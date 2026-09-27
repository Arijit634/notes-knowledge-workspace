package org.notesknowledge.identity;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@IdentityCoreEnabled
@RequestMapping("/api/me/security/sessions")
final class SessionController {
    private final SessionManagementService management;
    private final MfaRateControl rates;

    SessionController(SessionManagementService management, MfaRateControl rates) {
        this.management = management;
        this.rates = rates;
    }

    @GetMapping
    ResponseEntity<SessionManagementService.SessionList> list(HttpServletRequest request) {
        var userId = IdentitySessionState.principal("ROLE_USER");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(management.list(userId, request));
    }

    @DeleteMapping("/{sessionHandle}")
    ResponseEntity<Void> revokeOne(@PathVariable String sessionHandle, HttpServletRequest request) {
        var userId = IdentitySessionState.principal("ROLE_USER");
        rates.check("SESSION_REVOKE_ONE", userId, request);
        management.revokeOne(userId, sessionHandle, request);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping("/revoke-others")
    ResponseEntity<Void> revokeOthers(HttpServletRequest request) {
        var userId = IdentitySessionState.principal("ROLE_USER");
        rates.check("SESSION_REVOKE_OTHERS", userId, request);
        management.revokeOthers(userId, request);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping("/revoke-all")
    ResponseEntity<Void> revokeAll(HttpServletRequest request) {
        var userId = IdentitySessionState.principal("ROLE_USER");
        rates.check("SESSION_REVOKE_ALL", userId, request);
        management.revokeAll(userId, request);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
