package org.notesknowledge.publishing;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** All anonymous material originates in these copied Publishing-owned relations. */
@Repository
class PublicationRepository {
    private final ObjectProvider<JdbcClient> clients;
    PublicationRepository(ObjectProvider<JdbcClient> clients){this.clients=clients;}
    private JdbcClient jdbc(){return clients.getObject();}
    private static final String CORE="""
        select p.*,array(select display_label from publishing.publication_snapshot_tag t where t.publication_id=p.publication_id
            and t.snapshot_revision=p.snapshot_revision order by normalized_label) as tags from publishing.publication p
        """;
    Optional<PublicationRecord> owner(UUID owner,UUID id,boolean lock) {
        if(lock&&jdbc().sql("select publication_id from publishing.publication where owner_user_id=:owner and publication_id=:id for update")
            .param("owner",owner).param("id",id).query(UUID.class).optional().isEmpty())return Optional.empty();
        return jdbc().sql(CORE+" where owner_user_id=:owner and publication_id=:id").param("owner",owner).param("id",id).query(PublicationRepository::map).optional();
    }
    Optional<PublicationRecord> active(UUID id){return jdbc().sql(CORE+" where publication_id=:id and availability='active'")
        .param("id",id).query(PublicationRepository::map).optional();}
    Optional<PublicationRecord> source(UUID owner,UUID note){return jdbc().sql(CORE+" where owner_user_id=:owner and source_note_id=:note")
        .param("owner",owner).param("note",note).query(PublicationRepository::map).optional();}
    List<UUID> activeOwner(UUID owner){return jdbc().sql("select publication_id from publishing.publication where owner_user_id=:owner and availability='active' order by publication_id limit 100")
        .param("owner",owner).query(UUID.class).list();}
    List<PublicationRecord.OwnerSummary> page(UUID owner,Instant before,UUID beforeId,int count){
        var q=jdbc().sql("""
            select p.publication_id,p.title,p.availability,p.published_at,p.updated_at,
                array(select display_label from publishing.publication_snapshot_tag t where t.publication_id=p.publication_id
                    and t.snapshot_revision=p.snapshot_revision order by normalized_label) as tags
            from publishing.publication p
            """+" where owner_user_id=:owner"+(before==null?"":" and (updated_at,publication_id)<(:before,:beforeId)")
            +" order by updated_at desc,publication_id desc limit :count").param("owner",owner).param("count",count);
        if(before!=null)q=q.param("before",Timestamp.from(before)).param("beforeId",beforeId);
        return q.query((r,i)->new PublicationRecord.OwnerSummary(r.getObject("publication_id",UUID.class),r.getString("title"),
            Arrays.asList((String[])r.getArray("tags").getArray()),r.getString("availability"),r.getTimestamp("published_at").toInstant(),r.getTimestamp("updated_at").toInstant())).list();
    }
    List<PublicationRecord.Media> media(UUID publication){return jdbc().sql("select * from publishing.publication_public_media where publication_id=:id and state='current' order by media_order")
        .param("id",publication).query(PublicationRepository::media).list();}
    Optional<PublicationRecord.Media> media(UUID publication,UUID media){return jdbc().sql("""
        select m.* from publishing.publication_public_media m join publishing.publication p on p.publication_id=m.publication_id
            and p.snapshot_revision=m.snapshot_revision and p.publication_generation=m.publication_generation
        where m.publication_id=:id and m.public_media_id=:media and m.state='current' and p.availability='active'
        """).param("id",publication).param("media",media).query(PublicationRepository::media).optional();}
    void create(UUID id,PublicationTransactions.Prepared p,UUID checkpoint,Instant now) {
        jdbc().sql("""
            insert into publishing.publication(publication_id,owner_user_id,source_note_id,source_note_version_id,source_revision,
                public_profile_projection_id,title,markdown,snapshot_revision,publication_generation,availability,reason_code,published_at,updated_at)
            values(:id,:owner,:note,:checkpoint,:revision,:author,:title,:markdown,1,1,'active','owner_publish',:now,:now)
            """).param("id",id).param("owner",p.owner()).param("note",p.source().noteId()).param("checkpoint",checkpoint)
            .param("revision",p.source().revision()).param("author",p.author().projectionId()).param("title",p.source().title())
            .param("markdown",p.source().markdown()).param("now",Timestamp.from(now)).update();
    }
    void replace(PublicationRecord old,PublicationTransactions.Prepared p,UUID checkpoint,String reason,Instant now) {
        clearChildren(old.id());
        jdbc().sql("""
            update publishing.publication set source_note_version_id=:checkpoint,source_revision=:revision,public_profile_projection_id=:author,
                title=:title,markdown=:markdown,snapshot_revision=snapshot_revision+1,publication_generation=publication_generation+1,
                availability='active',reason_code=:reason,updated_at=greatest(updated_at,:now),unpublished_at=null,removed_at=null where publication_id=:id
            """).param("id",old.id()).param("checkpoint",checkpoint).param("revision",p.source().revision()).param("author",p.author().projectionId())
            .param("title",p.source().title()).param("markdown",p.source().markdown()).param("reason",reason).param("now",Timestamp.from(now)).update();
    }
    void clearChildren(UUID id) {
        jdbc().sql("delete from publishing.publication_snapshot_tag where publication_id=:id").param("id",id).update();
        jdbc().sql("delete from publishing.publication_public_media where publication_id=:id").param("id",id).update();
    }
    void deny(PublicationRecord p,String reason,Instant now) {
        jdbc().sql("""
            update publishing.publication set availability='unpublished',publication_generation=publication_generation+1,
                reason_code=:reason,updated_at=greatest(updated_at,:now),unpublished_at=greatest(updated_at,:now) where publication_id=:id
            """).param("id",p.id()).param("reason",reason).param("now",Timestamp.from(now)).update();
    }
    void children(UUID id,long snapshot,long generation,List<String> tags,List<PublicationRecord.Media> media,Instant now) {
        for(String tag:tags)jdbc().sql("insert into publishing.publication_snapshot_tag(publication_id,snapshot_revision,normalized_label,display_label) values(:id,:revision,:normalized,:tag)")
            .param("id",id).param("revision",snapshot).param("normalized",tag.toLowerCase(java.util.Locale.ROOT)).param("tag",tag).update();
        for(var m:media)jdbc().sql("""
            insert into publishing.publication_public_media(public_media_id,publication_id,snapshot_revision,publication_generation,source_attachment_id,
                public_object_reference,media_kind,media_type,display_name,byte_size,width,height,duration_seconds,page_count,media_order,state)
            values(:media,:id,:revision,:generation,:source,:reference,:kind,:type,:name,:size,:width,:height,:duration,:pages,:order,'current')
            """).param("media",m.id()).param("id",id).param("revision",snapshot).param("generation",generation).param("source",m.source())
            .param("reference",m.reference()).param("kind",m.kind()).param("type",m.type()).param("name",m.name()).param("size",m.size())
            .param("width",m.width()).param("height",m.height()).param("duration",m.duration()).param("pages",m.pages()).param("order",m.order()).update();
    }
    void audit(UUID id,PublicationRecord p,String action,String reason,Instant now){jdbc().sql("""
        insert into publishing.publication_audit_fact(audit_fact_id,publication_id,actor_user_id,action_code,outcome_code,reason_code,snapshot_revision,publication_generation,occurred_at)
        values(:id,:publication,:actor,:action,'committed',:reason,:revision,:generation,:now)
        """).param("id",id).param("publication",p.id()).param("actor",p.owner()).param("action",action).param("reason",reason)
        .param("revision",p.snapshot()).param("generation",p.generation()).param("now",Timestamp.from(now)).update();}
    boolean referenced(String ref){return jdbc().sql("select exists(select 1 from publishing.publication_public_media m join publishing.publication p on p.publication_id=m.publication_id where public_object_reference=:ref and m.state='current' and p.availability='active' and p.snapshot_revision=m.snapshot_revision and p.publication_generation=m.publication_generation)")
        .param("ref",ref).query(Boolean.class).single();}
    private static PublicationRecord map(ResultSet r,int i)throws SQLException{return new PublicationRecord(r.getObject("publication_id",UUID.class),
        r.getObject("owner_user_id",UUID.class),r.getObject("source_note_id",UUID.class),r.getObject("source_note_version_id",UUID.class),
        r.getLong("source_revision"),r.getObject("public_profile_projection_id",UUID.class),r.getString("title"),r.getString("markdown"),
        r.getLong("snapshot_revision"),r.getLong("publication_generation"),r.getString("availability"),r.getTimestamp("published_at").toInstant(),
        r.getTimestamp("updated_at").toInstant(),Arrays.asList((String[])r.getArray("tags").getArray()));}
    private static PublicationRecord.Media media(ResultSet r,int i)throws SQLException{return new PublicationRecord.Media(r.getObject("public_media_id",UUID.class),
        r.getObject("publication_id",UUID.class),r.getLong("snapshot_revision"),r.getLong("publication_generation"),r.getObject("source_attachment_id",UUID.class),
        r.getString("public_object_reference"),r.getString("media_kind"),r.getString("media_type"),r.getString("display_name"),r.getLong("byte_size"),
        r.getObject("width",Integer.class),r.getObject("height",Integer.class),duration(r),r.getObject("page_count",Integer.class),r.getInt("media_order"));}
    private static Double duration(ResultSet row)throws SQLException {
        var value=row.getBigDecimal("duration_seconds");return value==null?null:value.doubleValue();
    }
}
