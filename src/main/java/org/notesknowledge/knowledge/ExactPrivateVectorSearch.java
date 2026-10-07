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
        var roots=clients.getObject().sql("""
            select derived_representation_id,source_note_id,source_attachment_id,source_revision,processing_generation,attachment_generation
            from knowledge.private_derived_representation where owner_user_id=:owner and state='ready' and lineage_id=:lineage
                and processing_policy_id=:policy and embedding_dimension=:dimension and modality=:modality
                and (:scoped=false or source_note_id in (:notes))
            order by derived_representation_id limit 1001
            """).param("owner",owner).param("lineage",lineage.id()).param("policy",policy.policyId()).param("dimension",lineage.dimension()).param("modality",modality)
            .param("scoped",notes!=null).param("notes",notes==null?List.of(new UUID(0,0)):notes).query((r,i)->new Root(r.getObject(1,UUID.class),new PrivateAiSourceCurrentness.Expected(owner,r.getObject(2,UUID.class),r.getObject(3,UUID.class),
                r.getLong(4),r.getLong(5),r.getObject(6,Long.class)))).list();
        if(roots.size()>1000)throw new DerivationFailure(KnowledgeWork.Failure.BUDGET_EXCEEDED);
        var eligible=roots.stream().filter(r->sources.matches(r.expected())).toList();
        if(eligible.isEmpty())return List.of();
        String operator=switch(lineage.operator()){case "cosine"->"<=>";case "l2"->"<->";case "inner_product"->"<#>";default->throw new IllegalArgumentException("Invalid operator");};
        // MATERIALIZED prevents optimizer flattening: distance is computed only
        // after private scope, current gates and the exact source contract resolve.
        var result=clients.getObject().sql(candidateSql(operator))
            .param("owner",owner).param("lineage",lineage.id()).param("dimension",lineage.dimension()).param("policy",policy.policyId())
            .param("parents",eligible.stream().map(Root::id).toList()).param("vector",PrivateRepresentationRepository.vectorLiteral(preparedQuery)).param("limit",limit)
            .query((r,i)->{UUID parent=r.getObject("parent_id",UUID.class);return new Candidate(r.getObject("derived_segment_id",UUID.class),eligible.stream().filter(root->root.id().equals(parent)).findFirst().orElseThrow().expected(),
                r.getInt("ordinal"),new DerivedSegment(r.getString("surrogate_text"),r.getString("segment_kind"),r.getString("heading_ancestry"),
                    r.getObject("source_start",Integer.class),r.getObject("source_end",Integer.class),r.getObject("page_number",Integer.class),
                    r.getObject("time_start",Double.class),r.getObject("time_end",Double.class),r.getObject("region_x",Double.class),
                    r.getObject("region_y",Double.class),r.getObject("region_width",Double.class),r.getObject("region_height",Double.class)),r.getDouble("distance"));}).list();
        return result.stream().filter(c->sources.matches(c.expected())).toList();
    }
    static String candidateSql(String operator) {
        if(!java.util.Set.of("<=>","<->","<#>").contains(operator))throw new IllegalArgumentException("Invalid operator");
        return """
            with eligible as materialized (
                select s.* from knowledge.private_derived_segment s
                join knowledge.private_derived_representation r on r.derived_representation_id=s.parent_id and r.owner_user_id=s.owner_user_id
                where s.owner_user_id=:owner and s.lineage_id=:lineage and s.embedding_dimension=:dimension
                    and r.owner_user_id=:owner and r.state='ready' and r.lineage_id=:lineage and r.processing_policy_id=:policy
                    and r.derived_representation_id in (:parents))
            select *,embedding
            """+operator+" :vector::vector distance from eligible order by distance,derived_segment_id limit :limit";
    }
}
