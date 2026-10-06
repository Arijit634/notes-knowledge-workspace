package org.notesknowledge.knowledge;

import java.util.List;

interface MediaUnderstandingPort {
    boolean available();
    List<DerivedSegment> describe(AiProcessingGate.SourceAiPermit permit,byte[] media,String mediaType);
}
