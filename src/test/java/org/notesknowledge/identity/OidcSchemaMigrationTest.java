package org.notesknowledge.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("DATABASE")
@Testcontainers
class OidcSchemaMigrationTest {
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            "pgvector/pgvector:0.8.6-pg18-trixie")
            .withDatabaseName("oidc_schema").withUsername("oidc_schema_migrator")
            .withPassword("synthetic-oidc-schema-migrator-password");

    @Test void v004AddsOnlyApprovedIdentityLinkWithConstraintsAndNoProviderMaterial() {
        Flyway flyway = migrate();
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        assertThat(flyway.info().applied()).extracting(m -> m.getScript())
                .containsExactly("V001__platform__spring_session.sql",
                        "V002__identity__account_verification_and_security_email.sql",
                        "V003__identity__mfa_core.sql",
                        "V004__identity__google_oidc_core.sql");
        JdbcTemplate jdbc = migrator();
        assertThat(jdbc.queryForObject("""
                select count(*) from information_schema.tables
                where table_schema = 'identity' and table_type = 'BASE TABLE'
                """, Integer.class)).isEqualTo(9);
        assertThat(jdbc.queryForList("""
                select column_name from information_schema.columns
                where table_schema = 'identity' and table_name = 'external_identity_link'
                order by ordinal_position
                """, String.class)).containsExactly("external_identity_link_id", "user_id",
                        "issuer", "subject", "linked_at", "revoked_at");
        assertThat(jdbc.queryForList("""
                select indexname from pg_indexes where schemaname = 'identity'
                  and tablename = 'external_identity_link'
                """, String.class)).contains("ix_external_identity_link_active_user",
                        "ux_external_identity_link_principal");
        UUID user = account(jdbc);
        UUID link = jdbc.queryForObject("select uuidv7()", UUID.class);
        assertThat(link.version()).isEqualTo(7);
        jdbc.update("""
                insert into identity.external_identity_link
                    (external_identity_link_id, user_id, issuer, subject, linked_at)
                values (?, ?, 'https://accounts.google.com', 'synthetic-subject', now())
                """, link, user);
        UUID another = account(jdbc);
        assertThatThrownBy(() -> jdbc.update("""
                insert into identity.external_identity_link
                    (external_identity_link_id, user_id, issuer, subject, linked_at)
                values (uuidv7(), ?, 'https://accounts.google.com', 'synthetic-subject', now())
                """, another)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("""
                insert into identity.external_identity_link
                    (external_identity_link_id, user_id, issuer, subject, linked_at)
                values (uuidv7(), ?, 'https://accounts.google.com', 'other-subject', now())
                """, UUID.randomUUID())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("""
                insert into identity.external_identity_link
                    (external_identity_link_id, user_id, issuer, subject, linked_at)
                values (uuidv7(), ?, 'https://accounts.google.com', '', now())
                """, another)).isInstanceOf(DataAccessException.class);
    }

    @Test void runtimeHasRequiredLinkDmlButNoDdl() {
        migrate();
        JdbcTemplate migrator = migrator();
        UUID user = account(migrator);
        migrator.execute("""
                create role oidc_runtime login password 'synthetic-oidc-runtime-password'
                nosuperuser nocreatedb nocreaterole noinherit
                """);
        migrator.execute("grant connect on database oidc_schema to oidc_runtime");
        migrator.execute("grant usage on schema identity to oidc_runtime");
        migrator.execute("grant select, insert, update on identity.external_identity_link to oidc_runtime");
        JdbcTemplate runtime = new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), "oidc_runtime", "synthetic-oidc-runtime-password"));
        assertThat(runtime.queryForObject("""
                select has_table_privilege(current_user,
                    'identity.external_identity_link', 'SELECT,INSERT,UPDATE')
                """, Boolean.class)).isTrue();
        assertThat(runtime.queryForObject("""
                select has_table_privilege(current_user,
                    'identity.external_identity_link', 'DELETE')
                """, Boolean.class)).isFalse();
        assertThat(runtime.queryForObject("""
                select has_schema_privilege(current_user, 'identity', 'CREATE')
                """, Boolean.class)).isFalse();
        runtime.update("""
                insert into identity.external_identity_link
                    (external_identity_link_id, user_id, issuer, subject, linked_at)
                values (uuidv7(), ?, 'https://accounts.google.com', 'runtime-subject', now())
                """, user);
        assertThatThrownBy(() -> runtime.execute("""
                create table identity.unauthorized_oidc_probe(id integer)
                """)).isInstanceOf(DataAccessException.class);
    }

    private Flyway migrate() {
        Flyway flyway = Flyway.configure().dataSource(postgres.getJdbcUrl(),
                postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").load();
        flyway.migrate();
        return flyway;
    }

    private JdbcTemplate migrator() {
        return new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(),
                postgres.getUsername(), postgres.getPassword()));
    }

    private UUID account(JdbcTemplate jdbc) {
        UUID user = jdbc.queryForObject("select uuidv7()", UUID.class);
        String email = "oidc-schema-" + user + "@example.test";
        jdbc.update("""
                insert into identity.account
                    (user_id, canonical_email, display_email, email_verified_at,
                     account_state, created_at, updated_at)
                values (?, ?, ?, now(), 'active', now(), now())
                """, user, email, email);
        return user;
    }
}
