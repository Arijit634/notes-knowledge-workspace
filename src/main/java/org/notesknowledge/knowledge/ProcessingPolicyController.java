package org.notesknowledge.knowledge;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@RestController
final class ProcessingPolicyController {
    private final ProcessingPolicyService policies;
    private final ObjectMapper json;
    ProcessingPolicyController(ProcessingPolicyService policies,ObjectMapper json) { this.policies=policies;this.json=json; }
    @GetMapping("/api/ai/processing-policy")
    ResponseEntity<ProcessingPolicyService.View> read(HttpServletRequest browser) {
        try { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(policies.read(browser)); }
        catch(DataAccessException|TransactionException unavailable) { throw ApiFailureException.of(ApiFailureException.Kind.SERVICE_UNAVAILABLE); }
    }
    @PostMapping(value="/api/ai/processing-policy/acknowledgements",consumes="application/json")
    @SuppressWarnings("unchecked")
    ResponseEntity<Void> acknowledge(HttpServletRequest browser) throws IOException {
        byte[] bytes=browser.getInputStream().readNBytes(4097);
        if(bytes.length>4096) throw ApiFailureException.of(ApiFailureException.Kind.REQUEST_TOO_LARGE);
        Map<String,Object> input;
        try { input=json.readValue(bytes,Map.class); }
        catch(JacksonException malformed) { throw ApiFailureException.of(ApiFailureException.Kind.MALFORMED_REQUEST); }
        var request=PolicyAcknowledgementRequest.decode(input);
        try { policies.acknowledge(browser,request);return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build(); }
        catch(DataAccessException|TransactionException unavailable) { throw ApiFailureException.of(ApiFailureException.Kind.SERVICE_UNAVAILABLE); }
    }
}
