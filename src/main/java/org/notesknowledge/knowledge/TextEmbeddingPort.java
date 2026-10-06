package org.notesknowledge.knowledge;

import java.util.List;

/** Document embeddings only for this tranche. No HTTP/provider DTO enters the domain. */
interface TextEmbeddingPort {
    boolean available();
    List<float[]> embed(AiProcessingGate.SourceAiPermit permit,List<String> text);
}
