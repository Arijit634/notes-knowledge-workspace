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
class NoteVersionService {
    private final NotesRepository notes;
    private final NoteVersionRepository versions;
    private final NoteCheckpointPolicy policy;
    private final DatabaseUuidV7Generator ids;
    private final Clock clock;
    private final OpaqueCursorCodec cursors;
    private final StrongCoreEtagCodec etags;
    private final IfMatchPrecondition preconditions;

    NoteVersionService(NotesRepository notes, NoteVersionRepository versions, NoteCheckpointPolicy policy,
            DatabaseUuidV7Generator ids, Clock clock, OpaqueCursorCodec cursors,
            StrongCoreEtagCodec etags, IfMatchPrecondition preconditions) {
        this.notes = notes; this.versions = versions; this.policy = policy; this.ids = ids;
        this.clock = clock; this.cursors = cursors; this.etags = etags; this.preconditions = preconditions;
    }

    // Called only inside Save's owning Note transaction/lock, before editor content is replaced.
    void checkpointForSave(UUID owner, NoteRecord saved, String nextTitle, String nextMarkdown, Instant now) {
        if (!policy.eligible(saved, nextTitle, nextMarkdown, versions.latestTime(owner, saved.id()).orElse(null), now)) return;
        UUID retained = retain(owner, saved, "policy", now);
        versions.compact(owner, saved.id(), policy.maxUnheld(), List.of(retained));
    }

    private UUID retain(UUID owner, NoteRecord saved, String kind, Instant now) {
        var existing = versions.equivalent(owner, saved);
        if (existing.isPresent()) return existing.get();
        UUID id = ids.generate();
        versions.insert(owner, id, saved, kind, now);
        return id;
    }

    CursorPage<NoteVersionRecord.Summary> list(UUID owner, UUID note, Integer requestedLimit, String cursor) {
        requireNote(owner, note);
        int limit = new PageLimitPolicy(20, 100).resolve(requestedLimit);
        var context = new OpaqueCursorCodec.ExpectedCursorContext(new OpaqueCursorCodec.RouteFamily("NOTE_VERSIONS"),
                new OpaqueCursorCodec.ScopeFingerprint(digest(owner.toString())),
                new OpaqueCursorCodec.FilterFingerprint(digest(note.toString())), new OpaqueCursorCodec.SortCode("CREATED_DESC"));
        Instant before = null; UUID beforeId = null;
        if (cursor != null) {
            var tuple = cursors.decode(cursor, context).position().scalars();
            if (tuple.size() != 2 || !(tuple.get(0) instanceof OpaqueCursorCodec.EpochMillisValue time)
                    || !(tuple.get(1) instanceof OpaqueCursorCodec.UuidValue id)) {
                throw ApiFailureException.of(ApiFailureException.Kind.MALFORMED_REQUEST);
            }
            before = Instant.ofEpochMilli(time.value()); beforeId = id.value();
        }
        var rows = versions.page(owner, note, before, beforeId, limit + 1);
        boolean more = rows.size() > limit;
        var visible = more ? rows.subList(0, limit) : rows;
        String next = null;
        if (more) {
            var last = visible.getLast();
            next = cursors.encode(context, new OpaqueCursorCodec.OrderingTuple(List.of(
                    new OpaqueCursorCodec.EpochMillisValue(last.createdAt().toEpochMilli()),
                    new OpaqueCursorCodec.UuidValue(last.id()))), Duration.ofHours(24));
        }
        return new CursorPage<>(visible, next);
    }

    NoteVersionRecord read(UUID owner, UUID note, UUID version) {
        requireNote(owner, note);
        return versions.find(owner, note, version).orElseThrow(NoteVersionService::missing);
    }

    @Transactional
    NotesService.EtaggedNote restore(UUID owner, UUID note, UUID version, String ifMatch) {
        var current = notes.lock(owner, note).orElseThrow(NoteVersionService::missing);
        var selected = versions.find(owner, note, version).orElseThrow(NoteVersionService::missing);
        preconditions.requireCurrent(ifMatch, etags.encode(new NoteCoreVersion(note, current.revision())));
        if (!"active".equals(current.lifecycle())) {
            throw ApiFailureException.of(ApiFailureException.Kind.INVALID_LIFECYCLE_TRANSITION);
        }
        var now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        UUID preserved = retain(owner, current, "pre_restore", now);
        if (notes.save(owner, note, current.revision(), selected.title(), selected.markdown(), now) != 1) {
            throw ApiFailureException.of(ApiFailureException.Kind.STALE_WRITE);
        }
        // Protect both the inspected checkpoint and immediately pre-restore content in this command.
        versions.compact(owner, note, policy.maxUnheld(), List.of(version, preserved).stream().distinct().toList());
        var restored = requireNote(owner, note);
        return new NotesService.EtaggedNote(restored.view(), etags.encode(new NoteCoreVersion(note, restored.revision())));
    }

    @Transactional
    void acquirePublicationHold(UUID owner, UUID note, UUID version, UUID holder) {
        notes.lock(owner, note).orElseThrow(NoteVersionService::missing);
        versions.find(owner, note, version).orElseThrow(NoteVersionService::missing);
        versions.acquirePublicationHold(owner, note, version, holder, clock.instant().truncatedTo(ChronoUnit.MILLIS));
    }

    @Transactional
    void releasePublicationHold(UUID owner, UUID note, UUID version, UUID holder) {
        notes.lock(owner, note).orElseThrow(NoteVersionService::missing);
        versions.releasePublicationHold(owner, note, version, holder);
    }

    private NoteRecord requireNote(UUID owner, UUID note) {
        return notes.find(owner, note).orElseThrow(NoteVersionService::missing);
    }

    private static ApiFailureException missing() { return ApiFailureException.of(ApiFailureException.Kind.RESOURCE_NOT_FOUND); }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException("SHA-256 unavailable", failure); }
    }
}
