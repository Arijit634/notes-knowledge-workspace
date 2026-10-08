package org.notesknowledge.knowledge;

import java.util.List;
import java.util.UUID;
import org.notesknowledge.LeaseOwner;
import org.notesknowledge.LeaseToken;
import org.notesknowledge.knowledge.spi.PublicKnowledgeSource;
import org.notesknowledge.websupport.ScopedCursorPage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class PublicKnowledgeRepository {
    // Deterministic public Markdown surrogate, NOT an embedding space or AI completion.
    static final String LINEAGE=ScopedCursorPage.digest("public-markdown-surrogate-v1|600|900|80|public_text");
    record Claim(UUID id,PublicKnowledgeSource.Expected expected,LeaseOwner owner,LeaseToken token) {
        @Override public String toString(){return "PublicWorkClaim[REDACTED]";}
    }
    private final ObjectProvider<JdbcClient> clients;
    PublicKnowledgeRepository(ObjectProvider<JdbcClient> clients){this.clients=clients;}
    private JdbcClient jdbc(){return clients.getObject();}
    void invalidate(UUID id) {
        jdbc().sql("update knowledge.public_derived_representation set state='obsolete',updated_at=clock_timestamp() where publication_id=:id and state='current'").param("id",id).update();
        jdbc().sql("update knowledge.knowledge_work_intent set state='obsolete',next_attempt_at=null,lease_owner=null,lease_token=null,lease_until=null,updated_at=clock_timestamp() where scope_kind='public' and source_publication_id=:id and state in ('queued','claimed','retry_wait')").param("id",id).update();
    }
    void enqueue(PublicKnowledgeSource.Expected e) {
        jdbc().sql("""
            insert into knowledge.knowledge_work_intent(scope_kind,owner_user_id,work_class,source_kind,source_publication_id,
                expected_publication_generation,expected_snapshot_revision,derivation_class,target_lineage_id,dedupe_key,max_attempts,next_attempt_at)
            values('public',null,'public_publication_derivation','publication',:id,:generation,:snapshot,'text_surrogate',:lineage,:dedupe,5,clock_timestamp())
            on conflict(source_publication_id,dedupe_key) where scope_kind='public' and state in ('queued','claimed','retry_wait') do nothing
            """).param("id",e.publication()).param("generation",e.generation()).param("snapshot",e.snapshot()).param("lineage",LINEAGE)
            .param("dedupe",ScopedCursorPage.digest("public|"+e.publication()+"|"+e.generation()+"|"+e.snapshot()+"|"+LINEAGE)).update();
    }
    boolean readyOrTerminalFailure(PublicKnowledgeSource.Expected e) {
        return jdbc().sql("""
            select exists(select 1 from knowledge.public_derived_representation where publication_id=:id and publication_generation=:generation
                and snapshot_revision=:snapshot and lineage_id=:lineage and state='current')
            or exists(select 1 from knowledge.knowledge_work_intent where source_publication_id=:id and expected_publication_generation=:generation
                and expected_snapshot_revision=:snapshot and target_lineage_id=:lineage and scope_kind='public' and state='failed')
            """).param("id",e.publication()).param("generation",e.generation()).param("snapshot",e.snapshot()).param("lineage",LINEAGE).query(Boolean.class).single();
    }
    List<Claim> claim(LeaseOwner owner,int limit,boolean reclaim) {
        if(limit<1||limit>10)throw new IllegalArgumentException("Public claim batch exceeds bound");
        String due=reclaim?"lease_until":"next_attempt_at";
        String predicate=reclaim?"state='claimed' and lease_until<=clock_timestamp()":"state in ('queued','retry_wait') and next_attempt_at<=clock_timestamp()";
        return jdbc().sql("with picked as (select knowledge_work_intent_id from knowledge.knowledge_work_intent where scope_kind='public' and "+predicate
            +" order by "+due+",created_at,knowledge_work_intent_id limit :limit for update skip locked), changed as (update knowledge.knowledge_work_intent w set "
            +"state=case when attempt_count<max_attempts then 'claimed' else 'failed' end,attempt_count=least(attempt_count+1,max_attempts),next_attempt_at=null,"
            +"lease_owner=case when attempt_count<max_attempts then :owner else null end,lease_token=case when attempt_count<max_attempts then uuidv7() else null end,"
            +"lease_until=case when attempt_count<max_attempts then clock_timestamp()+interval '60 seconds' else null end,"
            +"failure_code=case when attempt_count<max_attempts then null else 'attempts_exhausted' end,updated_at=clock_timestamp() "
            +"from picked where w.knowledge_work_intent_id=picked.knowledge_work_intent_id returning w.*) select * from changed where state='claimed' order by created_at,knowledge_work_intent_id")
            .param("limit",limit).param("owner",owner.alias()).query((r,i)->new Claim(r.getObject("knowledge_work_intent_id",UUID.class),
                new PublicKnowledgeSource.Expected(r.getObject("source_publication_id",UUID.class),r.getLong("expected_publication_generation"),r.getLong("expected_snapshot_revision")),
                owner,LeaseToken.fromDatabase(r.getObject("lease_token",UUID.class)))).list();
    }
    private JdbcClient.StatementSpec lease(Claim c,String prefix) {
        return jdbc().sql(prefix+"knowledge_work_intent_id=:id and scope_kind='public' and work_class='public_publication_derivation' and state='claimed' "
            +"and lease_owner=:owner and lease_token=:token and lease_until>clock_timestamp() and source_publication_id=:publication "
            +"and expected_publication_generation=:generation and expected_snapshot_revision=:snapshot and target_lineage_id=:lineage")
            .param("id",c.id()).param("owner",c.owner().alias()).param("token",c.token().value()).param("publication",c.expected().publication())
            .param("generation",c.expected().generation()).param("snapshot",c.expected().snapshot()).param("lineage",LINEAGE);
    }
    boolean current(Claim c){return lease(c,"select knowledge_work_intent_id from knowledge.knowledge_work_intent where ").query(UUID.class).optional().isPresent();}
    boolean transition(Claim c,String state,boolean retry) {
        return lease(c,"update knowledge.knowledge_work_intent set state=case when :retry and attempt_count>=max_attempts then 'failed' else :state end, "
            +"next_attempt_at=case when :retry and attempt_count<max_attempts then clock_timestamp()+make_interval(secs=>least(300,power(2,attempt_count)::integer)) else null end,"
            +"failure_code=case when :retry and attempt_count>=max_attempts then 'attempts_exhausted' when :retry then 'transient_dependency' else null end,"
            +"lease_owner=null,lease_token=null,lease_until=null,updated_at=clock_timestamp() where ")
            .param("retry",retry).param("state",state).update()==1;
    }
    void activate(Claim c,List<DerivedSegment> segments) {
        // Work is locked only AFTER Publishing's source lock, the same order as generation invalidation.
        var lease=lease(c,"select knowledge_work_intent_id from knowledge.knowledge_work_intent where ");
        if(lease.query(UUID.class).optional().isEmpty())throw org.notesknowledge.websupport.ApiFailureException.of(org.notesknowledge.websupport.ApiFailureException.Kind.SERVICE_UNAVAILABLE);
        UUID root=jdbc().sql("""
            insert into knowledge.public_derived_representation(publication_id,snapshot_revision,publication_generation,derivation_class,lineage_id,state)
            values(:id,:snapshot,:generation,'text_surrogate',:lineage,'current')
            on conflict(publication_id,derivation_class,lineage_id) where state='current' do nothing returning derived_representation_id
            """).param("id",c.expected().publication()).param("snapshot",c.expected().snapshot()).param("generation",c.expected().generation()).param("lineage",LINEAGE)
            .query(UUID.class).optional().orElse(null);
        if(root==null&&!jdbc().sql("select exists(select 1 from knowledge.public_derived_representation where publication_id=:id and publication_generation=:generation and snapshot_revision=:snapshot and lineage_id=:lineage and state='current')")
            .param("id",c.expected().publication()).param("generation",c.expected().generation()).param("snapshot",c.expected().snapshot()).param("lineage",LINEAGE).query(Boolean.class).single())
            throw org.notesknowledge.websupport.ApiFailureException.of(org.notesknowledge.websupport.ApiFailureException.Kind.SERVICE_UNAVAILABLE);
        if(root!=null)for(int i=0;i<segments.size();i++) {
            var s=segments.get(i);
            jdbc().sql("""
                insert into knowledge.public_derived_segment(derived_representation_id,publication_id,snapshot_revision,publication_generation,lineage_id,
                    segment_order,location_kind,heading,source_start,source_end,text_content)
                values(:root,:id,:snapshot,:generation,:lineage,:ordinal,'public_text',:heading,:start,:end,:text)
                """).param("root",root).param("id",c.expected().publication()).param("snapshot",c.expected().snapshot()).param("generation",c.expected().generation())
                .param("lineage",LINEAGE).param("ordinal",i).param("heading",s.heading()).param("start",s.start()).param("end",s.end()).param("text",s.text()).update();
        }
        // A lost lease rolls back ALL representation writes, not only the final work update.
        if(!transition(c,"completed",false))throw org.notesknowledge.websupport.ApiFailureException.of(org.notesknowledge.websupport.ApiFailureException.Kind.SERVICE_UNAVAILABLE);
    }
}
