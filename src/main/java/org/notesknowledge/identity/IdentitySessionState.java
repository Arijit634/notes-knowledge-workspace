package org.notesknowledge.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.security.core.context.SecurityContextHolder;

/** Server-only Spring Session facts; none are client credentials. */
final class IdentitySessionState {
    static final String CHALLENGE_ATTRIBUTE = "IDENTITY_MFA_CHALLENGE";
    static final String RECENT_ATTRIBUTE = "IDENTITY_RECENT_AUTH";

    record Challenge(String id, UUID userId, Instant activation, Instant expiresAt,
            int failedAttempts, String primaryMethod)
            implements Serializable {
        @Serial private static final long serialVersionUID = 1L;

        Challenge {
            if (failedAttempts < 0 || failedAttempts > 8) {
                throw new IllegalArgumentException("Invalid MFA challenge failure count");
            }
            if (!"password".equals(primaryMethod) && !"oidc".equals(primaryMethod)) {
                throw new IllegalArgumentException("Invalid MFA primary method");
            }
        }

        Challenge failed() {
            return new Challenge(id, userId, activation, expiresAt, failedAttempts + 1,
                    primaryMethod);
        }
    }

    private IdentitySessionState() { }

    static UUID principal(String requiredRole) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof IdentitySessionPrincipal principal)
                || authentication.getAuthorities().stream()
                        .noneMatch(authority -> requiredRole.equals(authority.getAuthority()))) {
            throw ApiFailureException.of(ApiFailureException.Kind.INVALID_CREDENTIALS);
        }
        return principal.userId();
    }

    static void requireRecent(HttpServletRequest request, UUID userId,
            Instant now, MfaProperties policy) {
        HttpSession session = request.getSession(false);
        Object value = session == null ? null : session.getAttribute(RECENT_ATTRIBUTE);
        requireRecent(value, userId, now, policy);
    }

    static void requireRecent(Object value, UUID userId, Instant now, MfaProperties policy) {
        if (!(value instanceof RecentAuthentication recent) || !userId.equals(recent.userId())
                || recent.at().isAfter(now)
                || !recent.at().plus(policy.recentAuthLifetime()).isAfter(now)) {
            throw ApiFailureException.of(ApiFailureException.Kind.RECENT_AUTHENTICATION_REQUIRED);
        }
    }

    record RecentAuthentication(UUID userId, Instant at, String method) implements Serializable {
        @Serial private static final long serialVersionUID = 1L;
    }
}
