package org.notesknowledge.notes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.notesknowledge.security.RateControlService;
import org.notesknowledge.security.RateKeyDeriver;
import org.notesknowledge.security.RateLimitPort;
import org.notesknowledge.websupport.ApiFailureException;
import org.notesknowledge.websupport.CursorPage;
import org.notesknowledge.websupport.OpaqueCursorCodec;
import org.notesknowledge.websupport.PageLimitPolicy;
import org.springframework.stereotype.Service;

@Service
class PrivateNoteSearchQuery {
    private static final PageLimitPolicy LIMIT = new PageLimitPolicy(20, 50);
    private final NotesSearchRepository repository;
    private final OpaqueCursorCodec cursors;
    private final RateControlService rates;
    private final RateKeyDeriver keys;
    PrivateNoteSearchQuery(NotesSearchRepository repository, OpaqueCursorCodec cursors,
            RateControlService rates, RateKeyDeriver keys) {
        this.repository = repository; this.cursors = cursors; this.rates = rates; this.keys = keys;
    }

    CursorPage<NoteSearchResult> search(UUID owner, PrivateSearchRequest request) {
        int limit = LIMIT.resolve(request.limit());
        var context = new OpaqueCursorCodec.ExpectedCursorContext(
                new OpaqueCursorCodec.RouteFamily("NOTE_SEARCH"),
                new OpaqueCursorCodec.ScopeFingerprint(digest(owner.toString())),
                new OpaqueCursorCodec.FilterFingerprint(digest(digest(request.query()) + "|"
                    + request.lifecycle() + "|" + request.tags().stream().map(PrivateNoteSearchQuery::digest).toList()
                    + "|" + request.sort())), new OpaqueCursorCodec.SortCode("RELEVANCE_DESC"));
        Long beforeRank = null; UUID beforeId = null;
        if (request.cursor() != null) {
            var tuple = cursors.decode(request.cursor(), context).position().scalars();
            if (tuple.size() != 2 || !(tuple.get(0) instanceof OpaqueCursorCodec.SignedLongValue rank)
                    || rank.value() < 0 || !(tuple.get(1) instanceof OpaqueCursorCodec.UuidValue id)) {
                throw ApiFailureException.of(ApiFailureException.Kind.MALFORMED_REQUEST);
            }
            beforeRank = rank.value(); beforeId = id.value();
        }
        // Never use search text as an abuse bucket or telemetry identifier.
        rates.check(new RateLimitPort.Request(new RateLimitPort.ControlClass("NOTE_SEARCH"),
                keys.derive("NOTE_SEARCH", owner.toString()), 1), RateControlService.Policy.SECURITY_CRITICAL);
        rates.check(new RateLimitPort.Request(new RateLimitPort.ControlClass("NOTE_SEARCH_GLOBAL"),
                keys.derive("NOTE_SEARCH_GLOBAL", "application"), 1), RateControlService.Policy.SECURITY_CRITICAL);
        var rows = repository.search(owner, request, beforeRank, beforeId, limit + 1);
        boolean more = rows.size() > limit;
        var visible = more ? rows.subList(0, limit) : rows;
        String next = null;
        if (more) {
            var last = visible.getLast();
            next = cursors.encode(context, new OpaqueCursorCodec.OrderingTuple(List.of(
                new OpaqueCursorCodec.SignedLongValue(last.rank()),
                new OpaqueCursorCodec.UuidValue(last.result().id()))), Duration.ofMinutes(15));
        }
        return new CursorPage<>(visible.stream().map(NotesSearchRepository.Row::result).toList(), next);
    }

    private static String digest(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
}
