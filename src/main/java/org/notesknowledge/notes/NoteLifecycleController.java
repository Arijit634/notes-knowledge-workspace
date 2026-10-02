package org.notesknowledge.notes;

import java.util.Map;
import java.util.UUID;

import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
final class NoteLifecycleController {
    private final NoteOrganizationService commands;
    private final NoteTrashService trash;

    NoteLifecycleController(NoteOrganizationService commands, NoteTrashService trash) {
        this.commands = commands;
        this.trash = trash;
    }

    @PostMapping("/api/notes/{noteId}/trash")
    ResponseEntity<NoteRecord.NoteView> trash(@PathVariable UUID noteId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestBody(required = false) Map<String, Object> body) {
        if (body != null && (body.keySet().stream().anyMatch(key -> !key.equals("confirmPublicationUnpublish"))
                || (body.containsKey("confirmPublicationUnpublish")
                    && !(body.get("confirmPublicationUnpublish") instanceof Boolean)))) {
            throw ApiFailureException.of(ApiFailureException.Kind.MALFORMED_REQUEST);
        }
        var result = trash.trash(NotesActor.owner(), noteId, ifMatch,
                body != null && Boolean.TRUE.equals(body.get("confirmPublicationUnpublish")));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .eTag(result.etag()).body(result.note());
    }

    @PostMapping("/api/notes/{noteId}/restore")
    ResponseEntity<NoteRecord.NoteView> restore(@PathVariable UUID noteId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestBody(required = false) Map<String, Object> body) {
        if (body != null && !body.isEmpty()) {
            throw ApiFailureException.of(ApiFailureException.Kind.MALFORMED_REQUEST);
        }
        var result = trash.restore(NotesActor.owner(), noteId, ifMatch);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .eTag(result.etag()).body(result.note());
    }

    @PutMapping("/api/notes/{noteId}/pin")
    ResponseEntity<NoteRecord.NoteView> pin(@PathVariable UUID noteId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestBody(required = false) Map<String, Object> body) {
        return execute(noteId, ifMatch, body, NoteOrganizationService.Command.PIN);
    }

    @DeleteMapping("/api/notes/{noteId}/pin")
    ResponseEntity<NoteRecord.NoteView> unpin(@PathVariable UUID noteId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestBody(required = false) Map<String, Object> body) {
        return execute(noteId, ifMatch, body, NoteOrganizationService.Command.UNPIN);
    }

    @PostMapping("/api/notes/{noteId}/archive")
    ResponseEntity<NoteRecord.NoteView> archive(@PathVariable UUID noteId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestBody(required = false) Map<String, Object> body) {
        return execute(noteId, ifMatch, body, NoteOrganizationService.Command.ARCHIVE);
    }

    @PostMapping("/api/notes/{noteId}/return-from-archive")
    ResponseEntity<NoteRecord.NoteView> returnFromArchive(@PathVariable UUID noteId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestBody(required = false) Map<String, Object> body) {
        return execute(noteId, ifMatch, body, NoteOrganizationService.Command.RETURN_FROM_ARCHIVE);
    }

    private ResponseEntity<NoteRecord.NoteView> execute(UUID id, String ifMatch,
            Map<String, Object> body, NoteOrganizationService.Command command) {
        if (body != null && !body.isEmpty()) {
            throw ApiFailureException.of(ApiFailureException.Kind.MALFORMED_REQUEST);
        }
        var result = commands.execute(NotesActor.owner(), id, ifMatch, command);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .eTag(result.etag()).body(result.note());
    }
}
