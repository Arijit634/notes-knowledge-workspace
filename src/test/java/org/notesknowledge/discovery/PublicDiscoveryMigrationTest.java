package org.notesknowledge.discovery;

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

@Tag("DATABASE") @Tag("SECURITY") @Testcontainers
class PublicDiscoveryMigrationTest {
    @Container static final PostgreSQLContainer postgres=new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18-trixie").withDatabaseName("public_discovery_migration").withUsername("synthetic_migrator").withPassword("synthetic-discovery-migration-password");
    @Test void forwardMigrationAddsExactlyThreeRelationsWithRestrictiveScopeIntegrity()throws Exception {
        var ds=new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());var jdbc=new JdbcTemplate(ds);
        Flyway.configure().dataSource(ds).target("17").load().migrate();var before=relations(jdbc);assertThat(before).hasSize(32);
        var flyway=Flyway.configure().dataSource(ds).target("18").load();assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);assertThat(flyway.validateWithResult().validationSuccessful).isTrue();assertThat(flyway.migrate().migrationsExecuted).isZero();
        var after=relations(jdbc);assertThat(after).hasSize(35);assertThat(after.stream().filter(s->!before.contains(s))).containsExactlyInAnyOrder("knowledge.public_derived_representation","knowledge.public_derived_segment","discovery.publication_like");
        var a=PublicDiscoveryFixtures.publication(jdbc,"Public","Copied public","films",Instant.now().minusSeconds(3600));var b=PublicDiscoveryFixtures.publication(jdbc,"Other","Other copied public","films",Instant.now().minusSeconds(3600));String lineage="c".repeat(64);
        UUID root=jdbc.queryForObject("insert into knowledge.public_derived_representation(publication_id,snapshot_revision,publication_generation,derivation_class,lineage_id,state) values(?,1,1,'text_surrogate',?,'current') returning derived_representation_id",UUID.class,a.id(),lineage);
        invalid(()->jdbc.update("insert into knowledge.public_derived_segment(derived_representation_id,publication_id,snapshot_revision,publication_generation,lineage_id,segment_order,location_kind,heading,source_start,source_end,text_content) values(?,?,1,1,?,0,'public_text','',0,6,'Public')",root,b.id(),lineage));
        invalid(()->jdbc.update("insert into knowledge.public_derived_segment(derived_representation_id,publication_id,snapshot_revision,publication_generation,lineage_id,segment_order,location_kind,heading,source_start,source_end,text_content) values(?,?,1,2,?,0,'public_text','',0,6,'Public')",root,a.id(),lineage));
        jdbc.update("insert into knowledge.public_derived_segment(derived_representation_id,publication_id,snapshot_revision,publication_generation,lineage_id,segment_order,location_kind,heading,source_start,source_end,text_content) values(?,?,1,1,?,0,'public_text','',0,6,'Public')",root,a.id(),lineage);
        invalid(()->jdbc.update("delete from publishing.publication where publication_id=?",a.id()));invalid(()->jdbc.update("delete from knowledge.public_derived_representation where derived_representation_id=?",root));
        jdbc.update("insert into discovery.publication_like(user_id,publication_id) values(?,?)",b.owner(),a.id());invalid(()->jdbc.update("insert into discovery.publication_like(user_id,publication_id) values(?,?)",b.owner(),a.id()));
        invalid(()->jdbc.update("delete from identity.account where user_id=?",b.owner()));
        invalid(()->jdbc.update("update knowledge.public_derived_representation set publication_generation=2 where derived_representation_id=?",root));
        jdbc.update("update knowledge.public_derived_representation set state='obsolete',updated_at=clock_timestamp() where derived_representation_id=?",root);invalid(()->jdbc.update("update knowledge.public_derived_representation set state='current' where derived_representation_id=?",root));
        assertThat(jdbc.queryForObject("select uuid_extract_version(derived_representation_id)::int from knowledge.public_derived_representation where derived_representation_id=?",Integer.class,root)).isEqualTo(7);
        assertThat(jdbc.queryForObject("select count(*) from pg_constraint where conrelid in ('knowledge.public_derived_representation'::regclass,'knowledge.public_derived_segment'::regclass,'discovery.publication_like'::regclass) and contype='f' and confdeltype<>'r'",Integer.class)).isZero();
        assertThat(jdbc.queryForList("select indexname from pg_indexes where schemaname in ('discovery','knowledge')",String.class)).contains("ix_public_work_ready","ix_public_work_reclaim","ix_discovery_public_fts","ix_discovery_public_title_trgm","ix_publication_like_target");
        String publicIntent="""
            insert into knowledge.knowledge_work_intent(scope_kind,owner_user_id,work_class,source_kind,
                source_publication_id,expected_publication_generation,expected_snapshot_revision,
                derivation_class,target_lineage_id,dedupe_key,max_attempts,next_attempt_at)
            values('public',?,'public_publication_derivation','publication',?,1,1,'text_surrogate',?,?,5,clock_timestamp())
            """;
        // Public work cannot acquire private owner authority, even when the Account FK is valid.
        invalid(()->jdbc.update(publicIntent,a.owner(),a.id(),lineage,"d".repeat(64)));
        invalid(()->jdbc.update(publicIntent.replace("'publication'","null"),null,a.id(),lineage,"e".repeat(64)));
        UUID work=jdbc.queryForObject(publicIntent+" returning knowledge_work_intent_id",UUID.class,null,a.id(),lineage,"d".repeat(64));
        invalid(()->jdbc.update("update knowledge.knowledge_work_intent set expected_publication_generation=2 where knowledge_work_intent_id=?",work));
        invalid(()->jdbc.update("update knowledge.knowledge_work_intent set scope_kind='private' where knowledge_work_intent_id=?",work));
    }
    private List<String> relations(JdbcTemplate jdbc){return jdbc.queryForList("select schemaname||'.'||tablename from pg_tables where schemaname in ('identity','profile','notes','publishing','discovery','knowledge','moderation') order by 1",String.class);}
    private void invalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable action){assertThatThrownBy(action).isInstanceOf(org.springframework.dao.DataAccessException.class);}
}
