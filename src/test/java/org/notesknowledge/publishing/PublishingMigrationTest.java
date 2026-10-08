package org.notesknowledge.publishing;

import static org.assertj.core.api.Assertions.*;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("DATABASE") @Tag("SECURITY")
@Testcontainers @TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PublishingMigrationTest {
    @Container static final PostgreSQLContainer postgres=new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18-trixie")
        .withDatabaseName("public_migration").withUsername("synthetic_migrator").withPassword("synthetic-public-migration-password");
    DriverManagerDataSource ds;JdbcTemplate jdbc;Flyway flyway;List<String> before;
    @BeforeAll void migrate(){
        ds=new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());jdbc=new JdbcTemplate(ds);
        Flyway.configure().dataSource(ds).target("16").load().migrate();before=relations();
        assertThat(before).hasSize(25);flyway=Flyway.configure().dataSource(ds).load();assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
    }
    @Test void forwardMigrationAddsOnlySevenAuthorizedRelationsAndSafeIndexes(){
        assertThat(relations()).hasSize(32);assertThat(relations().stream().filter(s->!before.contains(s)).toList()).containsExactlyInAnyOrder(
            "profile.public_profile_projection","publishing.publication","publishing.publication_snapshot_tag","publishing.publication_public_media",
            "publishing.publication_audit_fact","discovery.publication_projection","discovery.publication_view_aggregate");
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForObject("select current_setting('server_version_num')::int",Integer.class)).isBetween(180000,189999);
        assertThat(jdbc.queryForObject("select extversion from pg_extension where extname='vector'",String.class)).isEqualTo("0.8.6");
        assertThat(jdbc.queryForList("select indexname from pg_indexes where schemaname in ('publishing','profile','discovery')",String.class))
            .contains("ux_public_profile_active_handle","ux_publication_source","ux_publication_snapshot","ix_publication_owner_page","ix_discovery_author_page");
        assertThat(jdbc.queryForObject("select count(*) from pg_constraint c join pg_namespace n on n.oid=c.connamespace where n.nspname in ('publishing','discovery') and c.contype='f' and (c.confdeltype<>'r' or c.confupdtype<>'a')",Integer.class)).isZero();
        assertThat(jdbc.queryForList("select pg_get_constraintdef(c.oid) from pg_constraint c join pg_class r on r.oid=c.conrelid join pg_namespace n on n.oid=r.relnamespace where n.nspname='publishing' and c.contype='f'",String.class))
            .noneMatch(s->s.contains("notes.note")||s.contains("notes.attachment"));
    }
    @Test void ownerPairUniquenessGenerationAndPublicChildIntegrityAreEnforced(){
        var p=publication();invalid(()->jdbc.update("update publishing.publication set snapshot_revision=0,publication_generation=publication_generation+1 where publication_id=?",p.id));
        invalid(()->jdbc.update("update publishing.publication set publication_generation=publication_generation where publication_id=?",p.id));
        invalid(()->jdbc.update("insert into publishing.publication_snapshot_tag values(?,2,'synthetic','Synthetic')",p.id));
        jdbc.update("insert into publishing.publication_snapshot_tag values(?,1,'synthetic','Synthetic')",p.id);
        invalid(()->jdbc.update("update publishing.publication set snapshot_revision=2,publication_generation=2 where publication_id=?",p.id));
        invalid(()->jdbc.update("delete from profile.public_profile_projection where public_profile_projection_id=?",p.author));
        invalid(()->jdbc.update("delete from identity.account where user_id=?",p.owner));
        UUID another=owner();invalid(()->jdbc.update("update publishing.publication set owner_user_id=?,publication_generation=2 where publication_id=?",another,p.id));
        invalid(()->media(p.id,2,"image/png",6));media(p.id,1,"image/png",6);
        invalid(()->media(p.id,1,"image/svg+xml",6));invalid(()->media(p.id,1,"image/png",5242881));
        jdbc.update("insert into publishing.publication_audit_fact values(uuidv7(),?,?,'create','committed','owner_publish',1,1,now())",p.id,p.owner);
        invalid(()->jdbc.update("delete from publishing.publication_audit_fact where publication_id=?",p.id));
        invalid(()->jdbc.update("update publishing.publication_audit_fact set outcome_code='changed' where publication_id=?",p.id));
    }
    @Test void copiedAvatarAllOrNoneBoundsAndIndependentProvenanceAreEnforced(){
        var p=publication();invalid(()->jdbc.update("update profile.public_profile_projection set public_avatar_id=uuidv7(),projection_generation=2 where public_profile_projection_id=?",p.author));
        invalid(()->jdbc.update("update profile.public_profile_projection set source_avatar_id=uuidv7(),projection_generation=2 where public_profile_projection_id=?",p.author));
        String ref="public-profile-avatar/"+p.id.toString().replace("-","").repeat(2);
        jdbc.update("update profile.public_profile_projection set public_avatar_id=uuidv7(),source_avatar_id=uuidv7(),public_avatar_object_reference=?,avatar_media_type='image/png',avatar_byte_size=100,avatar_width=2,avatar_height=2,projection_generation=2 where public_profile_projection_id=?",ref,p.author);
        invalid(()->jdbc.update("update profile.public_profile_projection set avatar_byte_size=5242881,projection_generation=3 where public_profile_projection_id=?",p.author));
        invalid(()->jdbc.update("update profile.public_profile_projection set avatar_width=4096,avatar_height=4096,projection_generation=3 where public_profile_projection_id=?",p.author));
        invalid(()->jdbc.update("update profile.public_profile_projection set public_avatar_object_reference=?,projection_generation=3 where public_profile_projection_id=?","private-avatar/"+"f".repeat(64),p.author));
        var q=publication();String handle=jdbc.queryForObject("select handle from profile.public_profile_projection where public_profile_projection_id=?",String.class,p.author);
        invalid(()->jdbc.update("update profile.public_profile_projection set handle=?,projection_generation=2 where public_profile_projection_id=?",handle,q.author));
    }
    @Test void discoveryCannotReactivateOldGenerationAndViewsHaveNoViewerLedger(){
        var p=publication();jdbc.update("insert into discovery.publication_projection(publication_id,public_profile_projection_id,publication_generation,active,title,markdown,tags,published_at,updated_at) values(?,?,1,true,'Synthetic','Synthetic',array[]::text[],now(),now())",p.id,p.author);
        jdbc.update("update discovery.publication_projection set active=false,publication_generation=2 where publication_id=?",p.id);
        invalid(()->jdbc.update("update discovery.publication_projection set active=true where publication_id=?",p.id));
        invalid(()->jdbc.update("update discovery.publication_projection set publication_generation=1 where publication_id=?",p.id));
        invalid(()->jdbc.update("insert into discovery.publication_view_aggregate values(?,current_date,-1,now())",p.id));
        assertThat(jdbc.queryForList("select column_name from information_schema.columns where table_schema='discovery' and table_name='publication_view_aggregate' order by ordinal_position",String.class))
            .containsExactly("publication_id","bucket_date","view_count","updated_at");
    }
    @Test void runtimeCanReadAndAppendButCannotDdlOrRewriteAudit()throws Exception {
        var p=publication();jdbc.execute("create role synthetic_public_runtime nologin nosuperuser nocreatedb nocreaterole");
        jdbc.execute("grant usage on schema publishing,profile,discovery to synthetic_public_runtime");
        jdbc.execute("grant select,insert,update,delete on publishing.publication,publishing.publication_snapshot_tag,publishing.publication_public_media,profile.public_profile_projection,discovery.publication_projection,discovery.publication_view_aggregate to synthetic_public_runtime");
        jdbc.execute("grant select,insert on publishing.publication_audit_fact to synthetic_public_runtime");
        try(var connection=ds.getConnection()){
            var runtime=new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection,true));runtime.execute("set role synthetic_public_runtime");
            try{assertThat(runtime.queryForObject("select rolsuper from pg_roles where rolname=current_user",Boolean.class)).isFalse();
                assertThat(runtime.queryForObject("select count(*) from publishing.publication where publication_id=?",Integer.class,p.id)).isEqualTo(1);
                runtime.update("insert into publishing.publication_audit_fact values(uuidv7(),?,?,'create','committed','owner_publish',1,1,now())",p.id,p.owner);
                invalid(()->runtime.execute("create table publishing.forbidden(id int)"));invalid(()->runtime.update("delete from publishing.publication_audit_fact where publication_id=?",p.id));
            }finally{runtime.execute("reset role");}
        }
    }
    private List<String> relations(){return jdbc.queryForList("select schemaname||'.'||tablename from pg_tables where schemaname in ('identity','profile','notes','knowledge','publishing','discovery','moderation') order by 1",String.class);}
    private UUID owner(){UUID id=jdbc.queryForObject("select uuidv7()",UUID.class);String email="migration-"+id+"@example.test";jdbc.update("insert into identity.account(user_id,canonical_email,display_email,email_verified_at,account_state,created_at,updated_at) values(?,?,?,now(),'active',now(),now())",id,email,email);return id;}
    private PublicRow publication(){UUID owner=owner();UUID profile=jdbc.queryForObject("insert into profile.profile(profile_id,user_id,display_name,biography,updated_at) values(uuidv7(),?,'Synthetic','',now()) returning profile_id",UUID.class,owner);
        UUID author=jdbc.queryForObject("insert into profile.public_profile_projection(public_profile_projection_id,profile_id,user_id,handle,display_name,biography,projection_generation,active,activated_at,updated_at) values(uuidv7(),?,?,?,'Synthetic','',1,true,now(),now()) returning public_profile_projection_id",UUID.class,profile,owner,"author_"+owner.toString().replace("-","").substring(0,12));
        UUID publication=jdbc.queryForObject("insert into publishing.publication(publication_id,owner_user_id,source_note_id,source_note_version_id,source_revision,public_profile_projection_id,title,markdown,snapshot_revision,publication_generation,availability,reason_code,published_at,updated_at) values(uuidv7(),?,uuidv7(),uuidv7(),1,?,'Synthetic','Synthetic',1,1,'active','owner_publish',now(),now()) returning publication_id",UUID.class,owner,author);
        return new PublicRow(publication,owner,author);
    }
    private void media(UUID publication,long revision,String type,long bytes){jdbc.update("insert into publishing.publication_public_media(public_media_id,publication_id,snapshot_revision,publication_generation,public_object_reference,media_kind,media_type,display_name,byte_size,width,height,media_order,state) values(uuidv7(),?,?,1,?,'image',?,'synthetic.png',?,2,2,0,'current')",publication,revision,"public-publication-media/"+UUID.randomUUID().toString().replace("-","").repeat(2),type,bytes);}
    private static void invalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable action){assertThatThrownBy(action).isInstanceOf(org.springframework.dao.DataAccessException.class);}
    record PublicRow(UUID id,UUID owner,UUID author){ }
}
