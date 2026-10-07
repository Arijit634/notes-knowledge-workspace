package org.notesknowledge.notes;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
final class NoteTagController {
    private final ReplaceTagsService tags;

    NoteTagController(ReplaceTagsService tags) { this.tags = tags; }

    @PutMapping("/api/notes/{noteId}/tags")
    ResponseEntity<NoteRecord.NoteView> replace(@PathVariable UUID noteId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestBody Map<String, Object> input) {
        if (input == null || !Set.of("tags","acceptedSuggestionId").containsAll(input.keySet())
                || input.containsKey("acceptedSuggestionId")&&!(input.get("acceptedSuggestionId") instanceof String)
                || !(input.get("tags") instanceof List<?> values)
                || values.stream().anyMatch(value -> !(value instanceof String))) {
            throw ApiFailureException.of(ApiFailureException.Kind.INVALID_INPUT);
        }
        UUID suggestion=null;
        if(input.containsKey("acceptedSuggestionId"))try {suggestion=UUID.fromString((String)input.get("acceptedSuggestionId"));}
            catch(IllegalArgumentException malformed){throw ApiFailureException.of(ApiFailureException.Kind.INVALID_INPUT);}
        var result = tags.replace(NotesActor.owner(), noteId, ifMatch,
                values.stream().map(String.class::cast).toList(),suggestion);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .eTag(result.etag()).body(result.note());
    }
}
