package org.notesknowledge.identity;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.session.Session;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Revokes persisted owner authority using Spring Session's transaction-participating repository. */
@Repository
@IdentityCoreEnabled
class SpringSessionAuthorityAdapter {
    private static final String LEGACY_INDEX = "IdentitySessionPrincipal[REDACTED]";
    private final JdbcClient jdbc;
    private final JdbcIndexedSessionRepository sessions;

    SpringSessionAuthorityAdapter(JdbcClient jdbc, JdbcIndexedSessionRepository sessions) {
        this.jdbc = jdbc;
        this.sessions = sessions;
    }

    int revokeAll(UUID userId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Session revocation requires the Identity transaction");
        }
        // Earlier authenticated sessions indexed Authentication.getName() via a
        // redacted toString(). Lock both index forms before inspecting authority;
        // changing the principal interface now gives future sessions a per-user key.
        var ids = jdbc.sql("""
                select session_id from identity.spring_session
                where principal_name in (:current, :legacy)
                order by primary_id for update
                """).param("current", userId.toString()).param("legacy", LEGACY_INDEX)
                .query(String.class).list();
        int revoked = 0;
        for (String id : ids) {
            Session session = sessions.findById(id);
            if (session == null) continue;
            SecurityContext context = session.getAttribute("SPRING_SECURITY_CONTEXT");
            if (context != null && context.getAuthentication() != null
                    && context.getAuthentication().getPrincipal()
                            instanceof IdentitySessionPrincipal principal
                    && userId.equals(principal.userId())) {
                sessions.deleteById(id);
                revoked++;
            }
        }
        return revoked;
    }
}
