package org.notesknowledge.notes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.notesknowledge.websupport.ApiFailureException;
import org.notesknowledge.websupport.CursorPage;
import org.notesknowledge.websupport.OpaqueCursorCodec;
import org.notesknowledge.websupport.PageLimitPolicy;
import org.springframework.stereotype.Service;

/** Relational metadata only: neither object custody nor parser state grants read authority. */
@Service
class ReadAttachmentQuery {
    private final NotesRepository notes;
    private final AttachmentRepository attachments;
    private final OpaqueCursorCodec cursors;

    ReadAttachmentQuery(NotesRepository notes, AttachmentRepository attachments, OpaqueCursorCodec cursors) {
        this.notes = notes; this.attachments = attachments; this.cursors = cursors;
    }

    CursorPage<AttachmentView> page(UUID owner, UUID note, Map<String, String> query) {
        requireNote(owner, note); // Even a malformed/cross-scope cursor must not reveal another owner's Note.
        if (!Set.of("limit", "cursor").containsAll(query.keySet())) throw malformed();
        Integer requestedLimit = null;
        if (query.containsKey("limit")) {
            try { requestedLimit = Integer.valueOf(query.get("limit")); }
            catch (NumberFormatException failure) { throw malformed(); }
        }
        int limit = new PageLimitPolicy(20, 100).resolve(requestedLimit);
        var context = new OpaqueCursorCodec.ExpectedCursorContext(new OpaqueCursorCodec.RouteFamily("NOTE_ATTACHMENTS"),
                new OpaqueCursorCodec.ScopeFingerprint(digest(owner.toString())),
                new OpaqueCursorCodec.FilterFingerprint(digest(note.toString())), new OpaqueCursorCodec.SortCode("CREATED_DESC"));
        Instant before = null; UUID beforeId = null;
        if (query.containsKey("cursor")) {
            var tuple = cursors.decode(query.get("cursor"), context).position().scalars();
            if (tuple.size() != 2 || !(tuple.get(0) instanceof OpaqueCursorCodec.EpochMillisValue time)
                    || !(tuple.get(1) instanceof OpaqueCursorCodec.UuidValue id)) throw malformed();
            before = Instant.ofEpochMilli(time.value()); beforeId = id.value();
        }
        var rows = attachments.retainedPage(owner, note, before, beforeId, limit + 1);
        boolean more = rows.size() > limit;
        var visible = more ? rows.subList(0, limit) : rows;
        String next = null;
        if (more) {
            var last = visible.getLast().view();
            next = cursors.encode(context, new OpaqueCursorCodec.OrderingTuple(List.of(
                    new OpaqueCursorCodec.EpochMillisValue(last.createdAt().toEpochMilli()),
                    new OpaqueCursorCodec.UuidValue(last.id()))), Duration.ofHours(24));
        }
        return new CursorPage<>(visible.stream().map(AttachmentRepository.Core::view).toList(), next);
    }

    AttachmentRepository.Core read(UUID owner, UUID note, UUID attachment) {
        requireNote(owner, note);
        return attachments.findRetained(owner, note, attachment).orElseThrow(ReadAttachmentQuery::missing);
    }

    private void requireNote(UUID owner, UUID note) { notes.find(owner, note).orElseThrow(ReadAttachmentQuery::missing); }
    private static ApiFailureException missing() { return ApiFailureException.of(ApiFailureException.Kind.RESOURCE_NOT_FOUND); }
    private static ApiFailureException malformed() { return ApiFailureException.of(ApiFailureException.Kind.MALFORMED_REQUEST); }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException("SHA-256 unavailable", failure); }
    }
}
