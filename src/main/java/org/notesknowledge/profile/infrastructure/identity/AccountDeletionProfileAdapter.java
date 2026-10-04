package org.notesknowledge.profile.infrastructure.identity;

import java.util.UUID;

import org.notesknowledge.identity.spi.AccountDeletionProfileConsequence;
import org.notesknowledge.profile.AccountDeletionAvatarConsequence;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Public-state denial seam; private Profile access is gated by current Identity eligibility. */
@Component
public class AccountDeletionProfileAdapter implements AccountDeletionProfileConsequence {
    private final AccountDeletionAvatarConsequence avatars;
    public AccountDeletionProfileAdapter(AccountDeletionAvatarConsequence avatars) { this.avatars = avatars; }
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void makeIneligible(UUID userId) {
        avatars.makeIneligible(userId);
    }
}
