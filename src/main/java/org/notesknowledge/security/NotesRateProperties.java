package org.notesknowledge.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Transient bulk-command capacity, charged once per bounded database batch. */
@ConfigurationProperties(prefix = "notes.rate")
record NotesRateProperties(@DefaultValue("60") int windowSeconds,
        @DefaultValue("60") int bulkCeiling, @DefaultValue("1200") int aggregateCeiling) {
    NotesRateProperties {
        if (windowSeconds < 1 || windowSeconds > 86400 || bulkCeiling < 1
                || bulkCeiling > 1_000_000 || aggregateCeiling < 1 || aggregateCeiling > 1_000_000) {
            throw new IllegalArgumentException("Invalid Notes rate-control policy");
        }
    }
    int ceiling(String control) {
        return switch (control) {
            case "NOTE_AI_BULK" -> bulkCeiling;
            case "NOTE_AI_BULK_GLOBAL" -> aggregateCeiling;
            default -> throw new IllegalArgumentException("Unknown Notes rate class");
        };
    }
}
