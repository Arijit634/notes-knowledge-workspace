package org.notesknowledge.knowledge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.notesknowledge.LeaseOwner;
import org.notesknowledge.LeasePolicy;
import org.notesknowledge.LeaseToken;
import org.notesknowledge.knowledge.spi.PrivateAiSourceCurrentness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class KnowledgeWorkRepository {
    static final int MAX_ATTEMPTS=5;
    private final ObjectProvider<JdbcClient> clients;
    KnowledgeWorkRepository(ObjectProvider<JdbcClient> clients) { this.clients=clients; }
    KnowledgeWork.Intent enqueue(KnowledgeWork.Kind kind,PrivateAiSourceCurrentness.Expected e) {
        return enqueue(kind,e,"legacy_unassigned");
    }
    KnowledgeWork.Intent enqueue(KnowledgeWork.Kind kind,PrivateAiSourceCurrentness.Expected e,String lineage) {
        if((kind==KnowledgeWork.Kind.ATTACHMENT)!=(e.attachmentId()!=null)) throw new IllegalArgumentException("Source kind mismatch");
        String dedupe=dedupe(kind,e,lineage);
        // DO NOTHING, then a new statement snapshot: concurrent winner may have committed after the INSERT snapshot.
        var insert=clients.getObject().sql("""
                insert into knowledge.knowledge_work_intent(owner_user_id,work_class,source_kind,source_note_id,source_attachment_id,
                    expected_revision,expected_ai_generation,expected_attachment_generation,max_attempts,next_attempt_at,dedupe_key,target_lineage_id)
                values(:owner,:workClass,:sourceKind,:note,:attachment,:revision,:generation,:attachmentGeneration,:attempts,clock_timestamp(),:dedupe,:lineage)
                on conflict(owner_user_id,dedupe_key) where state in ('queued','claimed','retry_wait') do nothing
                returning *
                """);
        bind(insert,kind,e).param("attempts",MAX_ATTEMPTS).param("dedupe",dedupe).param("lineage",lineage);
        for(int attempt=0;attempt<3;attempt++) {
            var created=insert.query((r,i)->intent(r)).optional();
            if(created.isPresent()) return created.get();
            var existing=clients.getObject().sql("select * from knowledge.knowledge_work_intent where "
                +"owner_user_id=:owner and dedupe_key=:dedupe and state in ('queued','claimed','retry_wait')")
                .param("owner",e.owner()).param("dedupe",dedupe).query((r,i)->intent(r)).optional();
            if(existing.isPresent()) return existing.get();
        }
        throw org.notesknowledge.websupport.ApiFailureException.of(org.notesknowledge.websupport.ApiFailureException.Kind.SERVICE_UNAVAILABLE);
    }
    private JdbcClient.StatementSpec bind(JdbcClient.StatementSpec sql,KnowledgeWork.Kind kind,PrivateAiSourceCurrentness.Expected e) {
        return sql.param("owner",e.owner()).param("workClass",kind.workClass).param("sourceKind",kind.sourceKind)
                .param("note",e.noteId()).param("attachment",e.attachmentId(),java.sql.Types.OTHER)
                .param("revision",e.revision()).param("generation",e.aiGeneration())
                .param("attachmentGeneration",e.attachmentGeneration(),java.sql.Types.BIGINT);
    }
    static String dedupe(KnowledgeWork.Kind kind,PrivateAiSourceCurrentness.Expected e) {
        return dedupe(kind,e,"legacy_unassigned");
    }
    static String dedupe(KnowledgeWork.Kind kind,PrivateAiSourceCurrentness.Expected e,String lineage) {
        String identity=e.owner()+"|"+kind.workClass+"|"+e.noteId()+"|"+e.attachmentId()+"|"+e.revision()+"|"+e.aiGeneration()+"|"+e.attachmentGeneration();
        identity+="|text_surrogate|"+lineage;
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    List<KnowledgeWork.Claim> claim(LeaseOwner owner,LeasePolicy policy,int limit,boolean expired) {
        String predicate=expired?"state='claimed' and lease_until<=clock_timestamp()":"state in ('queued','retry_wait') and next_attempt_at<=clock_timestamp()";
        String order=expired?"lease_until":"next_attempt_at";
        String occupied="exists(select 1 from knowledge.knowledge_work_intent sibling where "
            +"sibling.owner_user_id=w.owner_user_id and sibling.source_note_id=w.source_note_id and sibling.work_class in ('private_note_derivation','private_attachment_derivation') "
            +"and sibling.state='claimed'"+(expired?" and sibling.lease_until>clock_timestamp()":"")+")";
        // Provider dispatch is exclusive per Note root, including all its Attachments.
        // Admit only one derivation for that root rather than spending sibling attempts
        // on the dispatch lock. This short transaction lock closes the concurrent-claimer
        // race; it grants no source authority and is never held over provider I/O.
        var roots=clients.getObject().sql("select owner_user_id,source_note_id from knowledge.knowledge_work_intent w where "
            +"work_class in ('private_note_derivation','private_attachment_derivation') and "+predicate
            +" and not "+occupied+" order by "+order+",created_at,knowledge_work_intent_id limit :candidates")
            .param("candidates",policy.maximumBatchSize()).query((r,i)->new ClaimRoot(r.getObject("owner_user_id",UUID.class),r.getObject("source_note_id",UUID.class))).list();
        var claims=new java.util.ArrayList<KnowledgeWork.Claim>();
        for(var root:new java.util.LinkedHashSet<>(roots)) {
            if(claims.size()>=limit)break;
            boolean admitted=clients.getObject().sql("select pg_try_advisory_xact_lock(17404,hashtext(:root))")
                .param("root",root.note().toString()).query(Boolean.class).single();
            if(!admitted)continue;
            // A separate READ COMMITTED statement sees a prior claimer's committed
            // lease after acquiring the root lock. Hash collisions only defer work.
            claims.addAll(claimRoot(owner,policy,root,predicate,order,occupied));
        }
        return List.copyOf(claims);
    }
    private record ClaimRoot(UUID owner,UUID note) {
        @Override public String toString(){return "ClaimRoot[REDACTED]";}
    }
    private List<KnowledgeWork.Claim> claimRoot(LeaseOwner owner,LeasePolicy policy,ClaimRoot root,String predicate,String order,String occupied) {
        // Both successful and abandoned attempts are charged at claim time, exactly once per fresh fencing token.
        // Exhausted abandoned rows become terminal in the same bounded recovery transaction.
        return clients.getObject().sql("""
                with picked as (select knowledge_work_intent_id,
                """+order+" as due,created_at from knowledge.knowledge_work_intent w where "
                +"work_class in ('private_note_derivation','private_attachment_derivation') and owner_user_id=:sourceOwner and source_note_id=:root and "+predicate
                +" and not "+occupied+" order by "+order+",created_at,knowledge_work_intent_id limit 1 for update skip locked), changed as ("
                +"update knowledge.knowledge_work_intent w set "
                +"state=case when w.attempt_count<w.max_attempts then 'claimed' else 'failed' end, "
                +"attempt_count=least(w.attempt_count+1,w.max_attempts),next_attempt_at=null, "
                +"lease_owner=case when w.attempt_count<w.max_attempts then :owner else null end, "
                +"lease_token=case when w.attempt_count<w.max_attempts then uuidv7() else null end, "
                +"lease_until=case when w.attempt_count<w.max_attempts then clock_timestamp()+make_interval(secs=>:seconds) else null end, "
                +"failure_code=case when w.attempt_count<w.max_attempts then null else 'attempts_exhausted' end,updated_at=clock_timestamp() "
                +"from picked where w.knowledge_work_intent_id=picked.knowledge_work_intent_id returning w.*) "
                +"select changed.* from changed join picked using(knowledge_work_intent_id) where changed.state='claimed' "
                +"order by picked.due,picked.created_at,knowledge_work_intent_id")
                .param("sourceOwner",root.owner()).param("root",root.note()).param("owner",owner.alias()).param("seconds",policy.leaseDuration().toSeconds())
                .query((r,i)->new KnowledgeWork.Claim(intent(r),owner,LeaseToken.fromDatabase(r.getObject("lease_token",UUID.class)),r.getTimestamp("lease_until").toInstant())).list();
    }
    boolean transition(KnowledgeWork.Claim claim,String target,KnowledgeWork.Failure failure,boolean retry) {
        // Only current, unexpired, claimed tokens can change state. Clearing the lease fences all later use.
        return leaseStatement(claim,"update knowledge.knowledge_work_intent set "
                +"state=case when :retry and attempt_count>=max_attempts then 'failed' else :target end, "
                +"next_attempt_at=case when :retry and attempt_count<max_attempts then clock_timestamp()+make_interval(secs=>least(300,power(2,attempt_count)::integer)) else null end, "
                +"failure_code=case when :retry and attempt_count>=max_attempts then 'attempts_exhausted' else :failure end, "
                +"lease_owner=null,lease_token=null,lease_until=null,updated_at=clock_timestamp() where ")
                .param("retry",retry).param("target",target).param("failure",failure==null?null:failure.code(),java.sql.Types.VARCHAR).update()==1;
    }
    boolean heartbeat(KnowledgeWork.Claim claim,LeasePolicy policy) {
        return leaseStatement(claim,"update knowledge.knowledge_work_intent set lease_until=clock_timestamp()+make_interval(secs=>:seconds),updated_at=clock_timestamp() where ")
                .param("seconds",policy.leaseDuration().toSeconds()).update()==1;
    }
    private JdbcClient.StatementSpec leaseStatement(KnowledgeWork.Claim c,String prefix) {
        // Captured owner/source/lineage cannot be swapped by constructing another Claim with a stolen token.
        var e=c.intent().expected();
        return clients.getObject().sql(prefix+"knowledge_work_intent_id=:id and state='claimed' and lease_owner=:leaseOwner "
                +"and lease_token=:token and lease_until>clock_timestamp() and owner_user_id=:owner "
                +"and source_note_id=:note and source_attachment_id is not distinct from :attachment "
                +"and expected_revision=:revision and expected_ai_generation=:generation "
                +"and expected_attachment_generation is not distinct from :attachmentGeneration and work_class=:workClass "
                +"and derivation_class=:derivation and target_lineage_id=:lineage")
                .param("id",c.intent().id()).param("leaseOwner",c.owner().alias()).param("token",c.token().value())
                .param("owner",e.owner()).param("note",e.noteId()).param("attachment",e.attachmentId(),java.sql.Types.OTHER)
                .param("revision",e.revision()).param("generation",e.aiGeneration()).param("attachmentGeneration",e.attachmentGeneration(),java.sql.Types.BIGINT)
                .param("workClass",c.intent().kind().workClass).param("derivation",c.intent().derivationClass()).param("lineage",c.intent().targetLineageId());
    }
    boolean currentLease(KnowledgeWork.Claim c) {
        return leaseStatement(c,"select count(*) from knowledge.knowledge_work_intent where ").query(Integer.class).single()==1;
    }
    private KnowledgeWork.Intent intent(ResultSet r) throws SQLException {
        return new KnowledgeWork.Intent(r.getObject("knowledge_work_intent_id",UUID.class),
                "note".equals(r.getString("source_kind"))?KnowledgeWork.Kind.NOTE:KnowledgeWork.Kind.ATTACHMENT,
                new PrivateAiSourceCurrentness.Expected(r.getObject("owner_user_id",UUID.class),r.getObject("source_note_id",UUID.class),
                        r.getObject("source_attachment_id",UUID.class),r.getLong("expected_revision"),r.getLong("expected_ai_generation"),
                        r.getObject("expected_attachment_generation",Long.class)),r.getString("state"),r.getInt("attempt_count"),r.getInt("max_attempts"),
                        r.getString("derivation_class"),r.getString("target_lineage_id"));
    }
}
