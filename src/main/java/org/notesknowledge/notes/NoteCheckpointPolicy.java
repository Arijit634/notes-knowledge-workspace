package org.notesknowledge.notes;

import java.time.Duration;
import java.time.Instant;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Cadence applies to changed, already-saved content, not editor keystrokes or every Save. */
@ConfigurationProperties(prefix = "notes.checkpoints")
record NoteCheckpointPolicy(@DefaultValue("PT5M") Duration minimumSpacing,
        @DefaultValue("50") int maxUnheld) {
    NoteCheckpointPolicy {
        if (minimumSpacing == null || minimumSpacing.compareTo(Duration.ofSeconds(1)) < 0
                || minimumSpacing.compareTo(Duration.ofDays(7)) > 0 || maxUnheld < 2 || maxUnheld > 1000) {
            throw new IllegalArgumentException("Invalid Note checkpoint policy");
        }
    }

    boolean eligible(NoteRecord saved, String nextTitle, String nextMarkdown, Instant lastCheckpoint,
            Instant now) {
        if (saved.title().equals(nextTitle) && saved.markdown().equals(nextMarkdown)) return false;
        Instant anchor = lastCheckpoint == null ? saved.createdAt() : lastCheckpoint;
        return !now.isBefore(anchor.plus(minimumSpacing));
    }
}
