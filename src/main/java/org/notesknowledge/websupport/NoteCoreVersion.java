package org.notesknowledge.websupport;

import java.util.Objects;
import java.util.UUID;

public record NoteCoreVersion(UUID noteId, long revision) {

    public NoteCoreVersion {
        Objects.requireNonNull(noteId, "noteId");
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
    }
}
