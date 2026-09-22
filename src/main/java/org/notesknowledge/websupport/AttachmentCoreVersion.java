package org.notesknowledge.websupport;

import java.util.Objects;
import java.util.UUID;

public record AttachmentCoreVersion(UUID attachmentId, long revision) {

    public AttachmentCoreVersion {
        Objects.requireNonNull(attachmentId, "attachmentId");
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
    }
}
