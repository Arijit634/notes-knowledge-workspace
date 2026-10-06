package org.notesknowledge.knowledge;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.notesknowledge.identity.IdentitySessionPrincipal;
import org.notesknowledge.security.RateControlService;
import org.notesknowledge.security.RateKeyDeriver;
import org.notesknowledge.security.RateLimitPort;
import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
class AiProcessingStatusController {
    private final AiProcessingStatusService status;
    private final RateControlService rates;
    private final RateKeyDeriver keys;
    AiProcessingStatusController(AiProcessingStatusService status,RateControlService rates,RateKeyDeriver keys) {this.status=status;this.rates=rates;this.keys=keys;}
    @GetMapping("/api/notes/{noteId}/ai-processing")
    ResponseEntity<AiProcessingStatusService.View> read(@PathVariable UUID noteId,HttpServletRequest browser) {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth==null||!(auth.getPrincipal() instanceof IdentitySessionPrincipal p))throw ApiFailureException.of(ApiFailureException.Kind.INVALID_CREDENTIALS);
        var control=new RateLimitPort.ControlClass("KNOWLEDGE_STATUS");
        rates.check(new RateLimitPort.Request(control,keys.derive(control.value(),"subject:"+p.userId()),1),RateControlService.Policy.SECURITY_CRITICAL);
        try {return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(status.read(browser,noteId));}
        catch(org.springframework.dao.DataAccessException|org.springframework.transaction.TransactionException unavailable) {
            throw ApiFailureException.of(ApiFailureException.Kind.SERVICE_UNAVAILABLE);
        }
    }
}
