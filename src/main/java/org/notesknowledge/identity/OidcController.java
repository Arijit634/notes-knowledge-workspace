package org.notesknowledge.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.net.URI;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@IdentityCoreEnabled
@RequestMapping("/api/auth")
final class OidcController {
    private final OidcFlowService flows;

    OidcController(OidcFlowService flows) {
        this.flows = flows;
    }

    @PostMapping("/oidc/google/authorizations")
    ResponseEntity<Map<String, String>> beginLogin(HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Map.of("authorizationUrl", flows.begin(OidcProtocolPort.Action.LOGIN, request)));
    }

    @GetMapping("/oidc/google/callback")
    ResponseEntity<Void> login(@RequestParam(required = false) String state,
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String iss,
            @RequestParam(required = false) String error,
            HttpServletRequest request, HttpServletResponse response) {
        flows.complete(OidcProtocolPort.Action.LOGIN, state, iss,
                error == null ? code : null, request, response);
        return ResponseEntity.status(303).location(URI.create("/auth/complete"))
                .cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping("/reauth/oidc/google/authorizations")
    ResponseEntity<Map<String, String>> beginRecent(HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Map.of("authorizationUrl", flows.begin(OidcProtocolPort.Action.RECENT_AUTH, request)));
    }

    @GetMapping("/reauth/oidc/google/callback")
    ResponseEntity<Void> recent(@RequestParam(required = false) String state,
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String iss,
            @RequestParam(required = false) String error,
            HttpServletRequest request, HttpServletResponse response) {
        flows.complete(OidcProtocolPort.Action.RECENT_AUTH, state, iss,
                error == null ? code : null, request, response);
        return ResponseEntity.status(303).location(URI.create("/auth/complete"))
                .cacheControl(CacheControl.noStore()).build();
    }

    @GetMapping("/oidc/google/link-callback")
    ResponseEntity<Void> completeLink(@RequestParam(required = false) String state,
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String iss,
            @RequestParam(required = false) String error,
            HttpServletRequest request, HttpServletResponse response) {
        flows.complete(OidcProtocolPort.Action.LINK, state, iss,
                error == null ? code : null, request, response);
        return ResponseEntity.status(303).location(URI.create("/settings/security"))
                .cacheControl(CacheControl.noStore()).build();
    }
}
