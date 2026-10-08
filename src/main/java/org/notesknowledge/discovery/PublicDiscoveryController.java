package org.notesknowledge.discovery;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.notesknowledge.identity.IdentitySessionPrincipal;
import org.notesknowledge.security.*;
import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@RestController
class PublicDiscoveryController {
    private final PublicDiscoveryService service;
    private final PublicReadRateControl rate;
    PublicDiscoveryController(PublicDiscoveryService service,PublicReadRateControl rate){this.service=service;this.rate=rate;}
    @GetMapping("/api/public/explore")
    ResponseEntity<PublicDiscoveryService.Page> explore(@RequestParam(defaultValue="latest") String sort,@RequestParam(required=false) Integer limit,
        @RequestParam(required=false) String cursor,HttpServletRequest request){allow(request,Set.of("sort","limit","cursor"));rate.search(request);return ok(service.page(sort,null,null,limit,cursor,false));}
    @GetMapping("/api/public/search")
    ResponseEntity<PublicDiscoveryService.Page> search(@RequestParam(required=false) String q,@RequestParam(required=false) String tag,
        @RequestParam(required=false) Integer limit,@RequestParam(required=false) String cursor,HttpServletRequest request) {
        allow(request,Set.of("q","tag","limit","cursor"));rate.search(request);return ok(service.page("latest",q,tag,limit,cursor,true));
    }
    @PutMapping("/api/public/publications/{id}/like")
    ResponseEntity<Void> like(@PathVariable UUID id,HttpServletRequest request){return mutate(id,true,request);}
    @DeleteMapping("/api/public/publications/{id}/like")
    ResponseEntity<Void> unlike(@PathVariable UUID id,HttpServletRequest request){return mutate(id,false,request);}
    private ResponseEntity<Void> mutate(UUID id,boolean desired,HttpServletRequest request) {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth==null||!(auth.getPrincipal() instanceof IdentitySessionPrincipal p)||auth.getAuthorities().stream().noneMatch(a->a.getAuthority().equals("ROLE_USER")))
            throw ApiFailureException.of(ApiFailureException.Kind.INVALID_CREDENTIALS);
        rate.like(request,p.userId());service.like(p.userId(),id,desired,request);
        return ResponseEntity.noContent().header("Cache-Control","no-store").build();
    }
    private static void allow(HttpServletRequest request,Set<String> names) {
        if(request.getParameterMap().entrySet().stream().anyMatch(e->!names.contains(e.getKey())||e.getValue().length!=1))throw ApiFailureException.of(ApiFailureException.Kind.MALFORMED_REQUEST);
    }
    private static ResponseEntity<PublicDiscoveryService.Page> ok(PublicDiscoveryService.Page page){return ResponseEntity.ok().header("Cache-Control","no-store").body(page);}
}
