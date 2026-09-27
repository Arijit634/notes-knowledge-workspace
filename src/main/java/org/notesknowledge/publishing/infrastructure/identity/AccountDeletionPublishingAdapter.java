package org.notesknowledge.publishing.infrastructure.identity;

import java.util.UUID;

import org.notesknowledge.identity.spi.AccountDeletionPublishingConsequence;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Publishing currently has no persisted state; this seam owns later public denial. */
@Component
public class AccountDeletionPublishingAdapter implements AccountDeletionPublishingConsequence {
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void makeIneligible(UUID userId) {
        // Publications and their public generations are not yet implemented.
    }
}
