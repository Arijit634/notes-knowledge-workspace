package org.notesknowledge.notes;

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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class NoteVersionController {
    private final NoteVersionService versions;
    NoteVersionController(NoteVersionService versions) { this.versions = versions; }

    @GetMapping("/api/notes/{noteId}/versions")
    ResponseEntity<CursorPage<NoteVersionRecord.Summary>> list(@PathVariable UUID noteId,
            @RequestParam Map<String, String> query) {
        if (!Set.of("limit", "cursor").containsAll(query.keySet())) {
            throw ApiFailureException.of(ApiFailureException.Kind.MALFORMED_REQUEST);
        }
        Integer limit = null;
        if (query.containsKey("limit")) {
            try { limit = Integer.valueOf(query.get("limit")); }
            catch (NumberFormatException failure) { throw ApiFailureException.of(ApiFailureException.Kind.MALFORMED_REQUEST); }
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(versions.list(NotesActor.owner(), noteId, limit, query.get("cursor")));
    }

    @GetMapping("/api/notes/{noteId}/versions/{versionId}")
    ResponseEntity<NoteVersionRecord> read(@PathVariable UUID noteId, @PathVariable UUID versionId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(versions.read(NotesActor.owner(), noteId, versionId));
    }

    @PostMapping("/api/notes/{noteId}/versions/{versionId}/restore")
    ResponseEntity<NoteRecord.NoteView> restore(@PathVariable UUID noteId, @PathVariable UUID versionId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestBody(required = false) Map<String, Object> body) {
        if (body == null || !body.keySet().equals(Set.of("confirmRestore")) || !Boolean.TRUE.equals(body.get("confirmRestore"))) {
            throw ApiFailureException.of(ApiFailureException.Kind.INVALID_INPUT);
        }
        var restored = versions.restore(NotesActor.owner(), noteId, versionId, ifMatch);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag(restored.etag()).body(restored.note());
    }
}
