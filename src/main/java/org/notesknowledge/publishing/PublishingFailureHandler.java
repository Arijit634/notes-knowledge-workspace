package org.notesknowledge.publishing;

import jakarta.servlet.http.HttpServletRequest;
import org.notesknowledge.websupport.ApiProblemWriter;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.*;

@Order(-1)
@RestControllerAdvice(assignableTypes=PublicationController.class)
class PublishingFailureHandler {
    private final ApiProblemWriter writer;
    PublishingFailureHandler(ApiProblemWriter writer){this.writer=writer;}
    @ExceptionHandler({DataAccessException.class,TransactionException.class})
    ResponseEntity<ProblemDetail> unavailable(HttpServletRequest request) {
        return ResponseEntity.status(503).contentType(MediaType.APPLICATION_PROBLEM_JSON).cacheControl(CacheControl.noStore())
            .body(writer.create(request,HttpStatus.SERVICE_UNAVAILABLE,"service_unavailable","Required service is unavailable"));
    }
}
