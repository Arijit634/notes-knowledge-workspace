package org.notesknowledge;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Synthetic copied public fixtures. Private provenance is deliberately not a retrieval source. */
public final class PublicDiscoveryFixtures {
    private PublicDiscoveryFixtures(){ }
    public record PublicRow(UUID id,UUID owner,UUID author) { }
    static String publicHandle(UUID owner) {
        // UUIDv7's first 12 hex digits are a timestamp shared by burst-created owners.
        return "writer_" + owner.toString().replace("-", "").substring(12);
    }
    public static UUID account(JdbcTemplate jdbc) {
        UUID id=jdbc.queryForObject("select uuidv7()",UUID.class);String email="synthetic-"+id+"@example.test";
        jdbc.update("insert into identity.account(user_id,canonical_email,display_email,email_verified_at,account_state,created_at,updated_at) values(?,?,?,now(),'active',now(),now())",id,email,email);
        return id;
    }
    public static PublicRow publication(JdbcTemplate jdbc,String title,String markdown,String tag,Instant time) {
        UUID owner=account(jdbc);
        UUID profile=jdbc.queryForObject("insert into profile.profile(profile_id,user_id,display_name,biography,updated_at) values(uuidv7(),?,'Synthetic','',now()) returning profile_id",UUID.class,owner);
        UUID author=jdbc.queryForObject("insert into profile.public_profile_projection(public_profile_projection_id,profile_id,user_id,handle,display_name,biography,projection_generation,active,activated_at,updated_at) values(uuidv7(),?,?,?,'Synthetic','',1,true,now(),now()) returning public_profile_projection_id",UUID.class,profile,owner,publicHandle(owner));
        UUID id=jdbc.queryForObject("insert into publishing.publication(publication_id,owner_user_id,source_note_id,source_note_version_id,source_revision,public_profile_projection_id,title,markdown,snapshot_revision,publication_generation,availability,reason_code,published_at,updated_at) values(uuidv7(),?,uuidv7(),uuidv7(),1,?,?,?,1,1,'active','owner_publish',?,now()) returning publication_id",UUID.class,owner,author,title,markdown,Timestamp.from(time));
        jdbc.update("insert into discovery.publication_projection(publication_id,public_profile_projection_id,publication_generation,active,title,markdown,tags,published_at,updated_at) values(?,?,1,true,?,?,array[?]::text[],?,now())",id,author,title,markdown,tag,Timestamp.from(time));
        return new PublicRow(id,owner,author);
    }
    public static void retireAll(JdbcTemplate jdbc) {
        jdbc.update("update publishing.publication set availability='unpublished',publication_generation=publication_generation+1,unpublished_at=now(),updated_at=greatest(updated_at,now()) where availability='active'");
        jdbc.update("update discovery.publication_projection set active=false,publication_generation=publication_generation+1,updated_at=greatest(updated_at,now()) where active");
        jdbc.update("update knowledge.knowledge_work_intent set state='obsolete',next_attempt_at=null,lease_owner=null,lease_token=null,lease_until=null,updated_at=clock_timestamp() where scope_kind='public' and state in ('queued','claimed','retry_wait')");
    }
}
