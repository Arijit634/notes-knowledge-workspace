package org.notesknowledge.identity;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@IdentityCoreEnabled
class JdbcMfaRepositoryAdapter implements MfaRepository {
    private final JdbcClient jdbc;
    JdbcMfaRepositoryAdapter(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override public Optional<Configuration> configuration(UUID userId) {
        return jdbc.sql("""
                select user_id, state, seed_ciphertext, seed_nonce, seed_tag, key_version,
                       last_accepted_timestep, current_recovery_generation, enrolled_at, activated_at
                from identity.mfa_configuration where user_id = :user
                """).param("user", userId).query((rs, row) -> new Configuration(
                rs.getObject("user_id", UUID.class), rs.getString("state"),
                new MfaSecretCipher.Envelope(rs.getBytes("seed_ciphertext"),
                        rs.getBytes("seed_nonce"), rs.getBytes("seed_tag"),
                        rs.getString("key_version")),
                rs.getObject("last_accepted_timestep", Long.class),
                rs.getLong("current_recovery_generation"),
                rs.getTimestamp("enrolled_at").toInstant(),
                rs.getTimestamp("activated_at") == null ? null
                        : rs.getTimestamp("activated_at").toInstant())).optional();
    }

    @Override public int begin(UUID userId, MfaSecretCipher.Envelope seed, Instant now) {
        return jdbc.sql("""
                insert into identity.mfa_configuration
                    (user_id, state, seed_ciphertext, seed_nonce, seed_tag,
                     key_version, enrolled_at)
                values (:user, 'enrollment_pending', :ciphertext, :nonce, :tag, :version, :now)
                on conflict (user_id) do update set
                    seed_ciphertext = excluded.seed_ciphertext,
                    seed_nonce = excluded.seed_nonce, seed_tag = excluded.seed_tag,
                    key_version = excluded.key_version, enrolled_at = excluded.enrolled_at
                where identity.mfa_configuration.state = 'enrollment_pending'
                """).param("user", userId).param("ciphertext", seed.ciphertext())
                .param("nonce", seed.nonce()).param("tag", seed.tag())
                .param("version", seed.keyVersion()).param("now", Timestamp.from(now)).update();
    }

    @Override public int activate(UUID userId, byte[] expectedNonce, long step, Instant now) {
        return jdbc.sql("""
                update identity.mfa_configuration set
                    state = 'active', last_accepted_timestep = :step,
                    current_recovery_generation = 1, activated_at = :now
                where user_id = :user and state = 'enrollment_pending'
                  and seed_nonce = :nonce
                """).param("user", userId).param("nonce", expectedNonce)
                .param("step", step).param("now", Timestamp.from(now)).update();
    }

    @Override public int advanceStep(UUID userId, Instant activation, long step) {
        return jdbc.sql("""
                update identity.mfa_configuration set last_accepted_timestep = :step
                where user_id = :user and state = 'active' and activated_at = :activated
                  and last_accepted_timestep < :step
                """).param("user", userId).param("activated", Timestamp.from(activation))
                .param("step", step).update();
    }

    @Override public void insertRecovery(UUID userId, long generation, byte[] digest, Instant now) {
        jdbc.sql("""
                insert into identity.mfa_recovery_code
                    (recovery_code_id, user_id, set_generation, verifier_digest, issued_at)
                values (uuidv7(), :user, :generation, :digest, :now)
                """).param("user", userId).param("generation", generation)
                .param("digest", digest).param("now", Timestamp.from(now)).update();
    }

    @Override public boolean consumeRecovery(UUID userId, byte[] digest, Instant now) {
        return jdbc.sql("""
                update identity.mfa_recovery_code r set consumed_at = :now
                from identity.mfa_configuration c
                where r.user_id = :user and r.user_id = c.user_id
                  and c.state = 'active'
                  and r.set_generation = c.current_recovery_generation
                  and r.verifier_digest = :digest
                  and r.consumed_at is null and r.revoked_at is null
                returning r.recovery_code_id
                """).param("user", userId).param("digest", digest)
                .param("now", Timestamp.from(now)).query(UUID.class).optional().isPresent();
    }
}
