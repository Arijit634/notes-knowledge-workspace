package org.notesknowledge.identity.spi;

import java.util.UUID;

/** Knowledge-owned private history erasure within the Account deletion commit. */
public interface AccountDeletionKnowledgeConsequence {
    void makeIneligible(UUID userId);
}
