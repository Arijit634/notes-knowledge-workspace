package org.notesknowledge.identity;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.notesknowledge.LeaseOwner;
import org.notesknowledge.LeasePolicy;
import org.notesknowledge.LeaseToken;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Separate ready and reclaim paths; each claim transaction ends before worker I/O. */
@Repository
@IdentityCoreEnabled
class JdbcSecurityEmailDeliveryAdapter implements SecurityEmailDeliveryRepository {
    private static final String RETURNING = """
            returning work.security_email_delivery_id, work.capability_id, work.lease_token,
                      work.attempt_count, work.sealed_token_ciphertext, work.sealed_token_nonce,
                      work.sealed_token_tag, work.token_key_version
            """;

    private final JdbcClient jdbc;
    private final SecurityEmailDeliveryProperties properties;

    JdbcSecurityEmailDeliveryAdapter(JdbcClient jdbc, SecurityEmailDeliveryProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @Override
    public void queueCapability(UUID capabilityId, SecurityEmailMaterialCipher.Envelope envelope,
            Instant now) {
        jdbc.sql("""
                insert into identity.security_email_delivery
                    (security_email_delivery_id, delivery_kind, capability_id, state,
                     next_attempt_at, sealed_token_ciphertext, sealed_token_nonce,
                     sealed_token_tag, token_key_version, created_at, updated_at)
                values (uuidv7(), 'capability_link', :capability, 'queued', :now,
                        :ciphertext, :nonce, :tag, :version, :now, :now)
                """).param("capability", capabilityId).param("now", Timestamp.from(now))
                .param("ciphertext", envelope.ciphertext()).param("nonce", envelope.nonce())
                .param("tag", envelope.tag()).param("version", envelope.keyVersion()).update();
    }

    @Override
    @Transactional
    public List<Claim> claimReady(Instant now, LeaseOwner owner, LeasePolicy policy, int batchSize) {
        return claim("""
                with candidate as (
                    select security_email_delivery_id from identity.security_email_delivery
                    where state in ('queued','retry_wait') and next_attempt_at <= :now
                      and attempt_count < :maxAttempts
                    order by next_attempt_at, created_at, security_email_delivery_id
                    limit :batch for update skip locked
                )
                update identity.security_email_delivery work
                set state = 'claimed', attempt_count = work.attempt_count + 1,
                    lease_owner = :owner, lease_token = uuidv7(), lease_until = :until,
                    last_attempt_at = :now, updated_at = :now, next_attempt_at = null
                from candidate where work.security_email_delivery_id = candidate.security_email_delivery_id
                """ + RETURNING, now, owner, policy, batchSize);
    }

    @Override
    @Transactional
    public List<Claim> reclaimExpired(Instant now, LeaseOwner owner, LeasePolicy policy, int batchSize) {
        return claim("""
                with candidate as (
                    select security_email_delivery_id from identity.security_email_delivery
                    where state = 'claimed' and lease_until <= :now
                      and attempt_count < :maxAttempts
                    order by lease_until, created_at, security_email_delivery_id
                    limit :batch for update skip locked
                )
                update identity.security_email_delivery work
                set attempt_count = work.attempt_count + 1,
                    lease_owner = :owner, lease_token = uuidv7(), lease_until = :until,
                    last_attempt_at = :now, updated_at = :now, next_attempt_at = null
                from candidate where work.security_email_delivery_id = candidate.security_email_delivery_id
                """ + RETURNING, now, owner, policy, batchSize);
    }

    @Override
    @Transactional
    public int failExhausted(Instant now, int batchSize) {
        if (batchSize < 1 || batchSize > 1_000) {
            throw new IllegalArgumentException("Invalid cleanup batch");
        }
        return jdbc.sql("""
                with candidate as (
                    select security_email_delivery_id from identity.security_email_delivery
                    where attempt_count >= :maxAttempts
                      and ((state = 'claimed' and lease_until <= :now)
                        or (state in ('queued','retry_wait') and next_attempt_at <= :now))
                    order by updated_at, security_email_delivery_id
                    limit :batch for update skip locked
                )
                update identity.security_email_delivery work
                set state = 'failed', next_attempt_at = null,
                    lease_owner = null, lease_token = null, lease_until = null,
                    sealed_token_ciphertext = null, sealed_token_nonce = null,
                    sealed_token_tag = null, token_key_version = null,
                    sealed_recipient_ciphertext = null, sealed_recipient_nonce = null,
                    sealed_recipient_tag = null, recipient_key_version = null,
                    submitted_at = null, terminal_at = :now, updated_at = :now,
                    last_failure_code = 'attempt_limit'
                from candidate where work.security_email_delivery_id = candidate.security_email_delivery_id
                """).param("maxAttempts", properties.maxAttempts()).param("now", Timestamp.from(now))
                .param("batch", batchSize).update();
    }

    private List<Claim> claim(String sql, Instant now, LeaseOwner owner,
            LeasePolicy policy, int batchSize) {
        return jdbc.sql(sql)
                .param("now", Timestamp.from(now))
                .param("until", Timestamp.from(now.plus(policy.leaseDuration())))
                .param("maxAttempts", properties.maxAttempts())
                .param("owner", owner.alias())
                .param("batch", policy.checkedBatchSize(batchSize))
                .query((rs, row) -> new Claim(
                        rs.getObject("security_email_delivery_id", UUID.class),
                        rs.getObject("capability_id", UUID.class),
                        LeaseToken.fromDatabase(rs.getObject("lease_token", UUID.class)),
                        rs.getInt("attempt_count"),
                        new SecurityEmailMaterialCipher.Envelope(
                                rs.getBytes("sealed_token_ciphertext"),
                                rs.getBytes("sealed_token_nonce"),
                                rs.getBytes("sealed_token_tag"),
                                rs.getString("token_key_version"))))
                .list();
    }

    @Override
    public boolean ownsUsableClaim(Claim claim, Instant now) {
        return jdbc.sql("""
                select exists(select 1 from identity.security_email_delivery
                    where security_email_delivery_id = :id and state = 'claimed'
                      and lease_token = :token and lease_until > :now)
                """).param("id", claim.id()).param("token", claim.token().value())
                .param("now", Timestamp.from(now)).query(Boolean.class).single();
    }

    @Override
    @Transactional
    public boolean releaseUnstarted(Claim claim, Instant now) {
        return jdbc.sql("""
                update identity.security_email_delivery
                set state = 'queued', next_attempt_at = :now,
                    attempt_count = attempt_count - 1, lease_owner = null,
                    lease_token = null, lease_until = null, updated_at = :now
                where security_email_delivery_id = :id and state = 'claimed'
                  and lease_token = :token and lease_until > :now
                  and attempt_count > 0
                """).param("id", claim.id()).param("token", claim.token().value())
                .param("now", Timestamp.from(now)).update() == 1;
    }

    @Override
    @Transactional
    public boolean deferUnsent(Claim claim, Instant now, Instant retryAt, String safeReason) {
        if (!safeReason.matches("[a-z][a-z0-9_]{0,47}") || !retryAt.isAfter(now)) {
            throw new IllegalArgumentException("Invalid safe defer policy");
        }
        return jdbc.sql("""
                update identity.security_email_delivery
                set state = 'retry_wait', next_attempt_at = :retry,
                    attempt_count = attempt_count - 1, lease_owner = null,
                    lease_token = null, lease_until = null,
                    updated_at = :now, last_failure_code = :reason
                where security_email_delivery_id = :id and state = 'claimed'
                  and lease_token = :token and lease_until > :now
                  and attempt_count > 0
                """).param("id", claim.id()).param("token", claim.token().value())
                .param("now", Timestamp.from(now)).param("retry", Timestamp.from(retryAt))
                .param("reason", safeReason).update() == 1;
    }

    @Override
    @Transactional
    public boolean submitted(Claim claim, Instant now) {
        return terminal(claim, now, "submitted", null) == 1;
    }

    @Override
    @Transactional
    public boolean obsolete(Claim claim, Instant now, String safeReason) {
        return terminal(claim, now, "obsolete", safeReason) == 1;
    }

    @Override
    @Transactional
    public boolean failed(Claim claim, Instant now, String safeReason) {
        return terminal(claim, now, "failed", safeReason) == 1;
    }

    private int terminal(Claim claim, Instant now, String state, String reason) {
        if (reason != null && !reason.matches("[a-z][a-z0-9_]{0,47}")) {
            throw new IllegalArgumentException("Invalid safe failure code");
        }
        return jdbc.sql("""
                update identity.security_email_delivery
                set state = :state, next_attempt_at = null,
                    lease_owner = null, lease_token = null, lease_until = null,
                    sealed_token_ciphertext = null, sealed_token_nonce = null,
                    sealed_token_tag = null, token_key_version = null,
                    sealed_recipient_ciphertext = null, sealed_recipient_nonce = null,
                    sealed_recipient_tag = null, recipient_key_version = null,
                    submitted_at = case when :state = 'submitted'
                        then cast(:now as timestamptz) else null end,
                    terminal_at = :now, updated_at = :now, last_failure_code = :reason
                where security_email_delivery_id = :id and state = 'claimed'
                  and lease_token = :token and lease_until > :now
                """).param("state", state).param("now", Timestamp.from(now))
                .param("reason", reason).param("id", claim.id())
                .param("token", claim.token().value()).update();
    }

    @Override
    @Transactional
    public boolean retry(Claim claim, Instant now, Instant retryAt, String safeReason) {
        if (!safeReason.matches("[a-z][a-z0-9_]{0,47}")) {
            throw new IllegalArgumentException("Invalid safe failure code");
        }
        return jdbc.sql("""
                update identity.security_email_delivery
                set state = 'retry_wait', next_attempt_at = :retry,
                    lease_owner = null, lease_token = null, lease_until = null,
                    updated_at = :now, last_failure_code = :reason
                where security_email_delivery_id = :id and state = 'claimed'
                  and lease_token = :token and lease_until > :now
                """).param("retry", Timestamp.from(retryAt)).param("now", Timestamp.from(now))
                .param("reason", safeReason).param("id", claim.id())
                .param("token", claim.token().value()).update() == 1;
    }
}
