package org.notesknowledge.publishing.infrastructure.identity;

import java.util.UUID;
import org.notesknowledge.identity.spi.ModerationAccountSubject;
import org.notesknowledge.publishing.PublicationModerationApi;
import org.springframework.stereotype.Component;

@Component
class ModerationAccountSubjectAdapter implements ModerationAccountSubject {
    private final PublicationModerationApi publications;
    ModerationAccountSubjectAdapter(PublicationModerationApi publications){this.publications=publications;}
    @Override public boolean isResponsibleForRemovedPublication(UUID subject,UUID publication){return publications.isRemovedResponsibility(subject,publication);}
}
