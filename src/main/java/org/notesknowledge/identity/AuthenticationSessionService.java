package org.notesknowledge.identity;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Application-owned session projection, recent proof and current-session logout. */
@Service
@IdentityCoreEnabled
final class AuthenticationSessionService {
    record State(String state, String challengeId) { }
    private final IdentityPersistence identity;
    private final MfaChallengeService challenges;
    private final PasswordAuthenticationService passwords;
    private final SpringSessionAuthorityAdapter authority;
    private final ApplicationSessionDescriptorRepository descriptors;
    private final Clock clock;
    private final TransactionTemplate transactions;

    AuthenticationSessionService(IdentityPersistence identity, MfaChallengeService challenges,
            PasswordAuthenticationService passwords, SpringSessionAuthorityAdapter authority,
            ApplicationSessionDescriptorRepository descriptors, Clock clock,
            PlatformTransactionManager manager) {
        this.identity = identity;
        this.challenges = challenges;
        this.passwords = passwords;
        this.authority = authority;
        this.descriptors = descriptors;
        this.clock = clock;
        this.transactions = new TransactionTemplate(manager);
    }

    State state(HttpServletRequest request) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        String state = "anonymous";
        String challengeId = null;
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof IdentitySessionPrincipal principal
                && identity.isActive(principal.userId())) {
            if (authentication.getAuthorities().stream()
                    .anyMatch(a -> "ROLE_USER".equals(a.getAuthority()))) {
                state = "authenticated";
            } else if (authentication.getAuthorities().stream()
                    .anyMatch(a -> "ROLE_MFA_PENDING".equals(a.getAuthority()))) {
                state = "mfaRequired";
            }
            if ("mfaRequired".equals(state)) {
                challengeId = challenges.currentContinuation(principal.userId(), request);
            }
        }
        return new State(state, challengeId);
    }

    void reauthenticate(UUID userId, String password, HttpServletRequest request) {
        passwords.reauthenticate(userId, password);
        request.getSession().setAttribute(IdentitySessionState.RECENT_ATTRIBUTE,
                new IdentitySessionState.RecentAuthentication(userId, clock.instant(), "password"));
    }

    void logout(HttpServletRequest request) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        UUID userId = authentication != null
                && authentication.getPrincipal() instanceof IdentitySessionPrincipal principal
                ? principal.userId() : null;
        var session = request.getSession(false);
        if (session != null) {
            transactions.executeWithoutResult(status -> {
                if (userId != null) {
                    var current = authority.lockBySessionId(session.getId());
                    if (current != null) {
                        descriptors.revoke(userId, current.primaryId(), clock.instant());
                    }
                }
                session.invalidate();
            });
        }
        SecurityContextHolder.clearContext();
        if (userId != null) {
            try {
                identity.auditLogout(userId, clock.instant());
            } catch (RuntimeException failure) {
                // Revocation has already committed. Audit failure cannot restore authority.
                org.slf4j.LoggerFactory.getLogger(AuthController.class).warn("logout_audit_failed");
            }
        }
    }
}
