package org.notesknowledge.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("DATABASE")
@Testcontainers
class IdentitySchemaMigrationTest {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            "pgvector/pgvector:0.8.6-pg18-trixie")
            .withDatabaseName("identity_schema")
            .withUsername("identity_migrator")
            .withPassword("synthetic-identity-migrator-password");

    @Test
    void forwardMigrationCreatesOnlyApprovedMfaRelationsAndKeepsV001Intact() throws Exception {
        Flyway flyway = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").load();
        flyway.migrate();
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        assertThat(flyway.info().applied()).extracting(m -> m.getScript())
                .containsExactly("V001__platform__spring_session.sql",
                        "V002__identity__account_verification_and_security_email.sql",
                        "V003__identity__mfa_core.sql");
        byte[] v001 = Files.readAllBytes(Path.of("src/main/resources/db/migration/"
                + "V001__platform__spring_session.sql"));
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(v001)))
                .isEqualTo("9c4099d9f67f889181cd97dcebd347de2a9f1a3e58b384625aebe79717ea2258");
        byte[] v002 = Files.readAllBytes(Path.of("src/main/resources/db/migration/"
                + "V002__identity__account_verification_and_security_email.sql"));
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(v002)))
                .isEqualTo("8697914be30364a9b7a3c5aa317496dadd7df23248644d0342063657b78f131a");

        JdbcTemplate jdbc = jdbc(postgres.getUsername(), postgres.getPassword());
        assertThat(jdbc.queryForList("""
                select table_schema || '.' || table_name from information_schema.tables
                where table_schema in ('identity','profile','notes','knowledge',
                    'publishing','discovery','moderation') and table_type = 'BASE TABLE'
                order by table_schema, table_name
                """, String.class)).containsExactly(
                        "identity.account", "identity.identity_capability",
                        "identity.mfa_configuration", "identity.mfa_recovery_code",
                        "identity.security_audit_fact", "identity.security_email_delivery",
                        "identity.spring_session", "identity.spring_session_attributes");
        assertThat(jdbc.queryForList("""
                select indexname from pg_indexes where schemaname = 'identity'
                """, String.class)).contains("ix_security_email_delivery_ready",
                        "ix_security_email_delivery_reclaim",
                        "ux_security_email_delivery_capability",
                        "ux_security_email_delivery_event_notice");
        assertThatThrownBy(() -> jdbc.update("""
                insert into identity.security_email_delivery
                    (security_email_delivery_id, delivery_kind, state,
                     next_attempt_at, created_at, updated_at)
                values (uuidv7(), 'capability_link', 'queued', now(), now(), now())
                """)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void runtimeAuditRoleCanAppendButCannotRewriteEvidenceOrUseDdl() {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").load().migrate();
        JdbcTemplate migrator = jdbc(postgres.getUsername(), postgres.getPassword());
        migrator.execute("""
                create role identity_runtime login password 'synthetic-identity-runtime-password'
                nosuperuser nocreatedb nocreaterole noinherit
                """);
        migrator.execute("grant connect on database identity_schema to identity_runtime");
        migrator.execute("grant usage on schema identity to identity_runtime");
        migrator.execute("grant select, insert on identity.security_audit_fact to identity_runtime");
        JdbcTemplate runtime = jdbc("identity_runtime", "synthetic-identity-runtime-password");
        assertThat(runtime.queryForObject("""
                select has_table_privilege(current_user,
                    'identity.security_audit_fact', 'SELECT,INSERT')
                """, Boolean.class)).isTrue();
        assertThat(runtime.queryForObject("""
                select has_table_privilege(current_user,
                    'identity.security_audit_fact', 'UPDATE,DELETE')
                """, Boolean.class)).isFalse();
        assertThat(runtime.queryForObject("""
                select has_schema_privilege(current_user, 'identity', 'CREATE')
                """, Boolean.class)).isFalse();
        runtime.update("""
                insert into identity.security_audit_fact
                    (audit_fact_id, event_category, outcome_code, occurred_at)
                values (uuidv7(), 'synthetic_test', 'accepted', now())
                """);
        assertThatThrownBy(() -> runtime.update("""
                update identity.security_audit_fact set outcome_code = 'rewritten'
                """)).isInstanceOf(org.springframework.dao.DataAccessException.class)
                .hasRootCauseInstanceOf(org.postgresql.util.PSQLException.class);
        assertThatThrownBy(() -> runtime.execute("""
                create table identity.forbidden_probe(id integer)
                """)).isInstanceOf(org.springframework.dao.DataAccessException.class)
                .hasRootCauseInstanceOf(org.postgresql.util.PSQLException.class);
    }

    @Test
    void mfaConstraintsRejectInvalidShapeAndPreserveOneRowPerAccount() {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").load().migrate();
        JdbcTemplate jdbc = jdbc(postgres.getUsername(), postgres.getPassword());
        java.util.UUID user = jdbc.queryForObject("select uuidv7()", java.util.UUID.class);
        String email = "mfa-schema-" + user + "@example.test";
        jdbc.update("""
                insert into identity.account
                    (user_id, canonical_email, display_email, email_verified_at,
                     account_state, created_at, updated_at)
                values (?, ?, ?, now(), 'active', now(), now())
                """, user, email, email);
        byte[] seed = new byte[20], nonce = new byte[12], tag = new byte[16];
        assertThatThrownBy(() -> jdbc.update("""
                insert into identity.mfa_configuration
                    (user_id, state, seed_ciphertext, seed_nonce, seed_tag,
                     key_version, enrolled_at)
                values (?, 'invalid', ?, ?, ?, 'v1', now())
                """, user, seed, nonce, tag)).isInstanceOf(
                        org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                insert into identity.mfa_configuration
                    (user_id, state, seed_ciphertext, seed_nonce, seed_tag,
                     key_version, enrolled_at)
                values (?, 'enrollment_pending', ?, ?, ?, 'v1', now())
                """, user, new byte[19], nonce, tag)).isInstanceOf(
                        org.springframework.dao.DataIntegrityViolationException.class);
        jdbc.update("""
                insert into identity.mfa_configuration
                    (user_id, state, seed_ciphertext, seed_nonce, seed_tag,
                     key_version, enrolled_at)
                values (?, 'enrollment_pending', ?, ?, ?, 'v1', now())
                """, user, seed, nonce, tag);
        assertThatThrownBy(() -> jdbc.update("""
                insert into identity.mfa_configuration
                    (user_id, state, seed_ciphertext, seed_nonce, seed_tag,
                     key_version, enrolled_at)
                values (?, 'enrollment_pending', ?, ?, ?, 'v1', now())
                """, user, seed, nonce, tag)).isInstanceOf(
                        org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                insert into identity.mfa_recovery_code
                    (recovery_code_id, user_id, set_generation, verifier_digest, issued_at)
                values (uuidv7(), ?, 0, ?, now())
                """, user, new byte[32])).isInstanceOf(
                        org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                insert into identity.mfa_recovery_code
                    (recovery_code_id, user_id, set_generation, verifier_digest, issued_at)
                values (uuidv7(), ?, 1, ?, now())
                """, user, new byte[31])).isInstanceOf(
                        org.springframework.dao.DataIntegrityViolationException.class);
    }

    private JdbcTemplate jdbc(String username, String password) {
        return new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), username, password));
    }
}
