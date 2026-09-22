package org.notesknowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("DATABASE")
@Testcontainers
class DatabaseUuidV7GeneratorTest {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            "pgvector/pgvector:0.8.6-pg18-trixie")
            .withDatabaseName("uuidv7_runtime")
            .withUsername("uuidv7_migrator")
            .withPassword("synthetic-migrator-password");

    @Test
    void nativeUuidV7WorksOnRuntimeDatasourceWithoutDdlOrExtension() {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        JdbcClient migrator = JdbcClient.create(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        migrator.sql("""
                create role uuidv7_runtime login password 'synthetic-runtime-password'
                nosuperuser nocreatedb nocreaterole noinherit
                """).update();
        migrator.sql("grant connect on database uuidv7_runtime to uuidv7_runtime").update();

        var runtimeDataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), "uuidv7_runtime", "synthetic-runtime-password");
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(JdbcClient.class, () -> JdbcClient.create(runtimeDataSource));
            context.registerBean(DatabaseUuidV7Generator.class);
            context.refresh();

            var generator = context.getBean(DatabaseUuidV7Generator.class);
            var runtime = context.getBean(JdbcClient.class);
            HashSet<UUID> generated = new HashSet<>();
            for (int i = 0; i < 16; i++) {
                UUID id = generator.generate();
                assertThat(id).isNotNull();
                assertThat(id.version()).isEqualTo(7);
                assertThat(runtime.sql("select uuid_extract_version(:id)")
                        .param("id", id).query(Integer.class).single()).isEqualTo(7);
                generated.add(id);
            }
            assertThat(generated).hasSize(16);
            assertThat(runtime.sql("select current_user")
                    .query(String.class).single()).isEqualTo("uuidv7_runtime");
            assertThat(runtime.sql("select has_schema_privilege(current_user, 'identity', 'CREATE')")
                    .query(Boolean.class).single()).isFalse();
            assertThat(runtime.sql("select has_database_privilege(current_user, current_database(), 'CREATE')")
                    .query(Boolean.class).single()).isFalse();
            assertThat(runtime.sql("select count(*) from pg_extension where extname in ('uuid-ossp', 'pgcrypto')")
                    .query(Integer.class).single()).isZero();
        }
    }
}
