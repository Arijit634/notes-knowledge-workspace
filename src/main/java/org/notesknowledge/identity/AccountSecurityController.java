package org.notesknowledge.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@IdentityCoreEnabled
@RequestMapping("/api/me/security")
final class AccountSecurityController {
    private final SecuritySummaryQuery summary;
    private final CredentialManagementService credentials;
    private final MfaRateControl rates;
    private final MfaProperties policy;
    private final Clock clock;

    AccountSecurityController(SecuritySummaryQuery summary,
            CredentialManagementService credentials, MfaRateControl rates,
            MfaProperties policy, Clock clock) {
        this.summary = summary;
        this.credentials = credentials;
        this.rates = rates;
        this.policy = policy;
        this.clock = clock;
    }

    @GetMapping
    ResponseEntity<SecuritySummaryQuery.Summary> securitySummary() {
        UUID userId = IdentitySessionState.principal("ROLE_USER");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(summary.forOwner(userId));
    }

    @PutMapping("/password")
    ResponseEntity<Void> setPassword(@RequestBody Map<String, Object> input,
            HttpServletRequest request, HttpServletResponse response) {
        UUID userId = IdentitySessionState.principal("ROLE_USER");
        rates.check("PASSWORD_CHANGE", userId, request);
        IdentitySessionState.requireRecent(request, userId, clock.instant(), policy);
        if (input == null || !input.keySet().equals(Set.of("newPassword"))
                || !(input.get("newPassword") instanceof String password)) {
            throw ApiFailureException.of(ApiFailureException.Kind.INVALID_INPUT);
        }
        credentials.setPassword(userId, password, request, response);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
