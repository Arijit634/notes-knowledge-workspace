package org.notesknowledge.notes;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.notesknowledge.websupport.ApiFailureException;
import org.notesknowledge.websupport.IfMatchPrecondition;
import org.notesknowledge.websupport.NoteCoreVersion;
import org.notesknowledge.websupport.StrongCoreEtagCodec;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** These commands never write editor-owned title or Markdown. */
@Service
class NoteOrganizationService {
    enum Command { PIN, UNPIN, ARCHIVE, RETURN_FROM_ARCHIVE }

    private final NotesRepository repository;
    private final NotesService notes;
    private final StrongCoreEtagCodec etags;
    private final IfMatchPrecondition preconditions;
    private final Clock clock;

    NoteOrganizationService(NotesRepository repository, NotesService notes,
            StrongCoreEtagCodec etags, IfMatchPrecondition preconditions, Clock clock) {
        this.repository = repository;
        this.notes = notes;
        this.etags = etags;
        this.preconditions = preconditions;
        this.clock = clock;
    }

    @Transactional
    NotesService.EtaggedNote execute(UUID owner, UUID id, String ifMatch, Command command) {
        // No-op pin requests also lock and check current authority, rather than
        // accepting a stale validator merely because the requested state matches.
        NoteRecord current = repository.lock(owner, id)
                .orElseThrow(() -> ApiFailureException.of(ApiFailureException.Kind.RESOURCE_NOT_FOUND));
        preconditions.requireCurrent(ifMatch,
                etags.encode(new NoteCoreVersion(id, current.revision())));
        var now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        int updated;
        if (command == Command.PIN || command == Command.UNPIN) {
            if (!List.of("active", "archived").contains(current.lifecycle())) throw illegal();
            boolean desired = command == Command.PIN;
            if (current.pinned() == desired) return notes.get(owner, id);
            updated = repository.setPin(owner, id, current.revision(), desired, now);
        } else {
            String from = command == Command.ARCHIVE ? "active" : "archived";
            String to = command == Command.ARCHIVE ? "archived" : "active";
            if (!from.equals(current.lifecycle())) throw illegal();
            updated = repository.transitionLifecycle(owner, id, current.revision(), from, to, now);
        }
        if (updated != 1) {
            NoteRecord latest = repository.find(owner, id)
                    .orElseThrow(() -> ApiFailureException.of(ApiFailureException.Kind.RESOURCE_NOT_FOUND));
            if (latest.revision() != current.revision()) {
                throw ApiFailureException.of(ApiFailureException.Kind.STALE_WRITE);
            }
            throw illegal();
        }
        return notes.get(owner, id);
    }

    private static ApiFailureException illegal() {
        return ApiFailureException.of(ApiFailureException.Kind.INVALID_LIFECYCLE_TRANSITION);
    }
}
