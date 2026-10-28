package org.notesknowledge.notes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Collection-only presentation data; never part of a core mutation validator. */
record NoteCollectionItem(UUID id, String title, String markdown, String lifecycle,
        boolean pinned, List<String> tags, boolean aiEnabled, Instant createdAt,
        Instant updatedAt, AttachmentSummary attachmentSummary) {
    record AttachmentSummary(int images, int audio, int video, int documents) {
        static final AttachmentSummary EMPTY = new AttachmentSummary(0, 0, 0, 0);
    }
    static NoteCollectionItem of(NoteRecord note, AttachmentSummary summary) {
        return new NoteCollectionItem(note.id(), note.title(), note.markdown(), note.lifecycle(),
                note.pinned(), note.tags(), note.aiEnabled(), note.createdAt(), note.updatedAt(), summary);
    }
}
