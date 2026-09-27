package org.notesknowledge.identity.spi;

import java.util.UUID;

/** Publishing-owned public availability denial within Account deletion. */
public interface AccountDeletionPublishingConsequence {
    void makeIneligible(UUID userId);
}
