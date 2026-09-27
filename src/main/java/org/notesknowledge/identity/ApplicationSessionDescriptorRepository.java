package org.notesknowledge.identity;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Safe display projection; Spring Session rows, never this table, grant authority. */
@Repository
@IdentityCoreEnabled
class ApplicationSessionDescriptorRepository {
    record Descriptor(String primaryId, UUID userId, String client, Instant createdAt,
            Instant lastSeenAt, Instant expiresAt, Instant revokedAt) { }

    private final JdbcClient jdbc;

    ApplicationSessionDescriptorRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    void recordFull(String primaryId, UUID userId, String client, Instant created,
            Instant seen, Instant expiry) {
        int changed = jdbc.sql("""
                insert into identity.application_session_descriptor
                    (session_primary_id, user_id, client_label, created_at, last_seen_at, expires_at)
                values (:primary, :owner, :client, :created, :seen, :expiry)
                on conflict (session_primary_id) do update set
                    last_seen_at = excluded.last_seen_at, expires_at = excluded.expires_at
                where identity.application_session_descriptor.user_id = excluded.user_id
                  and identity.application_session_descriptor.revoked_at is null
                """).param("primary", primaryId).param("owner", userId, java.sql.Types.OTHER)
                .param("client", client).param("created", Timestamp.from(created))
                .param("seen", Timestamp.from(seen)).param("expiry", Timestamp.from(expiry))
                .update();
        if (changed != 1) throw new IllegalStateException("Session descriptor cannot be established");
    }

    Descriptor find(UUID userId, String primaryId) {
        return jdbc.sql("""
                select session_primary_id, user_id, client_label, created_at, last_seen_at,
                       expires_at, revoked_at
                from identity.application_session_descriptor
                where user_id = :owner and session_primary_id = :primary
                """).param("owner", userId, java.sql.Types.OTHER).param("primary", primaryId)
                .query((rs, row) -> new Descriptor(rs.getString(1), rs.getObject(2, UUID.class),
                        rs.getString(3), rs.getTimestamp(4).toInstant(),
                        rs.getTimestamp(5).toInstant(), rs.getTimestamp(6).toInstant(),
                        rs.getTimestamp(7) == null ? null : rs.getTimestamp(7).toInstant()))
                .optional().orElse(null);
    }

    void revoke(UUID userId, String primaryId, Instant now) {
        jdbc.sql("""
                update identity.application_session_descriptor
                set revoked_at = greatest(:now, created_at)
                where user_id = :owner and session_primary_id = :primary and revoked_at is null
                """).param("now", Timestamp.from(now)).param("owner", userId, java.sql.Types.OTHER)
                .param("primary", primaryId).update();
    }
}
