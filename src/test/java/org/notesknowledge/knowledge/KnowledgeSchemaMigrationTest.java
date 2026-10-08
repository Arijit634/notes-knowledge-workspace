package org.notesknowledge.knowledge;

import static org.assertj.core.api.Assertions.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("DATABASE") @Tag("SECURITY")
@Testcontainers
class KnowledgeSchemaMigrationTest {
    @Container static final PostgreSQLContainer postgres=new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18-trixie")
            .withDatabaseName("knowledge_upgrade").withUsername("knowledge_migrator").withPassword("synthetic-knowledge-migrator-password");
    @Test void forwardUpgradeAddsExactlyFiveApprovedRelationsAndPreservesExistingRows() throws Exception {
        var ds=new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
        var jdbc=new JdbcTemplate(ds);
        Flyway.configure().dataSource(ds).target("13").load().migrate();
        UUID owner=jdbc.queryForObject("insert into identity.account(user_id,canonical_email,display_email,email_verified_at,account_state,created_at,updated_at) values(uuidv7(),'synthetic@example.test','synthetic@example.test',now(),'active',now(),now()) returning user_id",UUID.class);
        UUID note=jdbc.queryForObject("insert into notes.note(note_id,owner_user_id,title,markdown,lifecycle_state,revision,ai_enabled,ai_generation,created_at,updated_at) values(uuidv7(),?,'Synthetic','https://example.test/saved','active',7,false,3,now(),now()) returning note_id",UUID.class,owner);
        var flyway=Flyway.configure().dataSource(ds).load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(5);
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForList("select tablename from pg_tables where schemaname='knowledge' order by tablename",String.class))
                .containsExactly("knowledge_work_intent","organization_suggestion","private_derived_representation","private_derived_segment","processing_policy","processing_policy_acknowledgement","public_derived_representation","public_derived_segment");
        assertThat(jdbc.queryForObject("select count(*) from pg_tables where schemaname in ('identity','notes','profile','knowledge','publishing','discovery','moderation')",Integer.class)).isEqualTo(35);
        assertThat(jdbc.queryForObject("select count(*) from notes.note where note_id=? and revision=7 and ai_generation=3 and not ai_enabled and markdown='https://example.test/saved'",Integer.class,note)).isEqualTo(1);
        assertThat(jdbc.queryForList("select extname from pg_extension",String.class)).contains("pg_trgm","vector");
        assertThat(jdbc.queryForObject("select count(*) from knowledge.processing_policy",Integer.class)).isZero();
        assertThat(jdbc.queryForList("select indexname from pg_indexes where schemaname='knowledge'",String.class))
                .contains("ux_knowledge_work_active_dedupe","ix_knowledge_work_ready","ix_knowledge_work_reclaim","ix_policy_ack_policy","ix_processing_policy_current");
        UUID policy=jdbc.queryForObject("insert into knowledge.processing_policy(policy_code,policy_version,policy_fingerprint,disclosure_revision,effective_at) values('synthetic',1,?,'synthetic-1',now()) returning processing_policy_id",UUID.class,"a".repeat(64));
        assertThatThrownBy(()->jdbc.update("insert into knowledge.processing_policy_acknowledgement(user_id,processing_policy_id,disclosure_revision) values(?,?,'wrong')",owner,policy))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        jdbc.update("insert into knowledge.processing_policy_acknowledgement(user_id,processing_policy_id,disclosure_revision) values(?,?,'synthetic-1')",owner,policy);
        assertThatThrownBy(()->jdbc.update("delete from identity.account where user_id=?",owner)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(jdbc.queryForList("select pg_get_constraintdef(oid) from pg_constraint where conrelid='knowledge.processing_policy_acknowledgement'::regclass and contype='p'",String.class))
                .containsExactly("PRIMARY KEY (user_id, processing_policy_id)");
        // A separately granted runtime role, not the migration superuser, can use the approved rows but cannot publish policy/DDL.
        jdbc.execute("create role knowledge_runtime nologin nosuperuser nocreatedb nocreaterole");
        jdbc.execute("grant usage on schema knowledge to knowledge_runtime");
        jdbc.execute("grant select on knowledge.processing_policy to knowledge_runtime");
        jdbc.execute("grant select,insert on knowledge.processing_policy_acknowledgement to knowledge_runtime");
        jdbc.execute("grant select,insert,update on knowledge.knowledge_work_intent to knowledge_runtime");
        try(var connection=ds.getConnection()) {
            var runtime=new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection,true));
            runtime.execute("set role knowledge_runtime");
            try {
                assertThat(runtime.queryForObject("select rolsuper from pg_roles where rolname=current_user",Boolean.class)).isFalse();
                runtime.update("insert into knowledge.processing_policy_acknowledgement(user_id,processing_policy_id,disclosure_revision) values(?,?,'synthetic-1') on conflict do nothing",owner,policy);
                assertThat(runtime.queryForObject("select count(*) from knowledge.processing_policy_acknowledgement where user_id=?",Integer.class,owner)).isEqualTo(1);
                var beans=new org.springframework.beans.factory.support.StaticListableBeanFactory();
                beans.addBean("jdbc",org.springframework.jdbc.core.simple.JdbcClient.create(runtime));
                var repository=new KnowledgeWorkRepository(beans.getBeanProvider(org.springframework.jdbc.core.simple.JdbcClient.class));
                var tx=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.support.JdbcTransactionManager(runtime.getDataSource()));
                var expected=new org.notesknowledge.knowledge.spi.PrivateAiSourceCurrentness.Expected(owner,note,null,7,3,null);
                var intent=tx.execute(s->repository.enqueue(KnowledgeWork.Kind.NOTE,expected));
                var claim=tx.execute(s->repository.claim(new org.notesknowledge.LeaseOwner("runtime"),KnowledgeWorkService.POLICY,1,false)).getFirst();
                assertThat(claim.intent().id()).isEqualTo(intent.id());
                assertThat(tx.<Boolean>execute(s->repository.heartbeat(claim,KnowledgeWorkService.POLICY))).isTrue();
                assertThat(tx.<Boolean>execute(s->repository.transition(claim,"obsolete",KnowledgeWork.Failure.INVALID_SOURCE,false))).isTrue();
                assertThatThrownBy(()->runtime.execute("create table knowledge.forbidden(id int)")) .isInstanceOf(org.springframework.dao.DataAccessException.class);
                assertThatThrownBy(()->runtime.update("update knowledge.processing_policy set policy_version=2 where processing_policy_id=?",policy)).isInstanceOf(org.springframework.dao.DataAccessException.class);
            } finally { runtime.execute("reset role"); }
        }
    }
}
