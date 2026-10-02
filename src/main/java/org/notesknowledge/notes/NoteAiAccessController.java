package org.notesknowledge.notes;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** AI permission commands do not save editor content or start AI processing. */
@RestController
class NoteAiAccessController {
    private final NoteAiAccessService service;

    NoteAiAccessController(NoteAiAccessService service) { this.service = service; }

    @PutMapping("/api/notes/{noteId}/ai-access")
    ResponseEntity<NoteRecord.NoteView> set(@PathVariable UUID noteId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestBody AiAccessRequest request) {
        var result = service.set(NotesActor.owner(), noteId, ifMatch, booleanValue(request.aiEnabled()));
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .eTag(result.etag()).body(result.note());
    }

    @PostMapping("/api/notes/ai-access-bulk")
    ResponseEntity<BulkResult> bulk(@RequestBody BulkInput input, HttpServletRequest http) {
        BulkRequest request = input.validated();
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(new BulkResult(service.bulk(NotesActor.owner(), request, http.getRemoteAddr())));
    }

    record BulkResult(long affectedCount) { }

    // Literal nodes deliberately prevent Jackson's string/number-to-boolean coercion.
    // Unknown DTO fields are rejected by the application's strict JSON configuration.
    record AiAccessRequest(JsonNode aiEnabled) { }

    enum Scope { SELECTED, ALL_EXISTING }

    record BulkRequest(boolean aiEnabled, Scope scope, List<UUID> noteIds) {
        BulkRequest { noteIds = List.copyOf(noteIds); }
    }

    record BulkInput(JsonNode aiEnabled, JsonNode scope, JsonNode confirm, List<JsonNode> noteIds) {
        static final int MAX_SELECTED = 1000;

        BulkRequest validated() {
            if (!booleanValue(confirm)) throw invalid();
            boolean enabled = booleanValue(aiEnabled);
            if (scope == null || !scope.isString()) throw invalid();
            if ("allExisting".equals(scope.asText()) && noteIds == null) {
                return new BulkRequest(enabled, Scope.ALL_EXISTING, List.of());
            }
            if (!"selected".equals(scope.asText()) || noteIds == null || noteIds.isEmpty()) throw invalid();
            if (noteIds.size() > MAX_SELECTED) {
                throw ApiFailureException.of(ApiFailureException.Kind.REQUEST_TOO_LARGE);
            }
            var ids = noteIds.stream().map(value -> {
                if (value == null || !value.isString()) throw invalid();
                String text = value.asText();
                try {
                    UUID id = UUID.fromString(text);
                    if (!id.toString().equalsIgnoreCase(text)) throw invalid();
                    return id;
                } catch (IllegalArgumentException exception) { throw invalid(); }
            }).toList();
            if (Set.copyOf(ids).size() != ids.size()) throw invalid();
            return new BulkRequest(enabled, Scope.SELECTED, ids);
        }
    }

    private static boolean booleanValue(JsonNode value) {
        if (value == null || !value.isBoolean()) throw invalid();
        return value.asBoolean();
    }

    private static ApiFailureException invalid() {
        return ApiFailureException.of(ApiFailureException.Kind.INVALID_INPUT);
    }
}
