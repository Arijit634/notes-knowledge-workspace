package org.notesknowledge.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.util.Map;
import java.util.UUID;

import org.notesknowledge.security.RateControlService;
import org.notesknowledge.security.RateKeyDeriver;
import org.notesknowledge.security.RateLimitPort;
import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@IdentityCoreEnabled
@RequestMapping("/api/auth")
final class AuthController {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(AuthController.class);
    record EmailInput(String email) { }
    record RegistrationInput(String email, String password) { }
    record ConfirmationInput(String token) { }
    record LoginInput(String email, String password) { }

    private final RegistrationService registration;
    private final EmailVerificationService verification;
    private final PasswordAuthenticationService passwords;
    private final IdentityPersistence identity;
    private final RateControlService rates;
    private final RateKeyDeriver rateKeys;
    private final MfaRateControl mfaRates;
    private final java.time.Clock clock;

    AuthController(RegistrationService registration, EmailVerificationService verification,
            PasswordAuthenticationService passwords, IdentityPersistence identity,
            RateControlService rates, RateKeyDeriver rateKeys,
            MfaRateControl mfaRates, java.time.Clock clock) {
        this.registration = registration;
        this.verification = verification;
        this.passwords = passwords;
        this.identity = identity;
        this.rates = rates;
        this.rateKeys = rateKeys;
        this.mfaRates = mfaRates;
        this.clock = clock;
    }

    @GetMapping("/csrf")
    ResponseEntity<Map<String, String>> csrf(HttpServletRequest request) {
        CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (token == null) {
            throw ApiFailureException.of(ApiFailureException.Kind.SERVICE_UNAVAILABLE);
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Map.of("csrfToken", token.getToken()));
    }

    @GetMapping("/session")
    ResponseEntity<Map<String, String>> session() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        String state = "anonymous";
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof IdentitySessionPrincipal principal
                && identity.isActive(principal.userId())) {
            state = authentication.getAuthorities().stream()
                    .anyMatch(a -> "ROLE_USER".equals(a.getAuthority()))
                    ? "authenticated" : "mfaRequired";
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Map.of("state", state));
    }

    @PostMapping("/registrations")
    ResponseEntity<Void> register(@RequestBody RegistrationInput input, HttpServletRequest request) {
        if (input == null) {
            throw ApiFailureException.of(ApiFailureException.Kind.INVALID_INPUT);
        }
        rate("REGISTRATION", input.email(), request);
        try {
            registration.begin(input.email(), input.password());
        } catch (DataIntegrityViolationException exception) {
            if (!isCanonicalEmailCollision(exception)) {
                throw exception;
            }
        }
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping("/email-verification/requests")
    ResponseEntity<Void> requestVerification(@RequestBody EmailInput input,
            HttpServletRequest request) {
        if (input == null) {
            throw ApiFailureException.of(ApiFailureException.Kind.INVALID_INPUT);
        }
        rate("VERIFICATION_REQUEST", input.email(), request);
        verification.request(input.email());
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping("/email-verification/confirmations")
    ResponseEntity<Void> confirmVerification(@RequestBody ConfirmationInput input,
            HttpServletRequest request) {
        if (input == null || input.token() == null || input.token().length() > 128) {
            throw ApiFailureException.of(ApiFailureException.Kind.INVALID_INPUT);
        }
        rateSourceOnly("VERIFICATION_CONFIRMATION", request);
        verification.confirm(input.token());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping("/login/password")
    ResponseEntity<Map<String, String>> login(@RequestBody LoginInput input,
            HttpServletRequest request, HttpServletResponse response) {
        if (input == null) {
            throw ApiFailureException.of(ApiFailureException.Kind.INVALID_INPUT);
        }
        rate("PASSWORD_LOGIN", input.email(), request);
        var result = passwords.authenticate(input.email(), input.password(), request, response);
        if (result.mfaRequired()) {
            return ResponseEntity.accepted().cacheControl(CacheControl.noStore())
                    .body(Map.of("challengeId", result.challengeId(), "state", "mfaRequired"));
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Map.of("state", "authenticated"));
    }

    @PostMapping("/reauth/password")
    ResponseEntity<Void> reauthenticate(@RequestBody LoginInput input, HttpServletRequest request) {
        UUID userId = IdentitySessionState.principal("ROLE_USER");
        mfaRates.check("PASSWORD_REAUTH", userId, request);
        if (input == null || input.email() != null) {
            throw ApiFailureException.of(ApiFailureException.Kind.INVALID_INPUT);
        }
        passwords.reauthenticate(userId, input.password());
        request.getSession().setAttribute(IdentitySessionState.RECENT_ATTRIBUTE,
                new IdentitySessionState.RecentAuthentication(userId, clock.instant(), "password"));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(HttpServletRequest request) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        UUID userId = authentication != null
                && authentication.getPrincipal() instanceof IdentitySessionPrincipal principal
                ? principal.userId() : null;
        var session = request.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
        if (userId != null) {
            try {
                identity.auditLogout(userId, clock.instant());
            } catch (RuntimeException failure) {
                // Revocation has already happened. Never restore authority for audit availability.
                LOG.warn("logout_audit_failed");
            }
        }
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private void rate(String control, String candidate, HttpServletRequest request) {
        String canonical = IdentityInput.canonicalEmail(candidate);
        rateSourceOnly(control, request);
        rates.check(new RateLimitPort.Request(new RateLimitPort.ControlClass(control),
                rateKeys.derive(control, "candidate:" + canonical), 1),
                RateControlService.Policy.SECURITY_CRITICAL);
        rateGlobal();
    }

    private void rateSourceOnly(String control, HttpServletRequest request) {
        rates.check(new RateLimitPort.Request(new RateLimitPort.ControlClass(control),
                rateKeys.derive(control, "source:" + request.getRemoteAddr()), 1),
                RateControlService.Policy.SECURITY_CRITICAL);
        if ("VERIFICATION_CONFIRMATION".equals(control)) {
            rateGlobal();
        }
    }

    private void rateGlobal() {
        checkGlobalBucket("IDENTITY_GLOBAL");
    }

    private void checkGlobalBucket(String control) {
        rates.check(new RateLimitPort.Request(new RateLimitPort.ControlClass(control),
                rateKeys.derive(control, "whole-deployment"), 1),
                RateControlService.Policy.SECURITY_CRITICAL);
    }

    private boolean isCanonicalEmailCollision(DataIntegrityViolationException exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof org.postgresql.util.PSQLException postgres
                    && "23505".equals(postgres.getSQLState())
                    && postgres.getServerErrorMessage() != null
                    && "account_canonical_email_key".equals(
                            postgres.getServerErrorMessage().getConstraint())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
