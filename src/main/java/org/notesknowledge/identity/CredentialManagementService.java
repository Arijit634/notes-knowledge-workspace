package org.notesknowledge.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Changes the current owner's credential with one committed session authority transition. */
@Service
@IdentityCoreEnabled
final class CredentialManagementService {
    private final IdentityPersistence identity;
    private final SpringSessionAuthorityAdapter sessions;
    private final MfaSessionTransitions transitions;
    private final MfaSessionChallengeRepository staleRequests;
    private final IdentitySessionTransitionCheckpoint checkpoint;
    private final PasswordEncoder passwords;
    private final Clock clock;
    private final MfaProperties policy;
    private final TransactionTemplate transactions;

    CredentialManagementService(IdentityPersistence identity,
            SpringSessionAuthorityAdapter sessions, MfaSessionTransitions transitions,
            MfaSessionChallengeRepository staleRequests,
            IdentitySessionTransitionCheckpoint checkpoint, PasswordEncoder passwords,
            Clock clock, MfaProperties policy, PlatformTransactionManager manager) {
        this.identity = identity;
        this.sessions = sessions;
        this.transitions = transitions;
        this.staleRequests = staleRequests;
        this.checkpoint = checkpoint;
        this.passwords = passwords;
        this.clock = clock;
        this.policy = policy;
        this.transactions = new TransactionTemplate(manager);
    }

    void setPassword(UUID userId, String newPassword, HttpServletRequest request,
            HttpServletResponse response) {
        HttpSession requestSession = request.getSession(false);
        if (requestSession == null) throw unauthenticated();
        IdentitySessionState.requireRecent(request, userId, clock.instant(), policy);
        IdentityInput.password(newPassword);
        // Argon2 must finish before any Account or Spring Session row lock.
        String verifier = passwords.encode(newPassword);
        String originalSessionId = requestSession.getId();
        boolean[] sessionMutationStarted = {false};
        try {
            transactions.executeWithoutResult(status -> {
                if (!identity.lockActiveAccount(userId)) throw unauthenticated();
                var current = sessions.lockCurrent(userId, originalSessionId);
                if (current == null) {
                    staleRequests.discardStaleRequestSession(request);
                    throw unauthenticated();
                }
                Instant now = clock.instant();
                Object persistedRecent = current.persisted().getAttribute(
                        IdentitySessionState.RECENT_ATTRIBUTE);
                IdentitySessionState.requireRecent(persistedRecent, userId, now, policy);
                if (identity.replacePassword(userId, verifier, now) != 1) {
                    throw new IllegalStateException("Locked Account could not change password");
                }
                checkpoint.afterPasswordMutation(request);
                sessions.revokeOthers(userId, current.primaryId());
                checkpoint.afterOtherSessionRevocation(request);
                sessionMutationStarted[0] = true;
                transitions.establish(userId, true, request, response);
                checkpoint.afterSessionMutation(request);
                identity.audit(userId, "password_change", "completed", now);
            });
        } catch (RuntimeException failure) {
            if (sessionMutationStarted[0]) {
                // The database rolled back, but Java's request wrapper did not.
                // Detach it; never invalidate the original committed session row.
                staleRequests.discardStaleRequestSession(request);
                SecurityContextHolder.clearContext();
            }
            throw failure;
        }
    }

    private static ApiFailureException unauthenticated() {
        return ApiFailureException.of(ApiFailureException.Kind.INVALID_CREDENTIALS);
    }
}
