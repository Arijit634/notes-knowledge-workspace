package org.notesknowledge.identity;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Identity-private conditional security writes; no other module accesses these relations. */
@Repository
@IdentityCoreEnabled
class IdentityPersistence {
    record Capability(UUID id, UUID userId, String purpose, byte[] digest, Instant expiresAt) { }
    record LoginAccount(UUID id, String verifier, String state, Instant verifiedAt) { }

    private final JdbcClient jdbc;
    IdentityPersistence(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<LoginAccount> loginAccount(String email) {
        return jdbc.sql("""
                select user_id, password_verifier, account_state, email_verified_at
                from identity.account where canonical_email = :email
                """).param("email", email).query((rs, row) -> new LoginAccount(
                rs.getObject("user_id", UUID.class), rs.getString("password_verifier"),
                rs.getString("account_state"), rs.getTimestamp("email_verified_at") == null ? null
                        : rs.getTimestamp("email_verified_at").toInstant())).optional();
    }

    boolean isActive(UUID userId) {
        return jdbc.sql("""
                select exists(select 1 from identity.account where user_id = :id
                    and account_state = 'active' and email_verified_at is not null)
                """).param("id", userId).query(Boolean.class).single();
    }

    Optional<String> currentPasswordVerifier(UUID userId) {
        return jdbc.sql("""
                select password_verifier from identity.account
                where user_id = :id and account_state = 'active'
                  and email_verified_at is not null
                """).param("id", userId).query(String.class).optional();
    }

    Optional<UUID> pendingAccountForUpdate(String email) {
        return jdbc.sql("""
                select user_id from identity.account
                where canonical_email = :email and account_state = 'pending_verification'
                for update
                """).param("email", email).query(UUID.class).optional();
    }

    Optional<Capability> capability(UUID id) {
        return jdbc.sql("""
                select capability_id, user_id, purpose, verifier_digest, expires_at
                from identity.identity_capability where capability_id = :id
                """).param("id", id).query((rs, row) -> new Capability(
                rs.getObject("capability_id", UUID.class), rs.getObject("user_id", UUID.class),
                rs.getString("purpose"), rs.getBytes("verifier_digest"),
                rs.getTimestamp("expires_at").toInstant())).optional();
    }

    boolean lockPendingAccount(UUID userId) {
        return jdbc.sql("""
                select user_id from identity.account
                where user_id = :id and account_state = 'pending_verification'
                for update
                """).param("id", userId).query(UUID.class).optional().isPresent();
    }

    void supersede(UUID userId, Instant now) {
        List<UUID> previous = jdbc.sql("""
                update identity.identity_capability
                set superseded_at = :now
                where user_id = :id and purpose = 'email_verification'
                  and consumed_at is null and superseded_at is null and revoked_at is null
                returning capability_id
                """).param("now", Timestamp.from(now)).param("id", userId)
                .query(UUID.class).list();
        for (UUID id : previous) {
            jdbc.sql("""
                    update identity.security_email_delivery
                    set state = 'obsolete', next_attempt_at = null,
                        lease_owner = null, lease_token = null, lease_until = null,
                        sealed_token_ciphertext = null, sealed_token_nonce = null,
                        sealed_token_tag = null, token_key_version = null,
                        terminal_at = :now, updated_at = :now,
                        last_failure_code = 'superseded'
                    where capability_id = :id and state in ('queued','retry_wait','claimed')
                    """).param("now", Timestamp.from(now)).param("id", id).update();
        }
    }

    void issue(UUID id, UUID userId, byte[] digest, Instant now, Instant expiry) {
        jdbc.sql("""
                insert into identity.identity_capability
                    (capability_id, user_id, purpose, verifier_digest, issued_at, expires_at)
                values (:id, :user, 'email_verification', :digest, :now, :expiry)
                """).param("id", id).param("user", userId).param("digest", digest)
                .param("now", Timestamp.from(now)).param("expiry", Timestamp.from(expiry)).update();
    }

    int consume(UUID id, byte[] digest, Instant now) {
        return jdbc.sql("""
                update identity.identity_capability set consumed_at = :now
                where capability_id = :id and purpose = 'email_verification'
                  and consumed_at is null and superseded_at is null and revoked_at is null
                  and expires_at > :now and verifier_digest = :digest
                """).param("id", id).param("digest", digest)
                .param("now", Timestamp.from(now)).update();
    }

    int verifyAccount(UUID userId, Instant now) {
        return jdbc.sql("""
                update identity.account
                set account_state = 'active', email_verified_at = :now, updated_at = :now
                where user_id = :id and account_state = 'pending_verification'
                """).param("id", userId).param("now", Timestamp.from(now)).update();
    }

    int authenticated(UUID userId, String oldVerifier, String replacement, Instant now) {
        return jdbc.sql("""
                update identity.account
                set password_verifier = coalesce(:replacement, password_verifier),
                    last_authenticated_at = :now, updated_at = :now
                where user_id = :id and account_state = 'active'
                  and email_verified_at is not null
                  and password_verifier = :old
                """).param("replacement", replacement).param("now", Timestamp.from(now))
                .param("id", userId).param("old", oldVerifier).update();
    }

    void audit(UUID target, String category, String outcome, Instant now) {
        jdbc.sql("""
                insert into identity.security_audit_fact
                    (audit_fact_id, target_user_id, event_category, outcome_code, occurred_at)
                values (uuidv7(), :target, :category, :outcome, :now)
                """).param("target", target, java.sql.Types.OTHER).param("category", category)
                .param("outcome", outcome).param("now", Timestamp.from(now)).update();
    }

    void auditFailure(UUID target, String category, String reason, Instant now) {
        jdbc.sql("""
                insert into identity.security_audit_fact
                    (audit_fact_id, target_user_id, event_category, outcome_code,
                     reason_code, occurred_at)
                values (uuidv7(), :target, :category, 'denied', :reason, :now)
                """).param("target", target, java.sql.Types.OTHER).param("category", category)
                .param("reason", reason).param("now", Timestamp.from(now)).update();
    }

    void auditLogout(UUID userId, Instant now) {
        jdbc.sql("""
                insert into identity.security_audit_fact
                    (audit_fact_id, actor_user_id, target_user_id,
                     event_category, outcome_code, occurred_at)
                values (uuidv7(), :user, :user, 'logout', 'success', :now)
                """).param("user", userId).param("now", Timestamp.from(now)).update();
    }

    Optional<String> currentVerificationDestination(UUID deliveryId, UUID capabilityId,
            Instant now) {
        return jdbc.sql("""
                select a.display_email
                from identity.security_email_delivery d
                join identity.identity_capability c on c.capability_id = d.capability_id
                join identity.account a on a.user_id = c.user_id
                where d.security_email_delivery_id = :delivery and d.capability_id = :capability
                  and d.delivery_kind = 'capability_link'
                  and c.purpose = 'email_verification' and c.consumed_at is null
                  and c.superseded_at is null and c.revoked_at is null and c.expires_at > :now
                  and a.account_state = 'pending_verification' and a.email_verified_at is null
                  and a.canonical_email = lower(a.display_email)
                """).param("delivery", deliveryId).param("capability", capabilityId)
                .param("now", Timestamp.from(now))
                .query(String.class).optional();
    }
}
