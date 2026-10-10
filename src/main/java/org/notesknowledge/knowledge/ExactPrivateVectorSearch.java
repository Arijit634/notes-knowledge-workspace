package org.notesknowledge.knowledge;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.notesknowledge.identity.AccountEligibilityApi;
import org.notesknowledge.identity.IdentitySessionPrincipal;
import org.notesknowledge.knowledge.spi.PrivateAiSourceCurrentness;
import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Exact owner/current ranking only; no ANN or global candidate retrieval. */
@Service
class ExactPrivateVectorSearch {
    record Root(UUID id,PrivateAiSourceCurrentness.Expected expected) { }
    record Candidate(UUID segmentId,PrivateAiSourceCurrentness.Expected expected,int ordinal,DerivedSegment segment,double distance) {
        @Override public String toString(){return "PrivateVectorCandidate[REDACTED]";}
    }
    private final ObjectProvider<JdbcClient> clients;
    private final ObjectProvider<AccountEligibilityApi> accounts;
    private final ProcessingPolicyService policies;
    private final AiDerivationProperties configuration;
    private final PrivateAiSourceCurrentness sources;
    ExactPrivateVectorSearch(ObjectProvider<JdbcClient> clients,ObjectProvider<AccountEligibilityApi> accounts,ProcessingPolicyService policies,
            AiDerivationProperties configuration,PrivateAiSourceCurrentness sources) {
        this.clients=clients;this.accounts=accounts;this.policies=policies;this.configuration=configuration;this.sources=sources;
    }
    @Transactional(timeout=3)
    List<Candidate> search(HttpServletRequest browser,String modality,float[] query,int limit) {
        if(limit<1||limit>100)throw new IllegalArgumentException("Invalid candidate budget");
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth==null||!(auth.getPrincipal() instanceof IdentitySessionPrincipal p))throw ApiFailureException.of(ApiFailureException.Kind.INVALID_CREDENTIALS);
        UUID owner=p.userId();accounts.getObject().requireCurrentOwner(owner,browser);
        return search(owner,modality,query,limit);
    }
    @Transactional(timeout=3)
    List<Candidate> search(UUID owner,String modality,float[] query,int limit) {
        return rank(owner,null,modality,query,limit);
    }
    @Transactional(timeout=3)
    List<Candidate> searchNotes(UUID owner,List<UUID> notes,float[] query,int limit) {
        if(notes.isEmpty())return List.of();
        if(notes.size()>100)throw new DerivationFailure(KnowledgeWork.Failure.BUDGET_EXCEEDED);
        return rank(owner,notes,"note",query,limit);
    }
    private List<Candidate> rank(UUID owner,List<UUID> notes,String modality,float[] query,int limit) {
        if(limit<1||limit>100)throw new IllegalArgumentException("Invalid candidate budget");
        if(!accounts.getObject().isEligible(owner))throw ApiFailureException.of(ApiFailureException.Kind.INVALID_CREDENTIALS);
        var policy=policies.policyPrerequisite(owner).orElseThrow(()->new DerivationFailure(KnowledgeWork.Failure.POLICY_BLOCKED));
        if(!configuration.configured()||!configuration.approvedPolicyFingerprint().equals(policy.fingerprint()))throw new DerivationFailure(KnowledgeWork.Failure.PROVIDER_UNAVAILABLE);
        var lineage=EmbeddingLineage.create(configuration,policy,modality);float[] preparedQuery=lineage.prepare(query);
        String rootSql="""
            select derived_representation_id,source_note_id,source_attachment_id,source_revision,processing_generation,attachment_generation
            from knowledge.private_derived_representation where owner_user_id=:owner and state='ready' and lineage_id=:lineage
                and processing_policy_id=:policy and embedding_dimension=:dimension and modality=:modality
            """+(notes==null?"":" and source_note_id in (:notes)");
        String operator=switch(lineage.operator()){case "cosine"->"<=>";case "l2"->"<->";case "inner_product"->"<#>";default->throw new IllegalArgumentException("Invalid operator");};
        var best=new java.util.ArrayList<Candidate>();
        var order=java.util.Comparator.comparingDouble(Candidate::distance).thenComparing(c->c.segmentId().toString());
        UUID after=new UUID(0,0);
        // A finite root high-water prevents concurrent creation from extending this ranked request forever.
        UUID boundary=rootStatement(rootSql+" order by derived_representation_id desc limit 1",owner,notes,modality,lineage)
            .query((r,i)->r.getObject(1,UUID.class)).optional().orElse(null);
        if(boundary==null)return List.of();
        while(true) {
            var roots=rootStatement(rootSql+" and derived_representation_id>:after and derived_representation_id<=:boundary order by derived_representation_id limit :batch",owner,notes,modality,lineage)
                .param("after",after).param("boundary",boundary)
                .param("batch",PrivateAiSourceCurrentness.MAX_BATCH)
                .query((r,i)->new Root(r.getObject(1,UUID.class),new PrivateAiSourceCurrentness.Expected(owner,r.getObject(2,UUID.class),r.getObject(3,UUID.class),r.getLong(4),r.getLong(5),r.getObject(6,Long.class)))).list();
            if(roots.isEmpty())break;
            var current=new java.util.HashSet<>(sources.matching(roots.stream().map(Root::expected).toList()));
            var eligible=roots.stream().filter(r->current.contains(r.expected())).toList();
            if(!eligible.isEmpty()) {
                best.addAll(score(owner,lineage,preparedQuery,limit,operator,eligible));
                best.sort(order);
                if(best.size()>limit)best.subList(limit,best.size()).clear();
            }
            after=roots.getLast().id();
            if(roots.size()<PrivateAiSourceCurrentness.MAX_BATCH)break;
        }
        // Defensive revalidation never grants authority to a stale retained candidate.
        var current=new java.util.HashSet<PrivateAiSourceCurrentness.Expected>();
        for(int i=0;i<best.size();i+=64)current.addAll(sources.matching(best.subList(i,Math.min(i+64,best.size())).stream().map(Candidate::expected).distinct().toList()));
        return best.stream().filter(c->current.contains(c.expected())).toList();
    }
    private JdbcClient.StatementSpec rootStatement(String sql,UUID owner,List<UUID> notes,String modality,EmbeddingLineage lineage) {
        var statement=clients.getObject().sql(sql).param("owner",owner).param("lineage",lineage.id()).param("policy",lineage.policyId())
            .param("dimension",lineage.dimension()).param("modality",modality);
        return notes==null?statement:statement.param("notes",notes);
    }
    private List<Candidate> score(UUID owner,EmbeddingLineage lineage,float[] preparedQuery,int limit,String operator,List<Root> eligible) {
        // Materialize the authorized parent batch once: underestimated owner/lineage
        // cardinality must not cause a representation-index rescan per segment.
        // The second barrier keeps distance after all private scope/current gates.
        var result=clients.getObject().sql(candidateSql(operator))
            .param("owner",owner).param("lineage",lineage.id()).param("dimension",lineage.dimension()).param("policy",lineage.policyId())
            .param("parents",eligible.stream().map(Root::id).toList()).param("vector",PrivateRepresentationRepository.vectorLiteral(preparedQuery)).param("limit",limit)
            .query((r,i)->{UUID parent=r.getObject("parent_id",UUID.class);return new Candidate(r.getObject("derived_segment_id",UUID.class),eligible.stream().filter(root->root.id().equals(parent)).findFirst().orElseThrow().expected(),
                r.getInt("ordinal"),new DerivedSegment(r.getString("surrogate_text"),r.getString("segment_kind"),r.getString("heading_ancestry"),
                    r.getObject("source_start",Integer.class),r.getObject("source_end",Integer.class),r.getObject("page_number",Integer.class),
                    r.getObject("time_start",Double.class),r.getObject("time_end",Double.class),r.getObject("region_x",Double.class),
                    r.getObject("region_y",Double.class),r.getObject("region_width",Double.class),r.getObject("region_height",Double.class)),r.getDouble("distance"));}).list();
        return result;
    }
    static String candidateSql(String operator) {
        if(!java.util.Set.of("<=>","<->","<#>").contains(operator))throw new IllegalArgumentException("Invalid operator");
        return """
            with parents as materialized (
                select derived_representation_id from knowledge.private_derived_representation
                where owner_user_id=:owner and state='ready' and lineage_id=:lineage
                    and processing_policy_id=:policy and embedding_dimension=:dimension
                    and derived_representation_id in (:parents)),
            eligible as materialized (
                select s.* from knowledge.private_derived_segment s
                join parents p on p.derived_representation_id=s.parent_id
                where s.owner_user_id=:owner and s.lineage_id=:lineage and s.embedding_dimension=:dimension
                    and s.parent_id in (:parents))
            select derived_segment_id,parent_id,owner_user_id,ordinal,surrogate_text,segment_kind,heading_ancestry,
                source_start,source_end,page_number,time_start,time_end,region_x,region_y,region_width,region_height,embedding
            """+operator+" :vector::vector distance from eligible order by distance,derived_segment_id limit :limit";
    }
}
