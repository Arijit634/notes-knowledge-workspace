package org.notesknowledge.publishing.infrastructure.notes;

import java.util.UUID;

import org.notesknowledge.notes.spi.SourceRetirementPublicationConsequence;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Publishing has no persisted Publications yet; there is no active public source to retire. */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class PublishingSourceRetirementAdapter implements SourceRetirementPublicationConsequence {
    @Override
    public boolean hasActiveSourcePublication(UUID ownerUserId, UUID noteId) {
        return false;
    }

    @Override
    public void makeIneligible(UUID ownerUserId, UUID noteId) {
        // Publication/public-generation state is not implemented in this phase.
    }
}
