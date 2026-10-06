package org.notesknowledge.notes;

import java.util.Map;
import java.io.IOException;
import jakarta.servlet.http.HttpServletRequest;
import org.notesknowledge.websupport.ApiFailureException;
import org.notesknowledge.websupport.CursorPage;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@RestController
final class PrivateSearchController {
    private final PrivateNoteSearchQuery search;
    private final ObjectMapper json;
    PrivateSearchController(PrivateNoteSearchQuery search, ObjectMapper json) { this.search = search; this.json = json; }

    @PostMapping(value = "/api/notes/search", consumes = "application/json")
    @SuppressWarnings("unchecked")
    ResponseEntity<CursorPage<NoteSearchResult>> search(HttpServletRequest browser) throws IOException {
        var owner = NotesActor.owner();
        // Bound bytes before JSON parsing, including chunked requests without Content-Length.
        byte[] bytes = browser.getInputStream().readNBytes(8193);
        if (bytes.length > 8192) throw ApiFailureException.of(ApiFailureException.Kind.REQUEST_TOO_LARGE);
        Map<String, Object> input;
        try { input = json.readValue(bytes, Map.class); }
        catch (JacksonException failure) { throw ApiFailureException.of(ApiFailureException.Kind.MALFORMED_REQUEST); }
        var request = PrivateSearchRequest.decode(input);
        try {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(search.search(owner, request));
        } catch (DataAccessException | TransactionException failure) {
            // Do not retain database diagnostics: they may contain private bound values.
            throw ApiFailureException.of(ApiFailureException.Kind.SERVICE_UNAVAILABLE);
        }
    }
}
