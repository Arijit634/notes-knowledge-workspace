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
    record ResetState(Instant expiresAt, Instant consumedAt, Instant supersededAt,
            Instant revokedAt) { }
    record LoginAccount(UUID id, String verifier, String state, Instant verifiedAt) { }
    record EmailChangeState(UUID userId, String oldEmail, String oldDisplayEmail,
            String candidateEmail,
            Instant expiresAt, Instant consumedAt, Instant supersededAt, Instant revokedAt,
            byte[] digest) { }

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

    boolean hasActiveGoogleLink(UUID userId) {
        return jdbc.sql("""
                select exists(select 1 from identity.external_identity_link
                    where user_id = :user and issuer = 'https://accounts.google.com'
                      and revoked_at is null)
                """).param("user", userId).query(Boolean.class).single();
    }

    void auditMfaChange(UUID userId, UUID eventId, String category, String outcome,
            Instant now) {
        if (!("mfa".equals(category) && "disabled".equals(outcome))
                && !("mfa_recovery".equals(category) && "regenerated".equals(outcome))) {
            throw new IllegalArgumentException("Unsupported MFA audit action");
        }
        jdbc.sql("""
                insert into identity.security_audit_fact
                    (audit_fact_id, target_user_id, event_category, outcome_code,
                     correlation_id, occurred_at)
                values (uuidv7(), :user, :category, :outcome, :event, :now)
                """).param("user", userId).param("category", category)
                .param("outcome", outcome).param("event", eventId)
                .param("now", Timestamp.from(now)).update();
    }

    void auditOidcLinkChange(UUID userId, UUID eventId, boolean linked, Instant now) {
        jdbc.sql("""
                insert into identity.security_audit_fact
                    (audit_fact_id, actor_user_id, target_user_id, event_category,
                     outcome_code, correlation_id, occurred_at)
                values (uuidv7(), :user, :user, :category, :outcome, :event, :now)
                """).param("user", userId).param("category", linked ? "oidc_link" : "oidc_unlink")
                .param("outcome", linked ? "linked" : "unlinked")
                .param("event", eventId).param("now", Timestamp.from(now)).update();
    }

    Optional<UUID> pendingAccountForUpdate(String email) {
        return jdbc.sql("""
                select user_id from identity.account
                where canonical_email = :email and account_state = 'pending_verification'
                for update
                """).param("email", email).query(UUID.class).optional();
    }

    Optional<UUID> activeAccountForUpdate(String email) {
        return jdbc.sql("""
                select user_id from identity.account
                where canonical_email = :email and account_state = 'active'
                  and email_verified_at is not null
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

    Optional<ResetState> resetStateForUpdate(UUID id) {
        return jdbc.sql("""
                select expires_at, consumed_at, superseded_at, revoked_at
                from identity.identity_capability
                where capability_id = :id and purpose = 'password_reset'
                for update
                """).param("id", id).query((rs, row) -> new ResetState(
                rs.getTimestamp("expires_at").toInstant(),
                instantOrNull(rs.getTimestamp("consumed_at")),
                instantOrNull(rs.getTimestamp("superseded_at")),
                instantOrNull(rs.getTimestamp("revoked_at")))).optional();
    }

    private static Instant instantOrNull(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    Optional<String> capabilityPurpose(UUID id) {
        return jdbc.sql("select purpose from identity.identity_capability where capability_id = :id")
                .param("id", id).query(String.class).optional();
    }

    boolean lockPendingAccount(UUID userId) {
        return jdbc.sql("""
                select user_id from identity.account
                where user_id = :id and account_state = 'pending_verification'
                for update
                """).param("id", userId).query(UUID.class).optional().isPresent();
    }

    boolean isActiveAccount(UUID userId) {
        return jdbc.sql("select exists(select 1 from identity.account where user_id=:id and account_state='active' and email_verified_at is not null)")
                .param("id", userId).query(Boolean.class).single();
    }

    boolean lockActiveAccount(UUID userId) {
        return jdbc.sql("""
                select user_id from identity.account
                where user_id = :id and account_state = 'active'
                  and email_verified_at is not null
                for update
                """).param("id", userId).query(UUID.class).optional().isPresent();
    }

    void revokeOutstandingForDeletion(UUID userId, Instant now) {
        jdbc.sql("""
                update identity.identity_capability set revoked_at = :now
                where user_id = :user and consumed_at is null
                  and superseded_at is null and revoked_at is null
                """).param("user", userId).param("now", Timestamp.from(now)).update();
        // Claimed work is fenced by its now-terminal row. A send already in flight
        // cannot be cancelled externally, but its capability grants no authority.
        jdbc.sql("""
                update identity.security_email_delivery
                set state = 'obsolete', next_attempt_at = null,
                    lease_owner = null, lease_token = null, lease_until = null,
                    sealed_token_ciphertext = null, sealed_token_nonce = null,
                    sealed_token_tag = null, token_key_version = null,
                    sealed_recipient_ciphertext = null, sealed_recipient_nonce = null,
                    sealed_recipient_tag = null, recipient_key_version = null,
                    terminal_at = :now, updated_at = :now,
                    last_failure_code = 'account_deleted'
                where state in ('queued', 'retry_wait', 'claimed')
                  and (subject_user_id = :user or capability_id in
                      (select capability_id from identity.identity_capability
                       where user_id = :user))
                """).param("user", userId).param("now", Timestamp.from(now)).update();
    }

    int markLogicallyDeleted(UUID userId, Instant now) {
        return jdbc.sql("""
                update identity.account
                set account_state = 'logically_deleted', updated_at = :now
                where user_id = :user and account_state = 'active'
                  and email_verified_at is not null
                """).param("user", userId).param("now", Timestamp.from(now)).update();
    }

    void auditAccountDeletion(UUID userId, Instant now) {
        jdbc.sql("""
                insert into identity.security_audit_fact
                    (audit_fact_id, actor_user_id, target_user_id,
                     event_category, outcome_code, occurred_at)
                values (uuidv7(), :user, :user, 'account_deletion',
                        'logically_deleted', :now)
                """).param("user", userId).param("now", Timestamp.from(now)).update();
    }

    Optional<String> activeEmail(UUID userId) {
        return jdbc.sql("""
                select canonical_email from identity.account
                where user_id = :id and account_state = 'active'
                  and email_verified_at is not null
                """).param("id", userId).query(String.class).optional();
    }

    boolean emailOccupied(String canonical) {
        return jdbc.sql("select exists(select 1 from identity.account where canonical_email = :email)")
                .param("email", canonical).query(Boolean.class).single();
    }

    Optional<EmailChangeState> emailChangeState(UUID id, boolean lock) {
        return jdbc.sql("""
                select c.user_id, a.canonical_email, a.display_email,
                       c.candidate_canonical_email,
                       c.expires_at, c.consumed_at, c.superseded_at, c.revoked_at,
                       c.verifier_digest
                from identity.identity_capability c
                join identity.account a on a.user_id = c.user_id
                where c.capability_id = :id and c.purpose = 'email_change'
                """ + (lock ? " for update of c" : ""))
                .param("id", id).query((rs, row) -> new EmailChangeState(
                        rs.getObject("user_id", UUID.class), rs.getString("canonical_email"),
                        rs.getString("display_email"),
                        rs.getString("candidate_canonical_email"),
                        rs.getTimestamp("expires_at").toInstant(),
                        instantOrNull(rs.getTimestamp("consumed_at")),
                        instantOrNull(rs.getTimestamp("superseded_at")),
                        instantOrNull(rs.getTimestamp("revoked_at")),
                        rs.getBytes("verifier_digest"))).optional();
    }

    void supersedeEmailChange(UUID userId, Instant now) {
        List<UUID> previous = jdbc.sql("""
                update identity.identity_capability set superseded_at = :now
                where user_id = :user and purpose = 'email_change'
                  and consumed_at is null and superseded_at is null and revoked_at is null
                returning capability_id
                """).param("now", Timestamp.from(now)).param("user", userId)
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

    void issueEmailChange(UUID id, UUID userId, String candidate, byte[] digest,
            Instant now, Instant expiry) {
        jdbc.sql("""
                insert into identity.identity_capability
                    (capability_id, user_id, purpose, candidate_canonical_email,
                     verifier_digest, issued_at, expires_at)
                values (:id, :user, 'email_change', :candidate, :digest, :now, :expiry)
                """).param("id", id).param("user", userId).param("candidate", candidate)
                .param("digest", digest).param("now", Timestamp.from(now))
                .param("expiry", Timestamp.from(expiry)).update();
    }

    int consumeEmailChange(UUID id, byte[] digest, Instant now) {
        return jdbc.sql("""
                update identity.identity_capability set consumed_at = :now
                where capability_id = :id and purpose = 'email_change'
                  and consumed_at is null and superseded_at is null and revoked_at is null
                  and expires_at > :now and verifier_digest = :digest
                """).param("id", id).param("digest", digest)
                .param("now", Timestamp.from(now)).update();
    }

    int changeEmail(UUID userId, String expectedOld, String candidate, Instant now) {
        return jdbc.sql("""
                update identity.account
                set canonical_email = :candidate, display_email = :candidate,
                    email_verified_at = :now, updated_at = :now
                where user_id = :user and canonical_email = :old
                  and account_state = 'active' and email_verified_at is not null
                """).param("user", userId).param("old", expectedOld)
                .param("candidate", candidate).param("now", Timestamp.from(now)).update();
    }

    void auditEmailChange(UUID userId, UUID eventId, Instant now) {
        jdbc.sql("""
                insert into identity.security_audit_fact
                    (audit_fact_id, target_user_id, event_category, outcome_code,
                     correlation_id, occurred_at)
                values (uuidv7(), :user, 'email_change', 'completed', :event, :now)
                """).param("user", userId).param("event", eventId)
                .param("now", Timestamp.from(now)).update();
    }

    Optional<String> currentEmailChangeDestination(UUID deliveryId, UUID capabilityId,
            Instant now) {
        return jdbc.sql("""
                select c.candidate_canonical_email from identity.security_email_delivery d
                join identity.identity_capability c on c.capability_id = d.capability_id
                join identity.account a on a.user_id = c.user_id
                where d.security_email_delivery_id = :delivery and d.capability_id = :capability
                  and d.delivery_kind = 'capability_link' and c.purpose = 'email_change'
                  and c.consumed_at is null and c.superseded_at is null
                  and c.revoked_at is null and c.expires_at > :now
                  and a.account_state = 'active' and a.email_verified_at is not null
                  and a.canonical_email <> c.candidate_canonical_email
                  and not exists(select 1 from identity.account occupied
                      where occupied.canonical_email = c.candidate_canonical_email)
                """).param("delivery", deliveryId).param("capability", capabilityId)
                .param("now", Timestamp.from(now)).query(String.class).optional();
    }

    boolean activeNoticeSubject(UUID deliveryId, UUID subjectId, UUID eventId,
            String noticeKind) {
        return jdbc.sql("""
                select exists(select 1 from identity.security_email_delivery d
                join identity.account a on a.user_id = d.subject_user_id
                where d.security_email_delivery_id = :delivery and d.subject_user_id = :subject
                  and d.security_event_id = :event and d.notice_kind = :kind
                  and d.delivery_kind = 'security_notice' and a.account_state = 'active'
                  and a.email_verified_at is not null)
                """).param("delivery", deliveryId).param("subject", subjectId)
                .param("event", eventId).param("kind", noticeKind)
                .query(Boolean.class).single();
    }

    void supersedeReset(UUID userId, Instant now) {
        List<UUID> previous = jdbc.sql("""
                update identity.identity_capability set superseded_at = :now
                where user_id = :id and purpose = 'password_reset'
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

    void issueReset(UUID id, UUID userId, byte[] digest, Instant now, Instant expiry) {
        jdbc.sql("""
                insert into identity.identity_capability
                    (capability_id, user_id, purpose, verifier_digest, issued_at, expires_at)
                values (:id, :user, 'password_reset', :digest, :now, :expiry)
                """).param("id", id).param("user", userId).param("digest", digest)
                .param("now", Timestamp.from(now)).param("expiry", Timestamp.from(expiry)).update();
    }

    int consumeReset(UUID id, byte[] digest, Instant now) {
        return jdbc.sql("""
                update identity.identity_capability set consumed_at = :now
                where capability_id = :id and purpose = 'password_reset'
                  and consumed_at is null and superseded_at is null and revoked_at is null
                  and expires_at > :now and verifier_digest = :digest
                """).param("id", id).param("digest", digest)
                .param("now", Timestamp.from(now)).update();
    }

    void obsoleteConsumedReset(UUID id, Instant now) {
        jdbc.sql("""
                update identity.security_email_delivery
                set state = 'obsolete', next_attempt_at = null,
                    lease_owner = null, lease_token = null, lease_until = null,
                    sealed_token_ciphertext = null, sealed_token_nonce = null,
                    sealed_token_tag = null, token_key_version = null,
                    terminal_at = :now, updated_at = :now,
                    last_failure_code = 'consumed'
                where capability_id = :id and state in ('queued','retry_wait','claimed')
                """).param("id", id).param("now", Timestamp.from(now)).update();
    }

    int replacePassword(UUID userId, String verifier, Instant now) {
        return jdbc.sql("""
                update identity.account set password_verifier = :verifier, updated_at = :now
                where user_id = :id and account_state = 'active'
                  and email_verified_at is not null
                """).param("id", userId).param("verifier", verifier)
                .param("now", Timestamp.from(now)).update();
    }

    void auditResetCompleted(UUID userId, UUID eventId, Instant now) {
        jdbc.sql("""
                insert into identity.security_audit_fact
                    (audit_fact_id, target_user_id, event_category, outcome_code,
                     correlation_id, occurred_at)
                values (uuidv7(), :user, 'password_reset', 'completed', :event, :now)
                """).param("user", userId).param("event", eventId)
                .param("now", Timestamp.from(now)).update();
    }

    Optional<String> currentResetDestination(UUID deliveryId, UUID capabilityId, Instant now) {
        return jdbc.sql("""
                select a.display_email from identity.security_email_delivery d
                join identity.identity_capability c on c.capability_id = d.capability_id
                join identity.account a on a.user_id = c.user_id
                where d.security_email_delivery_id = :delivery and d.capability_id = :capability
                  and d.delivery_kind = 'capability_link' and c.purpose = 'password_reset'
                  and c.consumed_at is null and c.superseded_at is null
                  and c.revoked_at is null and c.expires_at > :now
                  and a.account_state = 'active' and a.email_verified_at is not null
                  and a.canonical_email = lower(a.display_email)
                """).param("delivery", deliveryId).param("capability", capabilityId)
                .param("now", Timestamp.from(now)).query(String.class).optional();
    }

    Optional<String> currentResetNoticeDestination(UUID deliveryId, UUID subjectId) {
        return jdbc.sql("""
                select a.display_email from identity.security_email_delivery d
                join identity.account a on a.user_id = d.subject_user_id
                where d.security_email_delivery_id = :delivery
                  and d.subject_user_id = :subject
                  and d.delivery_kind = 'security_notice'
                  and d.notice_kind = 'password_reset_completed'
                  and a.account_state = 'active' and a.email_verified_at is not null
                  and a.canonical_email = lower(a.display_email)
                """).param("delivery", deliveryId).param("subject", subjectId)
                .query(String.class).optional();
    }

    Optional<String> currentMfaNoticeDestination(UUID deliveryId, UUID subjectId,
            UUID eventId, String noticeKind) {
        if (!"mfa_disabled".equals(noticeKind) && !"mfa_reset".equals(noticeKind)) {
            return Optional.empty();
        }
        return jdbc.sql("""
                select a.display_email from identity.security_email_delivery d
                join identity.account a on a.user_id = d.subject_user_id
                where d.security_email_delivery_id = :delivery
                  and d.subject_user_id = :subject and d.security_event_id = :event
                  and d.delivery_kind = 'security_notice' and d.notice_kind = :kind
                  and d.capability_id is null
                  and d.sealed_token_ciphertext is null
                  and d.sealed_recipient_ciphertext is null
                  and a.account_state = 'active' and a.email_verified_at is not null
                  and a.canonical_email = lower(a.display_email)
                """).param("delivery", deliveryId).param("subject", subjectId)
                .param("event", eventId).param("kind", noticeKind)
                .query(String.class).optional();
    }

    Optional<String> currentOidcNoticeDestination(UUID deliveryId, UUID subjectId,
            UUID eventId, String noticeKind) {
        if (!"google_oidc_linked".equals(noticeKind)
                && !"google_oidc_unlinked".equals(noticeKind)) return Optional.empty();
        return jdbc.sql("""
                select a.display_email from identity.security_email_delivery d
                join identity.account a on a.user_id = d.subject_user_id
                where d.security_email_delivery_id = :delivery
                  and d.subject_user_id = :subject and d.security_event_id = :event
                  and d.delivery_kind = 'security_notice' and d.notice_kind = :kind
                  and d.capability_id is null
                  and d.sealed_token_ciphertext is null
                  and d.sealed_recipient_ciphertext is null
                  and a.account_state = 'active' and a.email_verified_at is not null
                  and a.canonical_email = lower(a.display_email)
                """).param("delivery", deliveryId).param("subject", subjectId)
                .param("event", eventId).param("kind", noticeKind)
                .query(String.class).optional();
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

    void auditSessionRevocation(UUID userId, String scope, Instant now) {
        jdbc.sql("""
                insert into identity.security_audit_fact
                    (audit_fact_id, actor_user_id, target_user_id,
                     event_category, outcome_code, occurred_at)
                values (uuidv7(), :user, :user, 'session_revocation', :scope, :now)
                """).param("user", userId).param("scope", scope)
                .param("now", Timestamp.from(now)).update();
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
