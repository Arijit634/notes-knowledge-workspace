package org.notesknowledge;

import static org.assertj.core.api.Assertions.*;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("DATABASE") @Tag("SECURITY")
@Testcontainers
class PreviewMigrationDatabaseTest {
    @Container static final PostgreSQLContainer DB = new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18-trixie")
        .withDatabaseName("nkw_preview").withUsername("nkw_preview_migrator").withPassword("synthetic-migrator-password");

    @Test void separatelyMigratedRuntimeHasCurrentSchemaWithoutDdlOrEvidenceRewriteAuthority() throws Exception {
        var flyway=Flyway.configure().dataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword()).load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(20);
        String password=java.util.Base64.getEncoder().encodeToString(new byte[32]);
        try(var owner=DriverManager.getConnection(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword())) {
            PreviewMigrationCommand.grantRuntime(owner,password);
            PreviewMigrationCommand.grantRuntime(owner,password);
            assertThatThrownBy(()->PreviewMigrationCommand.grantRuntime(owner,"invalid' password"))
                .isInstanceOf(IllegalArgumentException.class);
        }
        try(var runtime=DriverManager.getConnection(DB.getJdbcUrl(),"nkw_preview_runtime",password)) {
            OperationalDatabaseConfiguration.requireRuntimeRole(org.springframework.jdbc.core.simple.JdbcClient.create(
                new org.springframework.jdbc.datasource.DriverManagerDataSource(DB.getJdbcUrl(),"nkw_preview_runtime",password)));
            try(var statement=runtime.createStatement()) {
                assertThatThrownBy(()->statement.execute("create table notes.unauthorized_probe(id integer)"))
                    .isInstanceOf(java.sql.SQLException.class);
                assertThatThrownBy(()->statement.execute("update identity.security_audit_fact set outcome_code='changed'"))
                    .isInstanceOf(java.sql.SQLException.class);
                try(var rows=statement.executeQuery("select count(*) from public.flyway_schema_history where success")) {
                    rows.next();assertThat(rows.getInt(1)).isEqualTo(20);
                }
            }
        }
        assertThat(Flyway.configure().dataSource(DB.getJdbcUrl(),"nkw_preview_runtime",password).load().validateWithResult().validationSuccessful).isTrue();
    }
}
