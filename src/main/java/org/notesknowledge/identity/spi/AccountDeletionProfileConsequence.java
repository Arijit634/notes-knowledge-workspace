package org.notesknowledge.identity.spi;

import java.util.UUID;

/** Profile-owned public projection and avatar eligibility denial within Account deletion. */
public interface AccountDeletionProfileConsequence {
    void makeIneligible(UUID userId);
}
