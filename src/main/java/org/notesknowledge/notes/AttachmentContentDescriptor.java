package org.notesknowledge.notes;

import java.util.UUID;

/** Internal authorized byte-custody projection; never a JSON representation. */
record AttachmentContentDescriptor(UUID id, String reference, String mediaType, String filename, long size, long revision) {
    @Override public String toString() { return "AttachmentContentDescriptor[REDACTED]"; }
}
