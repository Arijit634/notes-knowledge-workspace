package org.notesknowledge.notes;

import java.time.Instant;
import java.util.UUID;

/** Safe authoritative core, deliberately excluding ownership, storage and AI lineage. */
record AttachmentView(UUID id, UUID noteId, String mediaKind, String displayFilename, String mediaType,
        long sizeBytes, Integer width, Integer height, Double durationSeconds, Integer pageCount,
        String storageState, String validationState, String cleanupState, Instant createdAt, Instant updatedAt) { }
