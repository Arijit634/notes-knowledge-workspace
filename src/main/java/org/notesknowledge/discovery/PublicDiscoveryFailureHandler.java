package org.notesknowledge.discovery;

import jakarta.servlet.http.HttpServletRequest;
import org.notesknowledge.websupport.ApiProblemWriter;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@Order(-1)
@RestControllerAdvice(assignableTypes=PublicDiscoveryController.class)
class PublicDiscoveryFailureHandler {
    private final ApiProblemWriter writer;
    PublicDiscoveryFailureHandler(ApiProblemWriter writer){this.writer=writer;}
    @ExceptionHandler({org.springframework.dao.DataAccessException.class,org.springframework.transaction.TransactionException.class})
    ResponseEntity<ProblemDetail> unavailable(HttpServletRequest request){return ResponseEntity.status(503).contentType(MediaType.APPLICATION_PROBLEM_JSON).cacheControl(CacheControl.noStore())
        .body(writer.create(request,HttpStatus.SERVICE_UNAVAILABLE,"service_unavailable","Required service is unavailable"));}
}
