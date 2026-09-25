package org.notesknowledge.identity;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.session.Session;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/** Serializes MFA continuation on the existing framework-owned session row. */
@Repository
@IdentityCoreEnabled
class MfaSessionChallengeRepository {
    private final JdbcTemplate jdbc;
    private final JdbcIndexedSessionRepository sessions;

    MfaSessionChallengeRepository(JdbcTemplate jdbc, JdbcIndexedSessionRepository sessions) {
        this.jdbc = jdbc;
        this.sessions = sessions;
    }

    IdentitySessionState.Challenge lockAndRead(String sessionId, UUID userId) {
        // The caller's REQUIRED transaction holds this lock through proof consumption,
        // challenge mutation, audit, and (for success) session-ID rotation.
        boolean exists = !jdbc.queryForList("""
                select primary_id from identity.spring_session
                where session_id = ? for update
                """, String.class, sessionId).isEmpty();
        if (!exists) return null;

        // A request wrapper may have loaded an older attribute snapshot. Re-read only
        // after the lock, through Spring Session's own repository and transaction.
        Session persisted = sessions.findById(sessionId);
        if (persisted == null) return null;
        SecurityContext context = persisted.getAttribute("SPRING_SECURITY_CONTEXT");
        if (context == null || context.getAuthentication() == null
                || !context.getAuthentication().isAuthenticated()
                || !(context.getAuthentication().getPrincipal()
                        instanceof IdentitySessionPrincipal principal)
                || !userId.equals(principal.userId())
                || context.getAuthentication().getAuthorities().stream()
                        .noneMatch(a -> "ROLE_MFA_PENDING".equals(a.getAuthority()))) {
            return null;
        }
        Object challenge = persisted.getAttribute(IdentitySessionState.CHALLENGE_ATTRIBUTE);
        return challenge instanceof IdentitySessionState.Challenge current ? current : null;
    }

    void discardStaleRequestSession(HttpServletRequest request) {
        // Spring Session 4.1.1's filter saves its request-local wrapper at response
        // commit. Its public repository-attribute prefix identifies the private
        // current-session slot. Detach the stale wrapper without invalidating the
        // stable primary key now owned by a rotated winning session.
        String currentSessionSlot = SessionRepositoryFilter.SESSION_REPOSITORY_ATTR
                + ".CURRENT_SESSION";
        if (request.getAttribute(currentSessionSlot) == null) {
            throw new IllegalStateException("Spring Session request wrapper unavailable");
        }
        request.removeAttribute(currentSessionSlot);
        // Prevent any later getSession(false) (for example from CSRF/error handling)
        // from reattaching the cached stale wrapper before filter commit.
        request.setAttribute(SessionRepositoryFilter.SESSION_REPOSITORY_ATTR
                + ".invalidSessionId", Boolean.TRUE);
    }
}
