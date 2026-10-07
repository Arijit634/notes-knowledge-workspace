package org.notesknowledge.notes;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import org.notesknowledge.websupport.ApiFailureException;
import org.notesknowledge.websupport.IfMatchPrecondition;
import org.notesknowledge.websupport.NoteCoreVersion;
import org.notesknowledge.websupport.StrongCoreEtagCodec;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ReplaceTagsService {
    private final NotesRepository repository;
    private final NotesService notes;
    private final StrongCoreEtagCodec etags;
    private final IfMatchPrecondition preconditions;
    private final Clock clock;

    ReplaceTagsService(NotesRepository repository, NotesService notes,
            StrongCoreEtagCodec etags, IfMatchPrecondition preconditions, Clock clock) {
        this.repository = repository;
        this.notes = notes;
        this.etags = etags;
        this.preconditions = preconditions;
        this.clock = clock;
    }

    @Transactional
    @org.notesknowledge.CoordinatedMutation
    NotesService.EtaggedNote replace(UUID owner, UUID id, String ifMatch, List<String> values) {
        // Lock the Note root even for a no-op: the precondition and full set belong
        // to the same committed revision, including concurrent text Save commands.
        NoteRecord current = repository.lock(owner, id)
                .orElseThrow(() -> ApiFailureException.of(ApiFailureException.Kind.RESOURCE_NOT_FOUND));
        preconditions.requireCurrent(ifMatch,
                etags.encode(new NoteCoreVersion(id, current.revision())));
        if (!List.of("active", "archived").contains(current.lifecycle())) {
            throw ApiFailureException.of(ApiFailureException.Kind.INVALID_LIFECYCLE_TRANSITION);
        }
        List<TagLabel> tags = TagLabel.validate(values);
        if (!new HashSet<>(current.tags()).equals(
                new HashSet<>(tags.stream().map(TagLabel::display).toList()))) {
            var now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
            if (repository.advanceTagRevision(owner, id, current.revision(), now) != 1) {
                throw ApiFailureException.of(ApiFailureException.Kind.STALE_WRITE);
            }
            repository.replaceTags(owner, id, tags, now);
        }
        return notes.get(owner, id);
    }
}
