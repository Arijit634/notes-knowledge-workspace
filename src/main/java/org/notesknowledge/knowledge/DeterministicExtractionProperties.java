package org.notesknowledge.knowledge;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("knowledge.deterministic")
record DeterministicExtractionProperties(@DefaultValue("500") int maximumResults,
        @DefaultValue("2000") int maximumOccurrences) {
    DeterministicExtractionProperties {
        if(maximumResults<1||maximumResults>1000||maximumOccurrences<1||maximumOccurrences>5000)
            throw new IllegalArgumentException("Invalid deterministic extraction bounds");
    }
}
