package org.notesknowledge.websupport;

import java.util.Objects;
import java.util.UUID;

public record PublicationCoreVersion(
        UUID publicationId,
        long snapshotRevision,
        long publicationGeneration) {

    public PublicationCoreVersion {
        Objects.requireNonNull(publicationId, "publicationId");
        if (snapshotRevision < 1 || publicationGeneration < 1) {
            throw new IllegalArgumentException("publication version components must be positive");
        }
    }
}
