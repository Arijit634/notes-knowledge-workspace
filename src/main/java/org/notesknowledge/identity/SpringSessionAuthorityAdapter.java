package org.notesknowledge.identity;

import java.util.List;
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
    private record CandidateLocation(String sessionId, long expiryTime) { }
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
        // The legacy index is shared by every old principal. Inspect it without
        // locking, then lock only an identified owner's stable primary row.
        int revoked = revokeIndexed(userId);
        List<String> legacyRows = jdbc.sql("""
                select primary_id from identity.spring_session
                where principal_name = :legacy order by primary_id
                """).param("legacy", LEGACY_INDEX).query(String.class).list();
        for (String primaryId : legacyRows) {
            Session candidate = findStableCandidate(primaryId);
            if (!belongsTo(candidate, userId)) continue;
            String lockedId = jdbc.sql("""
                    select session_id from identity.spring_session
                    where primary_id = :id for update
                    """).param("id", primaryId).query(String.class).optional().orElse(null);
            if (lockedId != null && belongsTo(sessions.findById(lockedId), userId)) {
                sessions.deleteById(lockedId);
                revoked++;
            }
        }
        // A legacy row may have migrated to the per-user index during inspection.
        return revoked + revokeIndexed(userId);
    }

    private int revokeIndexed(UUID userId) {
        List<String> ids = jdbc.sql("""
                select session_id from identity.spring_session
                where principal_name = :owner order by primary_id for update
                """).param("owner", userId.toString()).query(String.class).list();
        int revoked = 0;
        for (String id : ids) {
            if (belongsTo(sessions.findById(id), userId)) {
                sessions.deleteById(id);
                revoked++;
            }
        }
        return revoked;
    }

    private Session findStableCandidate(String primaryId) {
        // A concurrent session-ID rotation can invalidate a snapshot ID. Retry
        // against the framework's stable primary row rather than skipping it.
        for (int attempt = 0; attempt < 3; attempt++) {
            CandidateLocation location = jdbc.sql("""
                    select session_id, expiry_time from identity.spring_session
                    where primary_id = :id
                    """).param("id", primaryId).query((rs, row) -> new CandidateLocation(
                    rs.getString("session_id"), rs.getLong("expiry_time")))
                    .optional().orElse(null);
            if (location == null || location.expiryTime() <= System.currentTimeMillis()) {
                return null;
            }
            Session candidate = sessions.findById(location.sessionId());
            if (candidate != null) return candidate;
        }
        throw new IllegalStateException("Legacy session changed during revocation inspection");
    }

    private static boolean belongsTo(Session session, UUID userId) {
        if (session == null) return false;
        SecurityContext context = session.getAttribute("SPRING_SECURITY_CONTEXT");
        return context != null && context.getAuthentication() != null
                && context.getAuthentication().getPrincipal()
                        instanceof IdentitySessionPrincipal principal
                && userId.equals(principal.userId());
    }
}
