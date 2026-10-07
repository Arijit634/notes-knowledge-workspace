package org.notesknowledge.notes;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.notesknowledge.notes.spi.SourceRetirementPublicationConsequence;
import org.notesknowledge.websupport.ApiFailureException;
import org.notesknowledge.websupport.IfMatchPrecondition;
import org.notesknowledge.websupport.NoteCoreVersion;
import org.notesknowledge.websupport.StrongCoreEtagCodec;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Trash and restore affect lifecycle only, never the editor's saved content. */
@Service
class NoteTrashService {
    private final NotesRepository repository;
    private final NotesService notes;
    private final SourceRetirementPublicationConsequence publications;
    private final StrongCoreEtagCodec etags;
    private final IfMatchPrecondition preconditions;
    private final Clock clock;

    NoteTrashService(NotesRepository repository, NotesService notes,
            SourceRetirementPublicationConsequence publications, StrongCoreEtagCodec etags,
            IfMatchPrecondition preconditions, Clock clock) {
        this.repository = repository;
        this.notes = notes;
        this.publications = publications;
        this.etags = etags;
        this.preconditions = preconditions;
        this.clock = clock;
    }

    @Transactional
    @org.notesknowledge.CoordinatedMutation
    NotesService.EtaggedNote trash(UUID owner, UUID id, String ifMatch, boolean confirmUnpublish) {
        NoteRecord current = requireCurrent(owner, id, ifMatch);
        if (!"active".equals(current.lifecycle()) && !"archived".equals(current.lifecycle())) throw illegal();
        if (publications.hasActiveSourcePublication(owner, id)) {
            if (!confirmUnpublish) {
                throw ApiFailureException.of(ApiFailureException.Kind.PUBLICATION_CONSEQUENCE_REQUIRED);
            }
            // Same local transaction: any provider failure aborts source retirement.
            publications.makeIneligible(owner, id);
        }
        if (repository.trash(owner, id, current.revision(),
                clock.instant().truncatedTo(ChronoUnit.MILLIS)) != 1) throw illegal();
        return notes.get(owner, id);
    }

    @Transactional
    @org.notesknowledge.CoordinatedMutation
    NotesService.EtaggedNote restore(UUID owner, UUID id, String ifMatch) {
        NoteRecord current = requireCurrent(owner, id, ifMatch);
        if (!"trashed".equals(current.lifecycle())) throw illegal();
        if (repository.restore(owner, id, current.revision(),
                clock.instant().truncatedTo(ChronoUnit.MILLIS)) != 1) throw illegal();
        return notes.get(owner, id);
    }

    private NoteRecord requireCurrent(UUID owner, UUID id, String ifMatch) {
        NoteRecord current = repository.lock(owner, id)
                .orElseThrow(() -> ApiFailureException.of(ApiFailureException.Kind.RESOURCE_NOT_FOUND));
        preconditions.requireCurrent(ifMatch, etags.encode(new NoteCoreVersion(id, current.revision())));
        return current;
    }

    private static ApiFailureException illegal() {
        return ApiFailureException.of(ApiFailureException.Kind.INVALID_LIFECYCLE_TRANSITION);
    }
}
