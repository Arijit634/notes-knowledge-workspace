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
    record OwnedLink(UUID id, String issuer, String subject) { }

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

    boolean reactivateLink(UUID userId, String issuer, String subject, Instant now) {
        return jdbc.sql("""
                update identity.external_identity_link
                set revoked_at = null, linked_at = :now
                where user_id = :user and issuer = :issuer and subject = :subject
                  and revoked_at is not null
                """).param("user", userId).param("issuer", issuer)
                .param("subject", subject).param("now", Timestamp.from(now)).update() == 1;
    }

    Optional<OwnedLink> ownedActiveLinkForUpdate(UUID userId, UUID linkId) {
        return jdbc.sql("""
                select external_identity_link_id, issuer, subject
                from identity.external_identity_link
                where user_id = :user and external_identity_link_id = :link
                  and revoked_at is null for update
                """).param("user", userId).param("link", linkId)
                .query((rs, row) -> new OwnedLink(
                        rs.getObject("external_identity_link_id", UUID.class),
                        rs.getString("issuer"), rs.getString("subject"))).optional();
    }

    boolean hasOtherUsableMethod(UUID userId, UUID excludingLinkId) {
        return jdbc.sql("""
                select exists(select 1 from identity.account a
                  where a.user_id = :user and a.password_verifier is not null)
                or exists(select 1 from identity.external_identity_link l
                  where l.user_id = :user and l.external_identity_link_id <> :link
                    and l.revoked_at is null)
                """).param("user", userId).param("link", excludingLinkId)
                .query(Boolean.class).single();
    }

    boolean revokeLink(UUID userId, UUID linkId, Instant now) {
        return jdbc.sql("""
                update identity.external_identity_link set revoked_at = :now
                where user_id = :user and external_identity_link_id = :link
                  and revoked_at is null
                """).param("user", userId).param("link", linkId)
                .param("now", Timestamp.from(now)).update() == 1;
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
