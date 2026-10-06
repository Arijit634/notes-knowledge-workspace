package org.notesknowledge.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
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
class SessionDescriptorSchemaMigrationTest {
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            "pgvector/pgvector:0.8.6-pg18-trixie")
            .withDatabaseName("session_descriptor_schema")
            .withUsername("session_schema_migrator")
            .withPassword("synthetic-session-schema-migrator-password");

    @Test void v005CreatesOnlySafeDescriptorWithFrameworkCompatiblePrimaryKey() {
        Flyway flyway = migrate();
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        assertThat(flyway.info().applied()).extracting(m -> m.getScript())
                .containsExactly("V001__platform__spring_session.sql",
                        "V002__identity__account_verification_and_security_email.sql",
                        "V003__identity__mfa_core.sql",
                        "V004__identity__google_oidc_core.sql",
                        "V005__identity__application_session_descriptor.sql",
                        "V006__identity__privilege_assignment.sql",
                        "V007__notes__editor_core.sql", "V008__notes__tags.sql", "V009__notes__versions.sql",
                        "V010__profile__private_core.sql", "V011__profile__avatar_management.sql",
                        "V012__notes__attachment_upload_core.sql", "V013__notes__ordinary_search.sql");
        JdbcTemplate jdbc = migrator();
        assertThat(jdbc.queryForObject("""
                select count(*) from information_schema.tables
                where table_schema in ('identity','profile','notes','knowledge',
                    'publishing','discovery','moderation') and table_type = 'BASE TABLE'
                """, Integer.class)).isEqualTo(19);
        assertThat(jdbc.queryForList("""
                select column_name from information_schema.columns
                where table_schema = 'identity' and table_name = 'application_session_descriptor'
                order by ordinal_position
                """, String.class)).containsExactly("session_primary_id", "user_id",
                        "client_label", "created_at", "last_seen_at", "expires_at", "revoked_at");
        assertThat(jdbc.queryForObject("""
                select character_maximum_length from information_schema.columns
                where table_schema = 'identity' and table_name = 'application_session_descriptor'
                  and column_name = 'session_primary_id'
                """, Integer.class)).isEqualTo(36);
        assertThat(jdbc.queryForList("""
                select indexname from pg_indexes where schemaname = 'identity'
                  and tablename = 'application_session_descriptor'
                """, String.class)).contains("ix_application_session_descriptor_active_owner",
                        "ix_application_session_descriptor_owner_lifecycle");
        UUID owner = account(jdbc);
        String primary = UUID.randomUUID().toString();
        jdbc.update("""
                insert into identity.application_session_descriptor
                    (session_primary_id, user_id, client_label, created_at, last_seen_at, expires_at)
                values (?, ?, 'Existing session', now(), now(), now() + interval '1 hour')
                """, primary, owner);
        assertThatThrownBy(() -> jdbc.update("""
                insert into identity.application_session_descriptor
                    (session_primary_id, user_id, client_label, created_at, last_seen_at, expires_at)
                values (?, ?, 'x', now(), now(), now())
                """, UUID.randomUUID().toString(), UUID.randomUUID()))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("""
                update identity.application_session_descriptor set client_label = E'raw\\nagent'
                where session_primary_id = ?
                """, primary)).isInstanceOf(DataAccessException.class);
    }

    @Test void runtimeCanReadInsertUpdateButCannotDeleteOrCreate() {
        migrate();
        JdbcTemplate migrator = migrator();
        UUID owner = account(migrator);
        migrator.execute("""
                create role session_descriptor_runtime login
                password 'synthetic-session-descriptor-runtime-password'
                nosuperuser nocreatedb nocreaterole noinherit
                """);
        migrator.execute("grant connect on database session_descriptor_schema to session_descriptor_runtime");
        migrator.execute("grant usage on schema identity to session_descriptor_runtime");
        migrator.execute("grant select, insert, update on identity.application_session_descriptor to session_descriptor_runtime");
        JdbcTemplate runtime = new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), "session_descriptor_runtime",
                "synthetic-session-descriptor-runtime-password"));
        String primary = UUID.randomUUID().toString();
        runtime.update("""
                insert into identity.application_session_descriptor
                    (session_primary_id, user_id, client_label, created_at, last_seen_at, expires_at)
                values (?, ?, 'Existing session', now(), now(), now() + interval '1 hour')
                """, primary, owner);
        assertThat(runtime.update("""
                update identity.application_session_descriptor set revoked_at = now()
                where session_primary_id = ?
                """, primary)).isEqualTo(1);
        assertThatThrownBy(() -> runtime.update(
                "delete from identity.application_session_descriptor where session_primary_id = ?",
                primary)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> runtime.execute(
                "create table identity.unauthorized_session_probe (id integer)"))
                .isInstanceOf(DataAccessException.class);
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
        UUID owner = jdbc.queryForObject("select uuidv7()", UUID.class);
        String email = "schema-" + owner.toString().substring(24) + "@example.test";
        jdbc.update("""
                insert into identity.account (user_id, canonical_email, display_email,
                    email_verified_at, account_state, created_at, updated_at)
                values (?, ?, ?, now(), 'active', now(), now())
                """, owner, email, email);
        return owner;
    }
}
