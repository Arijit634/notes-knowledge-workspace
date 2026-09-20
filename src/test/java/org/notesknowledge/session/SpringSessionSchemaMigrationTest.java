package org.notesknowledge.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.util.HexFormat;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.notesknowledge.Application;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("DATABASE")
@Testcontainers
class SpringSessionSchemaMigrationTest {

    private static final String IMAGE = "pgvector/pgvector:0.8.6-pg18-trixie";
    private static final String FRAMEWORK_DDL =
            "org/springframework/session/jdbc/schema-postgresql.sql";
    private static final String FRAMEWORK_DDL_SHA256 =
            "d5333e408d24d210020c3a60d5e1e3f4b52e858b904d47d00b462dde20c24954";
    private static final int MIGRATION_CHECKSUM = 731467147;

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(IMAGE)
            .withDatabaseName("session_schema")
            .withUsername("session_migrator")
            .withPassword("synthetic-migrator-password");

    @Test
    void migratesFromZeroToTheExactStableTwoRelationShape() throws Exception {
        Flyway flyway = flyway(postgres.getJdbcUrl());
        JdbcTemplate jdbc = jdbc(postgres.getJdbcUrl());

        var firstMigration = flyway.migrate();
        assertThat(firstMigration.migrationsExecuted).isEqualTo(1);
        assertThat(firstMigration.targetSchemaVersion).hasToString("001");
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();

        var applied = flyway.info().applied();
        assertThat(applied).singleElement().satisfies(migration -> {
            assertThat(migration.getVersion()).hasToString("001");
            assertThat(migration.getScript()).isEqualTo("V001__platform__spring_session.sql");
        });
        Integer checksum = applied[0].getChecksum();
        assertThat(checksum).isEqualTo(MIGRATION_CHECKSUM);

        assertThat(jdbc.queryForObject(
                "select exists(select 1 from information_schema.schemata where schema_name = 'identity')",
                Boolean.class)).isTrue();
        assertThat(productRelations(jdbc)).containsExactly(
                "identity.spring_session",
                "identity.spring_session_attributes");
        assertThat(columns(jdbc, "spring_session")).containsExactly(
                new ColumnShape("primary_id", "character", 36, "NO"),
                new ColumnShape("session_id", "character", 36, "NO"),
                new ColumnShape("creation_time", "bigint", null, "NO"),
                new ColumnShape("last_access_time", "bigint", null, "NO"),
                new ColumnShape("max_inactive_interval", "integer", null, "NO"),
                new ColumnShape("expiry_time", "bigint", null, "NO"),
                new ColumnShape("principal_name", "character varying", 100, "YES"));
        assertThat(columns(jdbc, "spring_session_attributes")).containsExactly(
                new ColumnShape("session_primary_id", "character", 36, "NO"),
                new ColumnShape("attribute_name", "character varying", 200, "NO"),
                new ColumnShape("attribute_bytes", "bytea", null, "NO"));

        assertThat(primaryKeyColumns(jdbc, "spring_session"))
                .containsExactly("primary_id");
        assertThat(primaryKeyColumns(jdbc, "spring_session_attributes"))
                .containsExactly("session_primary_id", "attribute_name");
        assertThat(foreignKey(jdbc)).isEqualTo(new ForeignKeyShape(
                "spring_session_attributes_fk",
                "spring_session_attributes",
                "session_primary_id",
                "spring_session",
                "primary_id",
                "CASCADE"));
        assertThat(indexes(jdbc)).containsExactlyInAnyOrder(
                "spring_session_ix1",
                "spring_session_ix2",
                "spring_session_ix3",
                "spring_session_pk",
                "spring_session_attributes_pk");
        assertThat(jdbc.queryForObject(
                "select count(*) from pg_indexes where schemaname = 'identity' and indexname = 'spring_session_ix1' and indexdef like 'CREATE UNIQUE INDEX%'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from information_schema.tables where table_schema = 'public' and table_name like 'spring_session%'",
                Integer.class)).isZero();

        var secondMigration = flyway.migrate();
        assertThat(secondMigration.migrationsExecuted).isZero();
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        assertThat(flyway.info().applied()).singleElement()
                .extracting(migration -> migration.getChecksum())
                .isEqualTo(checksum);
        assertThat(productRelations(jdbc)).containsExactly(
                "identity.spring_session",
                "identity.spring_session_attributes");

        byte[] authoritativeDdl = new ClassPathResource(FRAMEWORK_DDL)
                .getInputStream().readAllBytes();
        assertThat(sha256(authoritativeDdl)).isEqualTo(FRAMEWORK_DDL_SHA256);
        assertThat(new String(authoritativeDdl, StandardCharsets.UTF_8))
                .contains("CREATE TABLE SPRING_SESSION")
                .contains("ON DELETE CASCADE")
                .contains("CREATE INDEX SPRING_SESSION_IX3");
    }

