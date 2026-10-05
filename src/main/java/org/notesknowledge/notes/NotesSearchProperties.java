package org.notesknowledge.notes;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "notes.search")
record NotesSearchProperties(@DefaultValue("50") int candidatesPerSignal,
        @DefaultValue("0.3") double wordSimilarityThreshold,
        @DefaultValue("2000") int timeoutMillis) {
    NotesSearchProperties {
        if (candidatesPerSignal < 1 || candidatesPerSignal > 50
                || !Double.isFinite(wordSimilarityThreshold) || wordSimilarityThreshold < 0.2
                || wordSimilarityThreshold > 0.9 || timeoutMillis < 100 || timeoutMillis > 5000) {
            throw new IllegalArgumentException("Invalid ordinary search policy");
        }
    }
}
