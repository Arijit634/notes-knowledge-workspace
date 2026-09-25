package org.notesknowledge.compatibility;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("DATABASE")
@Testcontainers
@SpringBootTest
class PostgreSqlCompatibilityTest {

    private static final String IMAGE = "pgvector/pgvector:0.8.6-pg18-trixie";

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(IMAGE)
            .withDatabaseName("compatibility")
            .withUsername("compatibility")
            .withPassword("compatibility");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    @Autowired
    Flyway flyway;

    @Test
    void qualifiesPostgresExtensionsUuidV7FlywayAndTransactions() throws Exception {
        String serverVersion = jdbc.queryForObject("show server_version", String.class);
        assertThat(serverVersion).startsWith("18.");

        assertThat(availableExtensionVersion("vector")).isEqualTo("0.8.6");
        assertThat(availableExtensionVersion("pg_trgm")).isNotBlank();

        jdbc.execute("create extension if not exists vector");
        jdbc.execute("create extension if not exists pg_trgm");
        assertThat(installedExtensionVersion("vector")).isEqualTo("0.8.6");
        assertThat(installedExtensionVersion("pg_trgm")).isNotBlank();

        String uuid = jdbc.queryForObject("select uuidv7()::text", String.class);
        assertThat(uuid).matches("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");

        verifyTransactionalDdlRollback();

        assertThat(flyway.info().applied())
                .extracting(migration -> migration.getScript())
                .containsExactly("V001__platform__spring_session.sql",
                        "V002__identity__account_verification_and_security_email.sql",
                        "V003__identity__mfa_core.sql");
        assertThat(jdbc.queryForObject(
                "select to_regclass('public.flyway_schema_history') is not null", Boolean.class)).isTrue();
        assertThat(productRelationCount()).isEqualTo(8);
        assertThat(publicNonFrameworkRelationCount()).isZero();
    }

    private String availableExtensionVersion(String extension) {
        return jdbc.queryForObject(
                "select default_version from pg_available_extensions where name = ?",
                String.class,
                extension);
    }

    private String installedExtensionVersion(String extension) {
        return jdbc.queryForObject(
                "select extversion from pg_extension where extname = ?",
                String.class,
                extension);
    }

    private void verifyTransactionalDdlRollback() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.execute("create temporary table transaction_probe(value integer)");
                statement.executeUpdate("insert into transaction_probe(value) values (1)");
            }
            connection.rollback();
            try (var statement = connection.createStatement();
                    var result = statement.executeQuery("select to_regclass('pg_temp.transaction_probe')")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isNull();
            }
        }
    }

    private int productRelationCount() {
        return jdbc.queryForObject("""
                select count(*)
                from information_schema.tables
                where table_schema in ('identity', 'profile', 'notes', 'knowledge',
                                       'publishing', 'discovery', 'moderation')
                """, Integer.class);
    }

    private int publicNonFrameworkRelationCount() {
        return jdbc.queryForObject("""
                select count(*)
                from information_schema.tables
                where table_schema = 'public'
                  and table_type = 'BASE TABLE'
                  and table_name <> 'flyway_schema_history'
                """, Integer.class);
    }
}
