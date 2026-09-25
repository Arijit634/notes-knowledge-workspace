package org.notesknowledge.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@IdentityCoreEnabled
final class MfaController {
    record TotpInput(String code) { }
    record RecoveryInput(String code) { }
    private final MfaChallengeService challenges;
    private final MfaManagementService management;
    private final MfaSessionTransitions sessions;
    private final MfaRateControl rates;
    private final MfaProperties policy;
    private final Clock clock;

    MfaController(MfaChallengeService challenges, MfaManagementService management,
            MfaSessionTransitions sessions, MfaRateControl rates, MfaProperties policy,
            Clock clock) {
        this.challenges = challenges;
        this.management = management;
        this.sessions = sessions;
        this.rates = rates;
        this.policy = policy;
        this.clock = clock;
    }

    @PostMapping("/api/auth/mfa/challenges/{challengeId}/totp")
    ResponseEntity<Map<String, String>> totp(@PathVariable String challengeId,
            @RequestBody TotpInput input, HttpServletRequest request, HttpServletResponse response) {
        UUID userId = IdentitySessionState.principal("ROLE_MFA_PENDING");
        rates.check("MFA_TOTP", userId, request);
        if (input == null) throw ApiFailureException.of(ApiFailureException.Kind.INVALID_INPUT);
        challenges.completeTotp(userId, challengeId, input.code(), request, response);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Map.of("state", "authenticated"));
    }

    @PostMapping("/api/auth/mfa/challenges/{challengeId}/recovery-code")
    ResponseEntity<Map<String, String>> recovery(@PathVariable String challengeId,
            @RequestBody RecoveryInput input, HttpServletRequest request, HttpServletResponse response) {
        UUID userId = IdentitySessionState.principal("ROLE_MFA_PENDING");
        rates.check("MFA_RECOVERY", userId, request);
        if (input == null) throw ApiFailureException.of(ApiFailureException.Kind.INVALID_INPUT);
        challenges.completeRecovery(userId, challengeId, input.code(), request, response);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Map.of("state", "authenticated"));
    }

    @PostMapping("/api/me/security/mfa/totp/enrollments")
    ResponseEntity<MfaManagementService.Setup> begin(HttpServletRequest request) {
        UUID userId = IdentitySessionState.principal("ROLE_USER");
        rates.check("MFA_ENROLL", userId, request);
        IdentitySessionState.requireRecent(request, userId, clock.instant(), policy);
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(management.begin(userId));
    }

    @PostMapping("/api/me/security/mfa/totp/enrollments/{enrollmentId}/confirmation")
    ResponseEntity<Map<String, List<String>>> confirm(@PathVariable String enrollmentId,
            @RequestBody TotpInput input, HttpServletRequest request, HttpServletResponse response) {
        UUID userId = IdentitySessionState.principal("ROLE_USER");
        rates.check("MFA_CONFIRM", userId, request);
        IdentitySessionState.requireRecent(request, userId, clock.instant(), policy);
        if (input == null) throw ApiFailureException.of(ApiFailureException.Kind.INVALID_INPUT);
        List<String> codes = management.confirm(userId, enrollmentId, input.code());
        sessions.establish(userId, true, request, response);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Map.of("recoveryCodes", codes));
    }
}
