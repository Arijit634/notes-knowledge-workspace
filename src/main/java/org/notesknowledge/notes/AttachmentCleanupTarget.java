package org.notesknowledge.notes;

import java.util.UUID;

/** Internal custody descriptor, never a public representation or authority. */
record AttachmentCleanupTarget(UUID id, String reference, long revision) {
    @Override public String toString() { return "AttachmentCleanupTarget[redacted]"; }
}
