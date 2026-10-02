package org.notesknowledge.notes;

import java.time.Instant;
import java.util.UUID;

/** Private immutable history, never a Knowledge/Search corpus. */
record NoteVersionRecord(UUID id, String title, String markdown, long sourceRevision,
        String checkpointKind, Instant createdAt) {
    Summary summary() { return new Summary(id, title, sourceRevision, checkpointKind, createdAt); }
    record Summary(UUID id, String title, long sourceRevision, String checkpointKind, Instant createdAt) { }
}
