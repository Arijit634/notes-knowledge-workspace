package org.notesknowledge.profile;

import jakarta.servlet.http.HttpServletRequest;
import org.notesknowledge.websupport.ApiProblemWriter;
import org.postgresql.util.PSQLException;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.*;

@Order(-1)
@RestControllerAdvice(assignableTypes=PublicProfileController.class)
class PublicProfileFailureHandler {
    private final ApiProblemWriter writer;
    PublicProfileFailureHandler(ApiProblemWriter writer){this.writer=writer;}
    @ExceptionHandler({DataAccessException.class,TransactionException.class})
    ResponseEntity<ProblemDetail> failure(Exception exception,HttpServletRequest request) {
        for(Throwable cause=exception;cause!=null;cause=cause.getCause()) {
            if(cause instanceof PSQLException pg&&"23505".equals(pg.getSQLState())&&pg.getServerErrorMessage()!=null
                &&"ux_public_profile_active_handle".equals(pg.getServerErrorMessage().getConstraint())) {
                return response(request,HttpStatus.CONFLICT,"profile_handle_unavailable","Public handle is unavailable");
            }
        }
        return response(request,HttpStatus.SERVICE_UNAVAILABLE,"service_unavailable","Required service is unavailable");
    }
    private ResponseEntity<ProblemDetail> response(HttpServletRequest request,HttpStatus status,String code,String title) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).cacheControl(CacheControl.noStore()).body(writer.create(request,status,code,title));
    }
}
