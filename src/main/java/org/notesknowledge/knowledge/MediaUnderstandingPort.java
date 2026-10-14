package org.notesknowledge.knowledge;

import java.util.List;

interface MediaUnderstandingPort {
    boolean available();
    List<DerivedSegment> describe(AiProcessingGate.SourceAiPermit permit,byte[] media,MediaUnderstandingRequest request);
    default List<DerivedSegment> describeChild(AiProcessingGate.SourceAiPermit permit,TransformedMediaIdentity child,
            byte[] original,byte[] media,MediaUnderstandingRequest request) {
        throw new DerivationFailure(KnowledgeWork.Failure.PROVIDER_UNAVAILABLE);
    }
}
