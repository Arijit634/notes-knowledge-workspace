package org.notesknowledge.knowledge;

import java.time.Instant;
import java.util.Optional;
import org.notesknowledge.knowledge.spi.PrivateDerivationSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Sole issuer. Each gate obtains fresh Account, policy, lease and source locks in one short transaction. */
@Service
class AiProcessingGate {
    private final KnowledgeWorkService work;
    private final AiDerivationProperties configuration;
    private final ProcessingPolicyService policies;
    private final PrivateDerivationSource sources;
    private final ProviderDispatchPolicy dispatchPolicy;
    AiProcessingGate(KnowledgeWorkService work,AiDerivationProperties configuration,ProcessingPolicyService policies,PrivateDerivationSource sources,ProviderDispatchPolicy dispatchPolicy) {
        this.work=work;this.configuration=configuration;this.policies=policies;this.sources=sources;
        this.dispatchPolicy=dispatchPolicy;
    }
    static final class SourceAiPermit {
        private final EmbeddingLineage lineage;
        private final PrivateDerivationSource.Metadata source;
        private final Instant expires;
        private final ProviderDispatchPolicy dispatchPolicy;
        private final AiDerivationProperties configuration;
        private final boolean unpaidGeminiApproved;
        private final org.notesknowledge.DispatchCoordinator.Handle coordination;
        private SourceAiPermit(EmbeddingLineage lineage,PrivateDerivationSource.Metadata source,ProviderDispatchPolicy dispatchPolicy,AiDerivationProperties configuration) {
            this(lineage,source,dispatchPolicy,configuration,null);
        }
        private SourceAiPermit(EmbeddingLineage lineage,PrivateDerivationSource.Metadata source,ProviderDispatchPolicy dispatchPolicy,AiDerivationProperties configuration,
                org.notesknowledge.DispatchCoordinator.Handle coordination) {
            this.lineage=lineage;this.source=source;this.expires=Instant.now().plusSeconds(15);
            this.coordination=coordination;
            this.dispatchPolicy=dispatchPolicy;this.configuration=configuration;
            this.unpaidGeminiApproved=dispatchPolicy.permitsUnpaidGemini(configuration,source.expected());
        }
        EmbeddingLineage lineage(){return lineage;}
        PrivateDerivationSource.Metadata source(){return source;}
        org.notesknowledge.DispatchCoordinator.Handle coordination(){return coordination;}
        void requireDispatch() {
            if(coordination==null)throw new DerivationFailure(KnowledgeWork.Failure.INVALID_SOURCE);
            coordination.requireDispatchScope(source.expected().owner(),source.expected().noteId());
            if(Instant.now().isAfter(expires)||org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                    ||!dispatchPolicy.permits(configuration,source.expected(),source.modality()))
                throw new DerivationFailure(KnowledgeWork.Failure.INVALID_SOURCE);
        }
        void requireGoogleDispatch() {
            requireDispatch();
            if(!unpaidGeminiApproved||!dispatchPolicy.permitsUnpaidGemini(configuration,source.expected()))
                throw new DerivationFailure(KnowledgeWork.Failure.INVALID_SOURCE);
        }
        @Override public String toString(){return "SourceAiPermit[REDACTED]";}
    }
    @Transactional(timeout=3)
    Optional<SourceAiPermit> issueForDispatch(KnowledgeWork.Claim claim,org.notesknowledge.DispatchCoordinator.Handle coordination) {
        coordination.requireDispatchScope(claim.intent().expected().owner(),claim.intent().expected().noteId());
        return issue(claim).map(p->new SourceAiPermit(p.lineage,p.source,dispatchPolicy,configuration,coordination));
    }
    @Transactional(timeout=3)
    Optional<SourceAiPermit> issue(KnowledgeWork.Claim claim) {
        if(!configuration.configured()||claim.intent().targetLineageId().equals("legacy_unassigned"))return Optional.empty();
        var prerequisite=work.revalidate(claim);
        if(prerequisite.isEmpty())return Optional.empty();
        var e=claim.intent().expected();
        var metadata=sources.noteSources(e.owner(),e.noteId()).stream().filter(s->s.expected().equals(e)).findFirst();
        if(metadata.isEmpty())return Optional.empty();
        if(!dispatchPolicy.permits(configuration,e,metadata.get().modality()))return Optional.empty();
        var policy=policies.policyPrerequisite(e.owner());
        if(policy.isEmpty()||!configuration.approvedPolicyFingerprint().equals(policy.get().fingerprint()))return Optional.empty();
        var lineage=EmbeddingLineage.create(configuration,policy.get(),metadata.get().modality());
        if(!lineage.id().equals(claim.intent().targetLineageId()))return Optional.empty();
        return Optional.of(new SourceAiPermit(lineage,metadata.get(),dispatchPolicy,configuration));
    }
}
