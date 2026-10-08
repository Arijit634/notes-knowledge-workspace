package org.notesknowledge.publishing.infrastructure.identity;

import java.util.UUID;

import org.notesknowledge.identity.spi.AccountDeletionPublishingConsequence;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Account deletion cannot commit before all active public generations are denied. */
@Component
public class AccountDeletionPublishingAdapter implements AccountDeletionPublishingConsequence {
    private final org.notesknowledge.publishing.PublicDenialApi publications;
    public AccountDeletionPublishingAdapter(org.notesknowledge.publishing.PublicDenialApi publications){this.publications=publications;}
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void makeIneligible(UUID userId) {
        publications.retireAccount(userId);
    }
}
