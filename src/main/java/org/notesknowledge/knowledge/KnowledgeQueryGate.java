package org.notesknowledge.knowledge;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.notesknowledge.DispatchCoordinator;
import org.notesknowledge.knowledge.spi.PrivateQuerySource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class KnowledgeQueryGate {
    private final ProcessingPolicyService policies;
    private final AiDerivationProperties configuration;
    private final ProviderDispatchPolicy dispatch;
    private final ProviderDispatchProperties dispatchProperties;
    private final PrivateQuerySource sources;
    KnowledgeQueryGate(ProcessingPolicyService policies,AiDerivationProperties configuration,ProviderDispatchPolicy dispatch,
        ProviderDispatchProperties dispatchProperties,PrivateQuerySource sources){this.policies=policies;this.configuration=configuration;this.dispatch=dispatch;this.dispatchProperties=dispatchProperties;this.sources=sources;}
    final class QueryPermit {
        private final UUID owner;
        private final ProcessingPolicyService.AcknowledgedProcessingPolicy policy;
        private final Instant expires=Instant.now().plusSeconds(15);
        private QueryPermit(UUID owner,ProcessingPolicyService.AcknowledgedProcessingPolicy policy){this.owner=owner;this.policy=policy;}
        EmbeddingLineage lineage(String modality){return EmbeddingLineage.create(configuration,policy,modality);}
        void requireDispatch(){
            if(Instant.now().isAfter(expires)||org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()||!configuration.configured())throw new DerivationFailure(KnowledgeWork.Failure.PROVIDER_UNAVAILABLE);
        }
        void requireGoogle(){requireDispatch();if(!"gemini".equals(configuration.provider())||!"unpaid".equals(configuration.tier())||!"global".equals(configuration.region())||!"unpaid-synthetic-demo".equals(dispatchProperties.dispatchPolicy()))throw new DerivationFailure(KnowledgeWork.Failure.POLICY_BLOCKED);}
        @Override public String toString(){return "QueryOnlyPermit[REDACTED]";}
    }
    final class EvidencePermit {
        private final QueryPermit query;
        private final List<PrivateQuerySource.Source> evidence;
        private final DispatchCoordinator.Handle handle;
        private final boolean googleApproved;
        private EvidencePermit(QueryPermit query,List<PrivateQuerySource.Source> evidence,DispatchCoordinator.Handle handle){this.query=query;this.evidence=List.copyOf(evidence);this.handle=handle;
            googleApproved=evidence.stream().allMatch(e->dispatch.permitsUnpaidGemini(configuration,e.expected()));}
        void requireDispatch(){query.requireDispatch();handle.requireDispatchScopes(query.owner,evidence.stream().map(e->e.expected().noteId()).distinct().toList());
            if(evidence.stream().anyMatch(e->!dispatch.permits(configuration,e.expected(),e.modality())))throw new DerivationFailure(KnowledgeWork.Failure.POLICY_BLOCKED);}
        void requireGoogle(){query.requireGoogle();requireDispatch();if(!googleApproved||evidence.stream().anyMatch(e->!dispatch.permitsUnpaidGemini(configuration,e.expected())))throw new DerivationFailure(KnowledgeWork.Failure.POLICY_BLOCKED);}
        @Override public String toString(){return "QueryEvidencePermit[REDACTED]";}
    }
    @Transactional(timeout=3)
    QueryPermit query(UUID owner){var policy=policies.policyPrerequisite(owner).orElseThrow(()->new DerivationFailure(KnowledgeWork.Failure.POLICY_BLOCKED));
        if(!configuration.configured()||!configuration.approvedPolicyFingerprint().equals(policy.fingerprint())||!("synthetic".equals(configuration.provider())&&"synthetic".equals(configuration.tier())||"gemini".equals(configuration.provider())&&"unpaid".equals(configuration.tier())&&"global".equals(configuration.region())&&"unpaid-synthetic-demo".equals(dispatchProperties.dispatchPolicy())))throw new DerivationFailure(KnowledgeWork.Failure.PROVIDER_UNAVAILABLE);
        return new QueryPermit(owner,policy);
    }
    @Transactional(timeout=3)
    EvidencePermit evidence(UUID owner,List<PrivateQuerySource.Source> evidence,DispatchCoordinator.Handle handle){
        if(evidence.isEmpty()||evidence.size()>12)throw new DerivationFailure(KnowledgeWork.Failure.BUDGET_EXCEEDED);
        handle.requireDispatchScopes(owner,evidence.stream().map(e->e.expected().noteId()).distinct().toList());
        var permit=query(owner);
        if(evidence.stream().anyMatch(e->!owner.equals(e.expected().owner())||!sources.matches(e,true)||!dispatch.permits(configuration,e.expected(),e.modality())))throw new DerivationFailure(KnowledgeWork.Failure.INVALID_SOURCE);
        return new EvidencePermit(permit,evidence,handle);
    }
}
