package org.notesknowledge.notes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.notesknowledge.DatabaseUuidV7Generator;
import org.notesknowledge.websupport.ApiFailureException;
import org.notesknowledge.websupport.CursorPage;
import org.notesknowledge.websupport.IfMatchPrecondition;
import org.notesknowledge.websupport.NoteCoreVersion;
import org.notesknowledge.websupport.OpaqueCursorCodec;
import org.notesknowledge.websupport.PageLimitPolicy;
import org.notesknowledge.websupport.StrongCoreEtagCodec;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class NotesService {
    record EtaggedNote(NoteRecord.NoteView note, String etag) { }

    private static final PageLimitPolicy PAGE_LIMIT = new PageLimitPolicy(20, 100);
    private static final Duration CURSOR_LIFETIME = Duration.ofHours(24);
    private static final OpaqueCursorCodec.RouteFamily NOTES_ROUTE =
            new OpaqueCursorCodec.RouteFamily("OWNER_NOTES");
    private static final OpaqueCursorCodec.SortCode UPDATED_DESC =
            new OpaqueCursorCodec.SortCode("UPDATED_DESC");

    private final NotesRepository repository;
    private final DatabaseUuidV7Generator ids;
    private final Clock clock;
    private final StrongCoreEtagCodec etags;
    private final IfMatchPrecondition preconditions;
    private final OpaqueCursorCodec cursors;
    private final NoteVersionService versions;

    NotesService(NotesRepository repository, DatabaseUuidV7Generator ids, Clock clock,
            StrongCoreEtagCodec etags, IfMatchPrecondition preconditions,
            OpaqueCursorCodec cursors, NoteVersionService versions) {
        this.repository = repository;
        this.ids = ids;
        this.clock = clock;
        this.etags = etags;
        this.preconditions = preconditions;
        this.cursors = cursors;
        this.versions = versions;
    }

    boolean preference(UUID owner) { return repository.preference(owner); }

    @Transactional
    void setPreference(UUID owner, boolean enabled) {
        repository.setPreference(owner, enabled, now());
    }

    @Transactional
    EtaggedNote create(UUID owner, String title, String markdown, Boolean override) {
        validateEditor(title, markdown);
        boolean aiEnabled = override != null ? override : repository.preference(owner);
        UUID id = ids.generate();
        repository.create(id, owner, title, markdown, aiEnabled, now());
        return get(owner, id);
    }

    EtaggedNote get(UUID owner, UUID id) {
        return etagged(repository.find(owner, id)
                .orElseThrow(() -> ApiFailureException.of(ApiFailureException.Kind.RESOURCE_NOT_FOUND)));
    }

    @Transactional
    EtaggedNote save(UUID owner, UUID id, String ifMatch, String title, String markdown) {
        NoteRecord current = repository.lock(owner, id)
                .orElseThrow(() -> ApiFailureException.of(ApiFailureException.Kind.RESOURCE_NOT_FOUND));
        preconditions.requireCurrent(ifMatch, tag(current));
        validateEditor(title, markdown);
        if (!"active".equals(current.lifecycle())) {
            throw ApiFailureException.of(ApiFailureException.Kind.INVALID_LIFECYCLE_TRANSITION);
        }
        var time = now();
        versions.checkpointForSave(owner, current, title, markdown, time);
        int updated = repository.save(owner, id, current.revision(), title, markdown, time);
        if (updated == 0) {
            NoteRecord latest = repository.find(owner, id)
                    .orElseThrow(() -> ApiFailureException.of(ApiFailureException.Kind.RESOURCE_NOT_FOUND));
            if (latest.revision() != current.revision()) {
                throw ApiFailureException.of(ApiFailureException.Kind.STALE_WRITE);
            }
            throw ApiFailureException.of(ApiFailureException.Kind.INVALID_LIFECYCLE_TRANSITION);
        }
        return get(owner, id);
    }

    CursorPage<NoteRecord.NoteView> list(UUID owner, String lifecycle, Boolean pinned,
            String sort, Integer requestedLimit, String cursor) {
        String state = lifecycle == null ? "active" : lifecycle;
        if (!List.of("active", "archived", "trashed").contains(state)
                || (sort != null && !"updatedAtDesc".equals(sort))) {
            throw ApiFailureException.of(ApiFailureException.Kind.INVALID_INPUT);
        }
        int limit = PAGE_LIMIT.resolve(requestedLimit);
        var context = new OpaqueCursorCodec.ExpectedCursorContext(NOTES_ROUTE,
                new OpaqueCursorCodec.ScopeFingerprint(digest(owner.toString())),
                new OpaqueCursorCodec.FilterFingerprint(digest(state + ":" + pinned)), UPDATED_DESC);
        Instant beforeTime = null;
        UUID beforeId = null;
        if (cursor != null) {
            var position = cursors.decode(cursor, context).position().scalars();
            if (position.size() != 2
                    || !(position.get(0) instanceof OpaqueCursorCodec.EpochMillisValue time)
                    || !(position.get(1) instanceof OpaqueCursorCodec.UuidValue id)) {
                throw ApiFailureException.of(ApiFailureException.Kind.MALFORMED_REQUEST);
            }
            beforeTime = Instant.ofEpochMilli(time.value());
            beforeId = id.value();
        }
        List<NoteRecord> rows = repository.page(owner, state, pinned, beforeTime, beforeId,
                limit + 1);
        boolean more = rows.size() > limit;
        List<NoteRecord> visible = more ? rows.subList(0, limit) : rows;
        String next = null;
        if (more) {
            NoteRecord last = visible.getLast();
            next = cursors.encode(context, new OpaqueCursorCodec.OrderingTuple(List.of(
                    new OpaqueCursorCodec.EpochMillisValue(last.updatedAt().toEpochMilli()),
                    new OpaqueCursorCodec.UuidValue(last.id()))), CURSOR_LIFETIME);
        }
        return new CursorPage<>(visible.stream().map(NoteRecord::view).toList(), next);
    }

    private EtaggedNote etagged(NoteRecord note) {
        return new EtaggedNote(note.view(), tag(note));
    }

    private String tag(NoteRecord note) {
        return etags.encode(new NoteCoreVersion(note.id(), note.revision()));
    }

    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MILLIS); }

    private static void validateEditor(String title, String markdown) {
        if (title == null || title.isBlank() || title.length() > 500
                || markdown == null || markdown.length() > 1_000_000) {
            throw ApiFailureException.of(ApiFailureException.Kind.INVALID_INPUT);
        }
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