    @Test
    void applicationStartupCannotCreateSessionSchemaWhenMigrationIsAbsent() throws Exception {
        String databaseName = "session_without_migration";
        try (var connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("create database " + databaseName);
        }
        String emptyDatabaseUrl = "jdbc:postgresql://" + postgres.getHost() + ":"
                + postgres.getMappedPort(5432) + "/" + databaseName;

        try (var context = new SpringApplicationBuilder(Application.class)
                .web(WebApplicationType.SERVLET)
                .properties(
                        "server.port=0",
                        "spring.datasource.url=" + emptyDatabaseUrl,
                        "spring.datasource.username=" + postgres.getUsername(),
                        "spring.datasource.password=" + postgres.getPassword(),
                        "spring.flyway.enabled=false",
                        "spring.session.jdbc.initialize-schema=never",
                        "spring.session.jdbc.table-name=identity.spring_session")
                .run()) {
            JdbcTemplate jdbc = jdbc(emptyDatabaseUrl);
            assertThat(jdbc.queryForObject(
                    "select count(*) from information_schema.tables where table_schema = 'identity'",
                    Integer.class)).isZero();

            JdbcIndexedSessionRepository repository =
                    context.getBean(JdbcIndexedSessionRepository.class);
            SessionRepository<Session> publicRepository = publicRepository(repository);
            Session session = publicRepository.createSession();
            assertThatThrownBy(() -> publicRepository.save(session))
                    .isInstanceOf(DataAccessException.class);
            assertThat(jdbc.queryForObject(
                    "select count(*) from information_schema.tables where table_schema = 'identity'",
                    Integer.class)).isZero();
        }
    }

    private Flyway flyway(String url) {
        return Flyway.configure()
                .dataSource(url, postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load();
    }

    private JdbcTemplate jdbc(String url) {
        return new JdbcTemplate(new DriverManagerDataSource(
                url, postgres.getUsername(), postgres.getPassword()));
    }

    private List<String> productRelations(JdbcTemplate jdbc) {
        return jdbc.queryForList("""
                select table_schema || '.' || table_name
                from information_schema.tables
                where table_schema in ('identity', 'profile', 'notes', 'knowledge',
                                       'publishing', 'discovery', 'moderation')
                  and table_type = 'BASE TABLE'
                order by table_schema, table_name
                """, String.class);
    }

    private List<ColumnShape> columns(JdbcTemplate jdbc, String tableName) {
        return jdbc.query("""
                select column_name, data_type, character_maximum_length, is_nullable
                from information_schema.columns
                where table_schema = 'identity' and table_name = ?
                order by ordinal_position
                """, (result, row) -> new ColumnShape(
                        result.getString("column_name"),
                        result.getString("data_type"),
                        (Integer) result.getObject("character_maximum_length"),
                        result.getString("is_nullable")), tableName);
    }

    private List<String> primaryKeyColumns(JdbcTemplate jdbc, String tableName) {
        return jdbc.queryForList("""
                select key_column_usage.column_name
                from information_schema.table_constraints
                join information_schema.key_column_usage
                  on table_constraints.constraint_schema = key_column_usage.constraint_schema
                 and table_constraints.constraint_name = key_column_usage.constraint_name
                where table_constraints.table_schema = 'identity'
                  and table_constraints.table_name = ?
                  and table_constraints.constraint_type = 'PRIMARY KEY'
                order by key_column_usage.ordinal_position
                """, String.class, tableName);
    }

    private ForeignKeyShape foreignKey(JdbcTemplate jdbc) {
        return jdbc.queryForObject("""
                select constraints.constraint_name,
                       source.table_name,
                       source.column_name,
                       target.table_name as target_table,
                       target.column_name as target_column,
                       referential.delete_rule
                from information_schema.referential_constraints referential
                join information_schema.table_constraints constraints
                  on constraints.constraint_schema = referential.constraint_schema
                 and constraints.constraint_name = referential.constraint_name
                join information_schema.key_column_usage source
                  on source.constraint_schema = constraints.constraint_schema
                 and source.constraint_name = constraints.constraint_name
                join information_schema.constraint_column_usage target
                  on target.constraint_schema = referential.unique_constraint_schema
                 and target.constraint_name = referential.unique_constraint_name
                where constraints.constraint_schema = 'identity'
                """, (result, row) -> new ForeignKeyShape(
                        result.getString("constraint_name"),
                        result.getString("table_name"),
                        result.getString("column_name"),
                        result.getString("target_table"),
                        result.getString("target_column"),
                        result.getString("delete_rule")));
    }

    private List<String> indexes(JdbcTemplate jdbc) {
        return jdbc.queryForList("""
                select indexname
                from pg_indexes
                where schemaname = 'identity'
                order by indexname
                """, String.class);
    }

    private String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    @SuppressWarnings("unchecked")
    private SessionRepository<Session> publicRepository(
            JdbcIndexedSessionRepository repository) {
        return (SessionRepository<Session>) (SessionRepository<?>) repository;
    }

    private record ColumnShape(
            String name,
            String type,
            Integer length,
            String nullable) {
    }

    private record ForeignKeyShape(
            String name,
            String sourceTable,
            String sourceColumn,
            String targetTable,
            String targetColumn,
            String deleteRule) {
    }
}
