package org.notesknowledge.knowledge;

import java.util.List;
import org.notesknowledge.knowledge.spi.PrivateDerivationSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class DerivationTransactions {
    private final AiProcessingGate gate;
    private final PrivateDerivationSource sources;
    private final PrivateRepresentationRepository representations;
    private final KnowledgeWorkRepository work;
    DerivationTransactions(AiProcessingGate gate,PrivateDerivationSource sources,PrivateRepresentationRepository representations,KnowledgeWorkRepository work) {
        this.gate=gate;this.sources=sources;this.representations=representations;this.work=work;
    }
    @Transactional(timeout=3)
    PrivateDerivationSource.Material acquire(KnowledgeWork.Claim claim) {
        if(gate.issue(claim).isEmpty())throw new DerivationFailure(KnowledgeWork.Failure.INVALID_SOURCE);
        return sources.acquire(claim.intent().expected());
    }
    @Transactional(timeout=3)
    boolean activate(KnowledgeWork.Claim claim,EmbeddingLineage expected,List<DerivedSegment> segments,List<float[]> vectors,
            List<org.notesknowledge.DispatchCoordinator.Handle> dispatches) {
        // Independent veto: unchanged source and a current lease cannot rehabilitate lost coordination.
        if(dispatches.isEmpty()||dispatches.stream().anyMatch(d->!d.safelyReleased()))return false;
        var permit=gate.issue(claim);
        if(permit.isEmpty()||!permit.get().lineage().equals(expected))return false;
        representations.activate(claim.intent().expected(),expected,segments,vectors);
        // A reclaim can occur between the initial lease read and this conditional write.
        // Throwing rolls back the entire root/segment switch, not only work completion.
        if(!work.transition(claim,"completed",null,false))throw new DerivationFailure(KnowledgeWork.Failure.INVALID_SOURCE);
        return true;
    }
}
