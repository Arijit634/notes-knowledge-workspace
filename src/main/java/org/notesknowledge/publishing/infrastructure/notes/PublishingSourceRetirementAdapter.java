package org.notesknowledge.publishing.infrastructure.notes;

import java.util.UUID;

import org.notesknowledge.notes.spi.SourceRetirementPublicationConsequence;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Synchronous public denial participates in the source Note's owning transaction. */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class PublishingSourceRetirementAdapter implements SourceRetirementPublicationConsequence {
    private final org.notesknowledge.publishing.PublicDenialApi publications;
    public PublishingSourceRetirementAdapter(org.notesknowledge.publishing.PublicDenialApi publications){this.publications=publications;}
    @Override
    public boolean hasActiveSourcePublication(UUID ownerUserId, UUID noteId) {
        return publications.hasActiveSource(ownerUserId,noteId);
    }

    @Override
    public void makeIneligible(UUID ownerUserId, UUID noteId) {
        publications.retireSource(ownerUserId,noteId);
    }
}
