package org.notesknowledge.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/** Transient bulk-command capacity, charged once per bounded database batch. */
@ConfigurationProperties(prefix = "notes.rate")
record NotesRateProperties(@DefaultValue("60") int windowSeconds,
        @DefaultValue("60") int bulkCeiling, @DefaultValue("1200") int aggregateCeiling,
        @DefaultValue("30") int searchCeiling, @DefaultValue("300") int searchAggregateCeiling) {
    NotesRateProperties(int windowSeconds, int bulkCeiling, int aggregateCeiling) {
        this(windowSeconds, bulkCeiling, aggregateCeiling, 30, 300);
    }
    @ConstructorBinding
    NotesRateProperties {
        if (windowSeconds < 1 || windowSeconds > 86400 || bulkCeiling < 1
                || bulkCeiling > 1_000_000 || aggregateCeiling < 1 || aggregateCeiling > 1_000_000
                || searchCeiling < 1 || searchCeiling > 10000 || searchAggregateCeiling < 1 || searchAggregateCeiling > 100000) {
            throw new IllegalArgumentException("Invalid Notes rate-control policy");
        }
    }
    int ceiling(String control) {
        return switch (control) {
            case "NOTE_AI_BULK" -> bulkCeiling;
            case "NOTE_AI_BULK_GLOBAL" -> aggregateCeiling;
            case "ATTACHMENT_UPLOAD" -> 12;
            case "ATTACHMENT_UPLOAD_GLOBAL" -> 120;
            case "NOTE_SEARCH" -> searchCeiling;
            case "NOTE_SEARCH_GLOBAL" -> searchAggregateCeiling;
            case "KNOWLEDGE_STATUS" -> 120;
            default -> throw new IllegalArgumentException("Unknown Notes rate class");
        };
    }
}
