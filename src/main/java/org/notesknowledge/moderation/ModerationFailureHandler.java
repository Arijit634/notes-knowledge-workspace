package org.notesknowledge.moderation;

import jakarta.servlet.http.*;
import java.io.IOException;
import org.notesknowledge.websupport.ApiProblemWriter;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

/** Preserve safe typed failures at the module boundary; never expose database diagnostics. */
@Order(0)
@RestControllerAdvice(assignableTypes=ModerationController.class)
class ModerationFailureHandler {
    private final ApiProblemWriter problems;
    private final ModerationDeniedAudit audit;
    ModerationFailureHandler(ApiProblemWriter problems,ModerationDeniedAudit audit){this.problems=problems;this.audit=audit;}
    @ExceptionHandler(AccessDeniedException.class)
    void denied(AccessDeniedException denied,HttpServletRequest request,HttpServletResponse response)throws IOException {
        var auth=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        var match=java.util.regex.Pattern.compile("^/api/moderation/reports/([0-9a-fA-F-]{36})(?:/begin-review|/decisions)?$").matcher(request.getRequestURI());
        if(auth!=null&&auth.getPrincipal() instanceof org.notesknowledge.identity.IdentitySessionPrincipal actor&&match.matches()) {
            try{audit.record(actor.userId(),java.util.UUID.fromString(match.group(1)));}
            catch(DataAccessException unavailable){unavailable(request,response);return;}
        }
        response.setHeader("Cache-Control","no-store");problems.writeAccessDenied(request,response,denied);
    }
    @ExceptionHandler(DataAccessException.class)
    void unavailable(HttpServletRequest request,HttpServletResponse response)throws IOException {
        org.slf4j.LoggerFactory.getLogger(ModerationFailureHandler.class).warn("moderation_consistency_unavailable");
        response.setHeader("Cache-Control","no-store");problems.writeServiceUnavailable(request,response);
    }
}
