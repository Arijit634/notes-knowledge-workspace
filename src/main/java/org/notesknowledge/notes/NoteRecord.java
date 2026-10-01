package org.notesknowledge.notes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The current Notes-owned core, never an AI-processing projection. */
record NoteRecord(UUID id, String title, String markdown, String lifecycle,
        boolean pinned, boolean aiEnabled, Instant createdAt, Instant updatedAt,
        long revision, List<String> tags) {
    NoteView view() {
        return new NoteView(id, title, markdown, lifecycle, pinned, tags,
                aiEnabled, createdAt, updatedAt);
    }

    record NoteView(UUID id, String title, String markdown, String lifecycle,
            boolean pinned, List<String> tags, boolean aiEnabled,
            Instant createdAt, Instant updatedAt) { }
}
