package org.notesknowledge.knowledge;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
final class SearchHistoryController {
    private final KnowledgeOperationService operations;
    private final SearchHistoryService history;
    private final org.notesknowledge.websupport.ApiProblemWriter problems;
    private final org.notesknowledge.security.RateControlService rates;
    private final org.notesknowledge.security.RateKeyDeriver keys;
    SearchHistoryController(KnowledgeOperationService operations,SearchHistoryService history,org.notesknowledge.websupport.ApiProblemWriter problems,
            org.notesknowledge.security.RateControlService rates,org.notesknowledge.security.RateKeyDeriver keys){this.operations=operations;this.history=history;this.problems=problems;this.rates=rates;this.keys=keys;}
    private UUID owner(HttpServletRequest browser){var owner=operations.browserOwner(browser);String control="KNOWLEDGE_STATUS";
        rates.check(new org.notesknowledge.security.RateLimitPort.Request(new org.notesknowledge.security.RateLimitPort.ControlClass(control),keys.derive(control,"subject:"+owner),1),org.notesknowledge.security.RateControlService.Policy.SECURITY_CRITICAL);return owner;}
    @GetMapping("/api/knowledge/history")
    ResponseEntity<?> list(HttpServletRequest browser,@RequestParam(required=false)String cursor,@RequestParam(defaultValue="20")int limit){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(history.list(owner(browser),cursor,limit));}
    @GetMapping("/api/knowledge/history/{historyId}")
    ResponseEntity<?> detail(HttpServletRequest browser,@PathVariable UUID historyId){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(history.detail(owner(browser),historyId));}
    @DeleteMapping("/api/knowledge/history/{historyId}")
    ResponseEntity<Void> remove(HttpServletRequest browser,@PathVariable UUID historyId){history.remove(owner(browser),historyId);return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();}
    @DeleteMapping("/api/knowledge/history")
    ResponseEntity<Void> clear(HttpServletRequest browser){history.clear(owner(browser));return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();}
    @ExceptionHandler({org.springframework.dao.DataAccessException.class,org.springframework.transaction.TransactionException.class,DerivationFailure.class})
    ResponseEntity<org.springframework.http.ProblemDetail> unavailable(HttpServletRequest browser){return ResponseEntity.status(503).contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON).cacheControl(CacheControl.noStore())
        .body(problems.create(browser,org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"service_unavailable","Service temporarily unavailable"));}
}
