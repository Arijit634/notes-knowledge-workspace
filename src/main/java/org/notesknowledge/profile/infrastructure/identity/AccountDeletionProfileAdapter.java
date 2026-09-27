package org.notesknowledge.profile.infrastructure.identity;

import java.util.UUID;

import org.notesknowledge.identity.spi.AccountDeletionProfileConsequence;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Profile currently has no persisted state; this seam owns its later denial writes. */
@Component
public class AccountDeletionProfileAdapter implements AccountDeletionProfileConsequence {
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void makeIneligible(UUID userId) {
        // Profile projections and avatars are not yet implemented.
    }
}
