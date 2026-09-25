package org.notesknowledge.identity;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Identity-owned issuer+subject and Account writes; uniqueness is PostgreSQL authority. */
@Repository
@IdentityCoreEnabled
class OidcIdentityRepository {
    record Link(UUID userId, boolean active) { }

    private final JdbcClient jdbc;

    OidcIdentityRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<Link> linkForUpdate(String issuer, String subject) {
        return jdbc.sql("""
                select user_id, revoked_at from identity.external_identity_link
                where issuer = :issuer and subject = :subject for update
                """).param("issuer", issuer).param("subject", subject)
                .query((rs, row) -> new Link(rs.getObject("user_id", UUID.class),
                        rs.getTimestamp("revoked_at") == null)).optional();
    }

    boolean createVerifiedAccount(UUID userId, String canonicalEmail, String displayEmail,
            Instant now) {
        return jdbc.sql("""
                insert into identity.account
                    (user_id, canonical_email, display_email, email_verified_at,
                     password_verifier, account_state, created_at, updated_at,
                     last_authenticated_at)
                values (:id, :canonical, :display, :now, null, 'active', :now, :now, :now)
                on conflict (canonical_email) do nothing
                """).param("id", userId).param("canonical", canonicalEmail)
                .param("display", displayEmail).param("now", Timestamp.from(now)).update() == 1;
    }

    boolean createLink(UUID userId, String issuer, String subject, Instant now) {
        return jdbc.sql("""
                insert into identity.external_identity_link
                    (external_identity_link_id, user_id, issuer, subject, linked_at)
                values (uuidv7(), :user, :issuer, :subject, :now)
                on conflict (issuer, subject) do nothing
                """).param("user", userId).param("issuer", issuer)
                .param("subject", subject).param("now", Timestamp.from(now)).update() == 1;
    }

    boolean markAuthenticatedIfEligible(UUID userId, Instant now) {
        return jdbc.sql("""
                update identity.account set last_authenticated_at = :now, updated_at = :now
                where user_id = :id and account_state = 'active'
                  and email_verified_at is not null
                """).param("id", userId).param("now", Timestamp.from(now)).update() == 1;
    }

    boolean lockEligibleAccount(UUID userId) {
        return jdbc.sql("""
                select user_id from identity.account
                where user_id = :id and account_state = 'active'
                  and email_verified_at is not null
                for update
                """).param("id", userId).query(UUID.class).optional().isPresent();
    }
}
