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
    private final io.micrometer.core.instrument.MeterRegistry meters;
    PrivateSearchController(PrivateNoteSearchQuery search,ObjectMapper json){this(search,json,null);}
    @org.springframework.beans.factory.annotation.Autowired
    PrivateSearchController(PrivateNoteSearchQuery search,ObjectMapper json,io.micrometer.core.instrument.MeterRegistry meters){this.search=search;this.json=json;this.meters=meters;}

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
        long started=System.nanoTime();var outcome=org.notesknowledge.BackendTelemetry.Outcome.UNAVAILABLE;
        try {
            var result=search.search(owner,request);outcome=org.notesknowledge.BackendTelemetry.Outcome.SUCCESS;
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
        } catch (DataAccessException | TransactionException failure) {
            // Do not retain database diagnostics: they may contain private bound values.
            throw ApiFailureException.of(ApiFailureException.Kind.SERVICE_UNAVAILABLE);
        }finally{org.notesknowledge.BackendTelemetry.record(meters,org.notesknowledge.BackendTelemetry.Event.RETRIEVAL,
            org.notesknowledge.BackendTelemetry.Operation.ORDINARY,outcome,System.nanoTime()-started);}
    }
}
