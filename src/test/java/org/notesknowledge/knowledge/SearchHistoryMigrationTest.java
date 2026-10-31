package org.notesknowledge.knowledge;

import static org.assertj.core.api.Assertions.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("DATABASE") @Tag("SECURITY") @Testcontainers
class SearchHistoryMigrationTest {
    @Container static final PostgreSQLContainer postgres=new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18-trixie")
        .withDatabaseName("history_upgrade").withUsername("synthetic_migrator").withPassword("synthetic-history-migration-password");
    @Test void v020AddsOnlyTheApprovedHistoryRelationAndRestrictiveOwnerWorkBoundary(){
        var ds=new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());var jdbc=new JdbcTemplate(ds);
        Flyway.configure().dataSource(ds).target("19").load().migrate();var before=relations(jdbc);assertThat(before).hasSize(38);
        var flyway=Flyway.configure().dataSource(ds).load();assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        var after=relations(jdbc);assertThat(after).hasSize(39);assertThat(after.stream().filter(n->!before.contains(n))).containsExactly("knowledge.search_history_entry");
        assertThat(jdbc.queryForObject("select count(*) from pg_constraint where conrelid='knowledge.search_history_entry'::regclass and contype='f'",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from pg_constraint where conrelid='knowledge.search_history_entry'::regclass and contype='f' and confdeltype<>'r'",Integer.class)).isZero();
        assertThat(jdbc.queryForList("select indexname from pg_indexes where tablename='search_history_entry' and schemaname='knowledge'",String.class)).contains("ix_search_history_owner_page","ix_search_history_expiry","ux_search_history_work");
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where success",Integer.class)).isEqualTo(20);
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();assertThat(flyway.migrate().migrationsExecuted).isZero();
    }
    private static java.util.List<String> relations(JdbcTemplate jdbc){return jdbc.queryForList("select schemaname||'.'||tablename from pg_tables where schemaname in ('identity','profile','notes','knowledge','publishing','discovery','moderation') order by 1",String.class);}
}
