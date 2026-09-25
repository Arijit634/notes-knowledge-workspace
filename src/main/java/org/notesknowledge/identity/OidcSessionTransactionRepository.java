package org.notesknowledge.identity;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.session.Session;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.stereotype.Repository;

/** Re-reads persisted Spring Session under its existing row lock after provider I/O. */
@Repository
@IdentityCoreEnabled
class OidcSessionTransactionRepository {
    private final JdbcTemplate jdbc;
    private final JdbcIndexedSessionRepository sessions;
    private final MfaSessionChallengeRepository staleRequests;

    OidcSessionTransactionRepository(JdbcTemplate jdbc, JdbcIndexedSessionRepository sessions,
            MfaSessionChallengeRepository staleRequests) {
        this.jdbc = jdbc;
        this.sessions = sessions;
        this.staleRequests = staleRequests;
    }

    OidcSessionTransaction lockAndRead(String sessionId, OidcProtocolPort.Action action,
            UUID userId) {
        boolean exists = !jdbc.queryForList("""
                select primary_id from identity.spring_session
                where session_id = ? for update
                """, String.class, sessionId).isEmpty();
        if (!exists) return null;
        Session persisted = sessions.findById(sessionId);
        if (persisted == null) return null;
        Object value = persisted.getAttribute(OidcSessionTransaction.ATTRIBUTE);
        if (!(value instanceof OidcSessionTransaction transaction)
                || transaction.action() != action
                || !sessionId.equals(transaction.sessionId())) return null;
        SecurityContext context = persisted.getAttribute("SPRING_SECURITY_CONTEXT");
        if (action == OidcProtocolPort.Action.LOGIN) {
            if (context != null && context.getAuthentication() != null
                    && context.getAuthentication().getPrincipal() instanceof IdentitySessionPrincipal) {
                return null;
            }
        } else {
            if (userId == null || !userId.equals(transaction.userId())
                    || context == null || context.getAuthentication() == null
                    || !context.getAuthentication().isAuthenticated()
                    || !(context.getAuthentication().getPrincipal()
                            instanceof IdentitySessionPrincipal principal)
                    || !userId.equals(principal.userId())
                    || context.getAuthentication().getAuthorities().stream()
                            .noneMatch(a -> "ROLE_USER".equals(a.getAuthority()))) return null;
        }
        return transaction;
    }

    void discardStale(HttpServletRequest request) {
        staleRequests.discardStaleRequestSession(request);
    }
}
