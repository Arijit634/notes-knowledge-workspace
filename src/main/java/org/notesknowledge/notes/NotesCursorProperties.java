package org.notesknowledge.notes;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Deployment-supplied AES-256 keys; previous versions only decode outstanding cursors. */
@ConfigurationProperties(prefix = "notes.cursor")
record NotesCursorProperties(ConfiguredKey active, List<ConfiguredKey> previous) {
    NotesCursorProperties {
        previous = previous == null ? List.of() : List.copyOf(previous);
    }

    @Override public String toString() { return "NotesCursorProperties[REDACTED]"; }

    record ConfiguredKey(String version, String keyBase64) {
        @Override public String toString() { return "ConfiguredKey[REDACTED]"; }
    }
}
