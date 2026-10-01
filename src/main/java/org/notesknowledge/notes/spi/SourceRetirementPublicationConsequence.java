package org.notesknowledge.notes.spi;

import java.util.UUID;

/** Publishing owns detection and synchronous public denial in the source retirement transaction. */
public interface SourceRetirementPublicationConsequence {
    boolean hasActiveSourcePublication(UUID ownerUserId, UUID noteId);

    /** Deny the active public copy and its old generations before source retirement commits. */
    void makeIneligible(UUID ownerUserId, UUID noteId);
}
