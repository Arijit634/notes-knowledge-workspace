package org.notesknowledge.knowledge;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
final class KnowledgeQueryController {
    private final KnowledgeOperationService operations;
    private final KnowledgeQueryEngine engine;
    private final NoteKnowledgeService notes;
    private final io.micrometer.core.instrument.MeterRegistry metrics;
    private final org.notesknowledge.security.RateControlService rates;
    private final org.notesknowledge.security.RateKeyDeriver keys;
    private final tools.jackson.databind.ObjectMapper json;
    private final org.notesknowledge.websupport.ApiProblemWriter problems;
    KnowledgeQueryController(KnowledgeOperationService operations,KnowledgeQueryEngine engine,NoteKnowledgeService notes,io.micrometer.core.instrument.MeterRegistry metrics,
        org.notesknowledge.security.RateControlService rates,org.notesknowledge.security.RateKeyDeriver keys,tools.jackson.databind.ObjectMapper json,org.notesknowledge.websupport.ApiProblemWriter problems){this.operations=operations;this.engine=engine;this.notes=notes;this.metrics=metrics;this.rates=rates;this.keys=keys;this.json=json;this.problems=problems;}
    @PostMapping(value="/api/knowledge/query",consumes="application/json")
    @SuppressWarnings("unchecked")
    ResponseEntity<?> query(HttpServletRequest browser)throws java.io.IOException {
        UUID owner=operations.browserOwner(browser);rate(owner,false);
        byte[] bytes=browser.getInputStream().readNBytes(16385);if(bytes.length>16384)throw ApiFailureException.of(ApiFailureException.Kind.REQUEST_TOO_LARGE);
        Map<String,Object> input;try{input=json.readValue(bytes,Map.class);}catch(tools.jackson.core.JacksonException malformed){throw ApiFailureException.of(ApiFailureException.Kind.MALFORMED_REQUEST);}
        var request=KnowledgeQueryRequest.parse(input);
        metrics.counter("notes.workspace.retrieval.query","queryClass",request.plan().name()).increment();
        if(request.plan()==KnowledgeQueryRequest.Plan.SEMANTIC_CORPUS||request.plan()==KnowledgeQueryRequest.Plan.DETERMINISTIC_CORPUS){var accepted=operations.accept(owner,request,browser);return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).location(URI.create("/api/knowledge/operations/"+accepted.operationId())).body(accepted);}
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(engine.ranked(owner,request).result());
    }
    @GetMapping("/api/knowledge/operations/{operationId}")
    ResponseEntity<?> poll(HttpServletRequest browser,@PathVariable String operationId){rate(operations.browserOwner(browser),true);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(operations.poll(browser,operationId));}
    @DeleteMapping("/api/knowledge/operations/{operationId}")
    ResponseEntity<Void> cancel(HttpServletRequest browser,@PathVariable String operationId){operations.cancel(browser,operationId);return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();}
    @PostMapping("/api/notes/{noteId}/related")
    ResponseEntity<?> related(HttpServletRequest browser,@PathVariable UUID noteId,@RequestHeader(value="If-Match",required=false)String ifMatch,@RequestBody(required=false)Map<String,Object> input){empty(input);var owner=operations.browserOwner(browser);rate(owner,false);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("items",notes.related(owner,noteId,ifMatch)));}
    @PostMapping("/api/notes/{noteId}/organization-suggestions")
    ResponseEntity<?> suggestions(HttpServletRequest browser,@PathVariable UUID noteId,@RequestHeader(value="If-Match",required=false)String ifMatch,@RequestBody(required=false)Map<String,Object> input){empty(input);var owner=operations.browserOwner(browser);rate(owner,false);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(notes.suggest(owner,noteId,ifMatch));}
    private void rate(UUID owner,boolean polling){String control=polling?"KNOWLEDGE_STATUS":"KNOWLEDGE_QUERY";
        rates.check(new org.notesknowledge.security.RateLimitPort.Request(new org.notesknowledge.security.RateLimitPort.ControlClass(control),keys.derive(control,"subject:"+owner),1),org.notesknowledge.security.RateControlService.Policy.SECURITY_CRITICAL);
        if(!polling)rates.check(new org.notesknowledge.security.RateLimitPort.Request(new org.notesknowledge.security.RateLimitPort.ControlClass("KNOWLEDGE_QUERY_GLOBAL"),keys.derive("KNOWLEDGE_QUERY_GLOBAL","aggregate"),1),org.notesknowledge.security.RateControlService.Policy.SECURITY_CRITICAL);
    }
    private static void empty(Map<String,Object> input){if(input!=null&&!input.isEmpty())throw ApiFailureException.of(ApiFailureException.Kind.INVALID_INPUT);}
    @ExceptionHandler({org.springframework.dao.DataAccessException.class,org.springframework.transaction.TransactionException.class,DerivationFailure.class})
    ResponseEntity<org.springframework.http.ProblemDetail> unavailable(HttpServletRequest browser){
        return ResponseEntity.status(503).contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON).cacheControl(CacheControl.noStore())
            .body(problems.create(browser,org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"service_unavailable","Service temporarily unavailable"));
    }
}
