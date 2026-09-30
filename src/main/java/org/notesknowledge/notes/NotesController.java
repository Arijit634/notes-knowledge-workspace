package org.notesknowledge.notes;

import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.notesknowledge.websupport.ApiFailureException;
import org.notesknowledge.websupport.CursorPage;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notes")
final class NotesController {
    private static final Set<String> CREATE_FIELDS = Set.of("title", "markdown", "aiEnabled");
    private static final Set<String> SAVE_FIELDS = Set.of("title", "markdown");
    private static final Set<String> LIST_FIELDS = Set.of("lifecycle", "pinned", "sort",
            "limit", "cursor");

    private final NotesService notes;

    NotesController(NotesService notes) { this.notes = notes; }

    @PostMapping
    ResponseEntity<NoteRecord.NoteView> create(@RequestBody Map<String, Object> input) {
        if (input == null || !CREATE_FIELDS.containsAll(input.keySet())
                || !input.keySet().containsAll(SAVE_FIELDS)
                || !(input.get("title") instanceof String title)
                || !(input.get("markdown") instanceof String markdown)
                || (input.containsKey("aiEnabled") && !(input.get("aiEnabled") instanceof Boolean))) {
            throw ApiFailureException.of(ApiFailureException.Kind.INVALID_INPUT);
        }
        var result = notes.create(NotesActor.owner(), title, markdown,
                (Boolean) input.get("aiEnabled"));
        return ResponseEntity.created(URI.create("/api/notes/" + result.note().id()))
                .cacheControl(CacheControl.noStore()).eTag(result.etag()).body(result.note());
    }

    @GetMapping
    ResponseEntity<CursorPage<NoteRecord.NoteView>> list(@RequestParam Map<String, String> query) {
        if (!LIST_FIELDS.containsAll(query.keySet())) {
            throw ApiFailureException.of(ApiFailureException.Kind.MALFORMED_REQUEST);
        }
        Integer limit = null;
        if (query.containsKey("limit")) {
            try {
                limit = Integer.valueOf(query.get("limit"));
            } catch (NumberFormatException exception) {
                throw ApiFailureException.of(ApiFailureException.Kind.MALFORMED_REQUEST);
            }
        }
        Boolean pinned = null;
        if (query.containsKey("pinned")) {
            pinned = switch (query.get("pinned")) {
                case "true" -> true;
                case "false" -> false;
                default -> throw ApiFailureException.of(ApiFailureException.Kind.MALFORMED_REQUEST);
            };
        }
        var page = notes.list(NotesActor.owner(), query.get("lifecycle"), pinned,
                query.get("sort"), limit, query.get("cursor"));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(page);
    }

    @GetMapping("/{noteId}")
    ResponseEntity<NoteRecord.NoteView> get(@PathVariable UUID noteId) {
        var result = notes.get(NotesActor.owner(), noteId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .eTag(result.etag()).body(result.note());
    }

    @PutMapping("/{noteId}")
    ResponseEntity<NoteRecord.NoteView> save(@PathVariable UUID noteId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestBody Map<String, Object> input) {
        if (input == null || !input.keySet().equals(SAVE_FIELDS)
                || !(input.get("title") instanceof String title)
                || !(input.get("markdown") instanceof String markdown)) {
            throw ApiFailureException.of(ApiFailureException.Kind.INVALID_INPUT);
        }
        var result = notes.save(NotesActor.owner(), noteId, ifMatch, title, markdown);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .eTag(result.etag()).body(result.note());
    }
}
