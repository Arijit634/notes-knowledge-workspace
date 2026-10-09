package org.notesknowledge.moderation;

import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.notesknowledge.PublicDiscoveryFixtures;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("DATABASE") @Tag("SECURITY") @Testcontainers @TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ModerationMigrationTest {
    @Container static final PostgreSQLContainer postgres=new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18-trixie").withDatabaseName("moderation_migration").withUsername("synthetic_migrator").withPassword("synthetic-moderation-migration-password");
    DriverManagerDataSource ds;JdbcTemplate jdbc;Flyway flyway;List<String> before;
    @BeforeAll void migrate() {
        ds=new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());jdbc=new JdbcTemplate(ds);
        Flyway.configure().dataSource(ds).target("18").load().migrate();before=relations();assertThat(before).hasSize(35);
        flyway=Flyway.configure().dataSource(ds).load();assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
    }
    @Test void addsOnlyThreeRelationsAndPreservesPostgresAndHistoricalMigrationTruth() {
        var after=relations();assertThat(after).hasSize(38);assertThat(after.stream().filter(s->!before.contains(s))).containsExactlyInAnyOrder("moderation.report","moderation.moderation_decision","moderation.moderation_audit_fact");
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForObject("select current_setting('server_version_num')::int",Integer.class)).isBetween(180000,189999);
        assertThat(jdbc.queryForObject("select extversion from pg_extension where extname='vector'",String.class)).isEqualTo("0.8.6");
        assertThat(jdbc.queryForObject("select count(*) from pg_constraint c join pg_namespace n on n.oid=c.connamespace where n.nspname='moderation' and c.contype='f' and c.confdeltype<>'r'",Integer.class)).isZero();
        assertThat(jdbc.queryForList("select column_name from information_schema.columns where table_schema='moderation'",String.class)).noneMatch(s->s.contains("note")||s.contains("attachment")||s.contains("credential")||s.contains("object"));
        assertThat(jdbc.queryForList("select indexname from pg_indexes where schemaname='moderation'",String.class)).contains("ux_report_open_reporter","ix_report_queue","ix_report_category_queue","ix_moderation_audit_report");
    }
    @Test void restrictiveForeignKeysCategoryLifecycleAndDuplicateConstraintsHold() {
        var p=publication();var reporter=PublicDiscoveryFixtures.account(jdbc);UUID r=report(p,reporter);
        invalid(()->report(p,reporter));invalid(()->jdbc.update("delete from publishing.publication where publication_id=?",p.id()));invalid(()->jdbc.update("delete from identity.account where user_id=?",reporter));
        invalid(()->jdbc.update("update moderation.report set category='invented' where report_id=?",r));
        invalid(()->jdbc.update("update moderation.report set state='actioned',resolved_at=now() where report_id=?",r));
        jdbc.update("update moderation.report set state='under_review',reviewed_at=clock_timestamp() where report_id=?",r);
        invalid(()->jdbc.update("update moderation.report set state='open',reviewed_at=null where report_id=?",r));
        jdbc.update("update moderation.report set state='dismissed',resolved_at=clock_timestamp() where report_id=?",r);
        invalid(()->jdbc.update("update moderation.report set state='under_review',resolved_at=null where report_id=?",r));
        assertThat(jdbc.queryForObject("select uuid_extract_version(report_id)::int from moderation.report where report_id=?",Integer.class,r)).isEqualTo(7);
    }
    @Test void decisionAndAuditAreImmutableAndCannotCrossReportPublicationScope() {
        var p=publication();var other=publication();var reporter=PublicDiscoveryFixtures.account(jdbc);UUID r=report(p,reporter);
        UUID decision=jdbc.queryForObject("insert into moderation.moderation_decision(moderation_decision_id,report_id,publication_id,actor_user_id,outcome_code,consequence,reason_code,evidence_generation,correlation_id,occurred_at) values(uuidv7(),?,?,?,'dismissed','none','noPolicyViolation',1,uuidv7(),now()) returning moderation_decision_id",UUID.class,r,p.id(),reporter);
        invalid(()->jdbc.update("insert into moderation.moderation_decision(moderation_decision_id,report_id,publication_id,actor_user_id,outcome_code,consequence,reason_code,evidence_generation,correlation_id,occurred_at) values(uuidv7(),?,?,?,'dismissed','none','noPolicyViolation',1,uuidv7(),now())",r,p.id(),reporter));
        invalid(()->jdbc.update("insert into moderation.moderation_audit_fact(audit_fact_id,report_id,publication_id,actor_user_id,moderation_decision_id,action_code,outcome_code,reason_code,correlation_id,occurred_at) values(uuidv7(),?,?,?,?,'decision','committed','noPolicyViolation',uuidv7(),now())",r,other.id(),reporter,decision));
        UUID audit=jdbc.queryForObject("insert into moderation.moderation_audit_fact(audit_fact_id,report_id,publication_id,actor_user_id,moderation_decision_id,action_code,outcome_code,reason_code,correlation_id,occurred_at) values(uuidv7(),?,?,?,?,'decision','committed','noPolicyViolation',uuidv7(),now()) returning audit_fact_id",UUID.class,r,p.id(),reporter,decision);
        assertThat(jdbc.queryForObject("select moderation_decision_id from moderation.moderation_audit_fact where audit_fact_id=?",UUID.class,audit)).isEqualTo(decision);
    }
    @Test void leastPrivilegeRuntimeCanAppendButNotRewriteOrProvision()throws Exception {
        var p=publication();var reporter=PublicDiscoveryFixtures.account(jdbc);UUID r=report(p,reporter);
        jdbc.execute("create role moderation_runtime nologin nosuperuser nocreatedb nocreaterole");jdbc.execute("grant usage on schema moderation,identity to moderation_runtime");
        jdbc.execute("grant select on moderation.report to moderation_runtime");jdbc.execute("grant select,insert on moderation.moderation_audit_fact,moderation.moderation_decision to moderation_runtime");
        try(var c=ds.getConnection();var s=c.createStatement()){
            s.execute("set role moderation_runtime");
            s.executeUpdate("insert into moderation.moderation_audit_fact(audit_fact_id,report_id,publication_id,actor_user_id,action_code,outcome_code,reason_code,correlation_id,occurred_at) values(uuidv7(),'"+r+"','"+p.id()+"','"+reporter+"','begin_review','committed','review_started',uuidv7(),now())");
            assertThatThrownBy(()->s.executeUpdate("delete from moderation.moderation_audit_fact")).isInstanceOf(java.sql.SQLException.class);
            assertThatThrownBy(()->s.executeUpdate("update moderation.moderation_decision set reason_code='noPolicyViolation'")).isInstanceOf(java.sql.SQLException.class);
            assertThatThrownBy(()->s.executeUpdate("create table moderation.unapproved(id int)")).isInstanceOf(java.sql.SQLException.class);
            assertThatThrownBy(()->s.executeQuery("select * from identity.privilege_assignment")).isInstanceOf(java.sql.SQLException.class);
            assertThatThrownBy(()->s.executeUpdate("truncate moderation.moderation_audit_fact")).isInstanceOf(java.sql.SQLException.class);
        }
    }
    private PublicDiscoveryFixtures.PublicRow publication(){return PublicDiscoveryFixtures.publication(jdbc,"Synthetic public","Copied text","safe",Instant.now().minusSeconds(3600));}
    private UUID report(PublicDiscoveryFixtures.PublicRow p,UUID reporter){return jdbc.queryForObject("insert into moderation.report(report_id,publication_id,publication_generation,reporter_user_id,category,description,state,submitted_at) values(uuidv7(),?,1,?,'spam','Synthetic context','open',now()) returning report_id",UUID.class,p.id(),reporter);}
    private List<String> relations(){return jdbc.queryForList("select schemaname||'.'||tablename from pg_tables where schemaname in ('identity','profile','notes','publishing','discovery','knowledge','moderation') order by 1",String.class);}
    private static void invalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable action){assertThatThrownBy(action).isInstanceOf(org.springframework.dao.DataAccessException.class);}
}
