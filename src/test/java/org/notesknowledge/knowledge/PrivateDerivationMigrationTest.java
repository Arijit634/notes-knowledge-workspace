package org.notesknowledge.knowledge;

import static org.assertj.core.api.Assertions.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("DATABASE") @Tag("SECURITY") @Tag("RETRIEVAL")
@Testcontainers @TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PrivateDerivationMigrationTest {
    @Container static final PostgreSQLContainer postgres=new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18-trixie")
        .withDatabaseName("derivation_migration").withUsername("synthetic_migrator").withPassword("synthetic-derivation-password");
    JdbcTemplate jdbc;DriverManagerDataSource ds;Flyway flyway;UUID owner,note,policy;
    @BeforeAll void migrate() {
        ds=new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());jdbc=new JdbcTemplate(ds);
        Flyway.configure().dataSource(ds).target("14").load().migrate();
        owner=jdbc.queryForObject("insert into identity.account(user_id,canonical_email,display_email,email_verified_at,account_state,created_at,updated_at) values(uuidv7(),'synthetic@example.test','synthetic@example.test',now(),'active',now(),now()) returning user_id",UUID.class);
        note=jdbc.queryForObject("insert into notes.note(note_id,owner_user_id,title,markdown,lifecycle_state,revision,ai_enabled,ai_generation,created_at,updated_at) values(uuidv7(),?,'Synthetic','body','active',1,true,1,now(),now()) returning note_id",UUID.class,owner);
        policy=jdbc.queryForObject("insert into knowledge.processing_policy(policy_code,policy_version,policy_fingerprint,disclosure_revision,effective_at) values('synthetic',1,?,'synthetic',now()) returning processing_policy_id",UUID.class,"a".repeat(64));
        jdbc.update("insert into knowledge.knowledge_work_intent(owner_user_id,work_class,source_kind,source_note_id,expected_revision,expected_ai_generation,max_attempts,next_attempt_at,dedupe_key) values(?,'private_note_derivation','note',?,1,1,5,now(),?)",owner,note,"b".repeat(64));
        flyway=Flyway.configure().dataSource(ds).load();assertThat(flyway.migrate().migrationsExecuted).isEqualTo(2);
    }
    @Test void upgradePreservesOldWorkAsUnexecutableHistoryAndAddsExactlyThreeRelations() {
        assertThat(jdbc.queryForObject("select state from knowledge.knowledge_work_intent where dedupe_key=?",String.class,"b".repeat(64))).isEqualTo("obsolete");
        assertThat(jdbc.queryForObject("select target_lineage_id from knowledge.knowledge_work_intent where dedupe_key=?",String.class,"b".repeat(64))).isEqualTo("legacy_unassigned");
        assertThat(jdbc.queryForObject("select count(*) from pg_tables where schemaname in ('identity','notes','profile','knowledge','publishing','discovery','moderation')",Integer.class)).isEqualTo(25);
        assertThat(jdbc.queryForObject("select extversion from pg_extension where extname='vector'",String.class)).isEqualTo("0.8.6");
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForList("select indexdef from pg_indexes where schemaname='knowledge'",String.class)).noneMatch(s->s.toLowerCase().contains("hnsw")||s.toLowerCase().contains("ivfflat"));
    }
    @Test void rootIdentityCurrentUniquenessAndObsolescenceAreEnforced() {
        String lineage="c".repeat(64);UUID root=root(lineage);
        invalid(()->root(lineage));invalid(()->jdbc.update("update knowledge.private_derived_representation set source_revision=2 where derived_representation_id=?",root));
        jdbc.update("update knowledge.private_derived_representation set state='obsolete',obsolete_at=clock_timestamp() where derived_representation_id=?",root);
        invalid(()->jdbc.update("update knowledge.private_derived_representation set state='ready',obsolete_at=null where derived_representation_id=?",root));
        assertThat(root(lineage)).isNotEqualTo(root);
    }
    @Test void childOwnerLineageDimensionFiniteAndOrdinalAreEnforced() {
        UUID root=root("d".repeat(64));segment(root,owner,"d".repeat(64),"[1,0,0,0,0,0,0,0]",0);
        invalid(()->segment(root,owner,"d".repeat(64),"[1,0]",1));
        invalid(()->segment(root,owner,"d".repeat(64),"[NaN,0,0,0,0,0,0,0]",1));
        invalid(()->segment(root,owner,"d".repeat(64),"[0,0,0,0,0,0,0,0]",1));
        invalid(()->segment(root,new UUID(0,1),"d".repeat(64),"[1,0,0,0,0,0,0,0]",1));
        invalid(()->segment(root,owner,"e".repeat(64),"[1,0,0,0,0,0,0,0]",1));
        invalid(()->segment(root,owner,"d".repeat(64),"[1,0,0,0,0,0,0,0]",512));
        invalid(()->jdbc.update("update knowledge.private_derived_segment set surrogate_text='changed' where parent_id=?",root));
        invalid(()->jdbc.update("delete from notes.note where note_id=?",note));
        invalid(()->jdbc.update("delete from knowledge.private_derived_representation where derived_representation_id=?",root));
    }
    @Test void newWorkLineageIsImmutableAndDifferentLineagesCanBeQueued() {
        for(String lineage:java.util.List.of("f".repeat(64),"0".repeat(64)))jdbc.update("insert into knowledge.knowledge_work_intent(owner_user_id,work_class,source_kind,source_note_id,expected_revision,expected_ai_generation,max_attempts,next_attempt_at,dedupe_key,target_lineage_id) values(?,'private_note_derivation','note',?,1,1,5,now(),?,?)",owner,note,lineage,lineage);
        invalid(()->jdbc.update("update knowledge.knowledge_work_intent set target_lineage_id=? where target_lineage_id=?","1".repeat(64),"f".repeat(64)));
        invalid(()->jdbc.update("update knowledge.knowledge_work_intent set derivation_class='unapproved' where target_lineage_id=?","f".repeat(64)));
    }
    @Test void runtimeCanUseRepresentationsButCannotPerformDdl() throws Exception {
        jdbc.execute("create role synthetic_runtime nologin nosuperuser nocreatedb nocreaterole");
        jdbc.execute("grant usage on schema knowledge to synthetic_runtime");
        jdbc.execute("grant select,insert,update on knowledge.private_derived_representation to synthetic_runtime");
        jdbc.execute("grant select,insert on knowledge.private_derived_segment to synthetic_runtime");
        try(var connection=ds.getConnection()) {
            var runtime=new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection,true));runtime.execute("set role synthetic_runtime");
            try {assertThat(runtime.queryForObject("select rolsuper from pg_roles where rolname=current_user",Boolean.class)).isFalse();
                assertThat(runtime.queryForObject("select count(*) from knowledge.private_derived_representation",Integer.class)).isGreaterThanOrEqualTo(0);
                UUID root=runtime.queryForObject("insert into knowledge.private_derived_representation(owner_user_id,source_kind,source_note_id,source_revision,processing_generation,derivation_class,processing_policy_id,lineage_id,lineage_configuration,modality,embedding_dimension,distance_operator) values(?,'note',?,1,1,'text_surrogate',?,?,'synthetic-runtime','note',8,'cosine') returning derived_representation_id",UUID.class,owner,note,policy,"9".repeat(64));
                runtime.update("insert into knowledge.private_derived_segment(parent_id,owner_user_id,ordinal,surrogate_text,segment_kind,source_start,source_end,lineage_id,embedding_dimension,embedding) values(?,?,0,'synthetic','note_text',0,9,?,8,'[1,0,0,0,0,0,0,0]'::vector)",root,owner,"9".repeat(64));
                runtime.update("update knowledge.private_derived_representation set state='obsolete',obsolete_at=clock_timestamp() where derived_representation_id=?",root);
                assertThatThrownBy(()->runtime.execute("create table knowledge.forbidden(id int)")).isInstanceOf(org.springframework.dao.DataAccessException.class);
            } finally {runtime.execute("reset role");}
        }
    }
    private UUID root(String lineage) {return jdbc.queryForObject("insert into knowledge.private_derived_representation(owner_user_id,source_kind,source_note_id,source_revision,processing_generation,derivation_class,processing_policy_id,lineage_id,lineage_configuration,modality,embedding_dimension,distance_operator) values(?,'note',?,1,1,'text_surrogate',?,?,'synthetic-v1','note',8,'cosine') returning derived_representation_id",UUID.class,owner,note,policy,lineage);}
    private void segment(UUID root,UUID owner,String lineage,String vector,int ordinal){jdbc.update("insert into knowledge.private_derived_segment(parent_id,owner_user_id,ordinal,surrogate_text,segment_kind,source_start,source_end,lineage_id,embedding_dimension,embedding) values(?,?,?,'synthetic','note_text',0,9,?,8,?::vector)",root,owner,ordinal,lineage,vector);}
    private static void invalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable action){assertThatThrownBy(action).isInstanceOf(org.springframework.dao.DataAccessException.class);}
}
