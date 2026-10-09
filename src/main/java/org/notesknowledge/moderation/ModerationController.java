package org.notesknowledge.moderation;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URI;
import java.util.Set;
import java.util.UUID;
import org.notesknowledge.identity.IdentitySessionPrincipal;
import org.notesknowledge.security.ModerationRateControl;
import org.notesknowledge.websupport.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;
import static org.notesknowledge.moderation.ModerationContract.*;

@RestController
class ModerationController {
    private final ModerationTransactions transactions;
    private final ModerationService service;
    private final ModerationRateControl rates;
    private final ObjectMapper json;
    ModerationController(ModerationTransactions transactions,ModerationService service,ModerationRateControl rates,ObjectMapper json){this.transactions=transactions;this.service=service;this.rates=rates;this.json=json;}
    @PostMapping(value="/api/public/publications/{publicationId}/reports",consumes="application/json")
    ResponseEntity<Receipt> submit(@PathVariable UUID publicationId,HttpServletRequest request)throws IOException {
        UUID actor=actor();rates.intake(request,actor);allow(request,Set.of());
        var result=transactions.submit(actor,publicationId,body(request,Intake.class,Set.of("category","description")),request);
        // There is deliberately no reporter-accessible Report GET resource.
        return ResponseEntity.created(URI.create("/api/moderation/reports/"+result.id())).header("Cache-Control","no-store").body(result);
    }
    @GetMapping("/api/moderation/reports")
    ResponseEntity<CursorPage<Report>> queue(@RequestParam(required=false) String state,@RequestParam(required=false) String category,
        @RequestParam(defaultValue="submittedDesc") String sort,@RequestParam(required=false) Integer limit,@RequestParam(required=false) String cursor,HttpServletRequest request) {
        UUID actor=actor();rates.review(request,actor,false);allow(request,Set.of("state","category","sort","limit","cursor"));return ok(transactions.queue(actor,state,category,sort,limit,cursor,request));
    }
    @GetMapping("/api/moderation/reports/{reportId}")
    ResponseEntity<Detail> detail(@PathVariable UUID reportId,HttpServletRequest request){UUID actor=actor();rates.review(request,actor,false);allow(request,Set.of());return ok(transactions.detail(actor,reportId,request));}
    @PostMapping("/api/moderation/reports/{reportId}/begin-review")
    ResponseEntity<Detail> begin(@PathVariable UUID reportId,HttpServletRequest request){UUID actor=actor();rates.review(request,actor,true);allow(request,Set.of());return ok(transactions.begin(actor,reportId,request));}
    @PostMapping(value="/api/moderation/reports/{reportId}/decisions",consumes="application/json")
    ResponseEntity<Decision> decide(@PathVariable UUID reportId,HttpServletRequest request)throws IOException {
        UUID actor=actor();rates.review(request,actor,true);allow(request,Set.of());
        var result=service.decide(actor,reportId,body(request,DecisionRequest.class,Set.of("consequence","reasonCode")),request);
        return ResponseEntity.created(URI.create("/api/moderation/reports/"+reportId)).header("Cache-Control","no-store").body(result);
    }
    private <T>T body(HttpServletRequest request,Class<T> type,Set<String> allowed)throws IOException {
        // Bounded before JSON parsing, including chunked bodies and unknown oversized properties.
        byte[] bytes=request.getInputStream().readNBytes(8193);
        if(bytes.length>8192)throw ApiFailureException.of(ApiFailureException.Kind.REQUEST_TOO_LARGE);
        try{var tree=json.readTree(bytes);if(tree==null||!tree.isObject()||tree.properties().stream().anyMatch(e->!allowed.contains(e.getKey())))throw ApiFailureException.of(ApiFailureException.Kind.MALFORMED_REQUEST);
            return json.treeToValue(tree,type);
        }catch(tools.jackson.core.JacksonException invalid){throw ApiFailureException.of(ApiFailureException.Kind.MALFORMED_REQUEST);}
    }
    private static UUID actor(){var a=SecurityContextHolder.getContext().getAuthentication();if(a==null||!(a.getPrincipal() instanceof IdentitySessionPrincipal p)
        ||a.getAuthorities().stream().noneMatch(x->x.getAuthority().equals("ROLE_USER")))throw ApiFailureException.of(ApiFailureException.Kind.INVALID_CREDENTIALS);return p.userId();}
    private static void allow(HttpServletRequest request,Set<String> allowed){if(request.getParameterMap().entrySet().stream().anyMatch(e->!allowed.contains(e.getKey())||e.getValue().length!=1))throw ApiFailureException.of(ApiFailureException.Kind.MALFORMED_REQUEST);}
    private static <T>ResponseEntity<T> ok(T value){return ResponseEntity.ok().header("Cache-Control","no-store").body(value);}
}
