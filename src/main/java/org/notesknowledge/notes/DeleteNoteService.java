package org.notesknowledge.notes;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.notesknowledge.identity.RecentAuthenticationApi;
import org.notesknowledge.notes.spi.SourceRetirementPublicationConsequence;
import org.notesknowledge.websupport.ApiFailureException;
import org.notesknowledge.websupport.IfMatchPrecondition;
import org.notesknowledge.websupport.NoteCoreVersion;
import org.notesknowledge.websupport.StrongCoreEtagCodec;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.annotation.Transactional;

/** Logical denial is the completed command; physical retention/cleanup is separate policy. */
@Service
class DeleteNoteService {
    private final NotesRepository repository;
    private final ObjectProvider<RecentAuthenticationApi> recent;
    private final SourceRetirementPublicationConsequence publications;
    private final StrongCoreEtagCodec etags;
    private final IfMatchPrecondition preconditions;
    private final Clock clock;

    DeleteNoteService(NotesRepository repository, ObjectProvider<RecentAuthenticationApi> recent,
            SourceRetirementPublicationConsequence publications, StrongCoreEtagCodec etags,
            IfMatchPrecondition preconditions, Clock clock) {
        this.repository = repository;
        this.recent = recent;
        this.publications = publications;
        this.etags = etags;
        this.preconditions = preconditions;
        this.clock = clock;
    }

    @Transactional
    void delete(UUID owner, UUID id, String ifMatch, boolean confirmUnpublish,
            HttpServletRequest request) {
        // Unavailable owner-scoped resources remain nonexistent, including repeat deletion.
        if (repository.find(owner, id).isEmpty()) {
            throw ApiFailureException.of(ApiFailureException.Kind.RESOURCE_NOT_FOUND);
        }
        // Identity locks Account/session first, matching existing security-transition ordering.
        var guard = recent.getIfAvailable();
        if (guard == null) throw ApiFailureException.of(ApiFailureException.Kind.SERVICE_UNAVAILABLE);
        guard.requireRecent(owner, request);
        NoteRecord current = repository.lock(owner, id)
                .orElseThrow(() -> ApiFailureException.of(ApiFailureException.Kind.RESOURCE_NOT_FOUND));
        preconditions.requireCurrent(ifMatch, etags.encode(new NoteCoreVersion(id, current.revision())));
        if (!"trashed".equals(current.lifecycle())) {
            throw ApiFailureException.of(ApiFailureException.Kind.INVALID_LIFECYCLE_TRANSITION);
        }
        if (publications.hasActiveSourcePublication(owner, id)) {
            if (!confirmUnpublish) {
                throw ApiFailureException.of(ApiFailureException.Kind.PUBLICATION_CONSEQUENCE_REQUIRED);
            }
            publications.makeIneligible(owner, id);
        }
        if (repository.logicallyDelete(owner, id, current.revision(),
                clock.instant().truncatedTo(ChronoUnit.MILLIS)) != 1) {
            throw ApiFailureException.of(ApiFailureException.Kind.INVALID_LIFECYCLE_TRANSITION);
        }
    }
}
