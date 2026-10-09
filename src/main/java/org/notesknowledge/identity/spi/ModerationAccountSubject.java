package org.notesknowledge.identity.spi;

import java.util.UUID;

/** Identity-owned trusted provenance check; implemented by Publishing, never by HTTP input. */
public interface ModerationAccountSubject {
    boolean isResponsibleForRemovedPublication(UUID subject,UUID publication);
}
