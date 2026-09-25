package org.notesknowledge.identity;

import jakarta.servlet.http.HttpServletRequest;

import java.util.UUID;

import org.notesknowledge.security.RateControlService;
import org.notesknowledge.security.RateKeyDeriver;
import org.notesknowledge.security.RateLimitPort;
import org.springframework.stereotype.Component;

/** HMAC-derived source, subject, operation, and deployment capacity controls. */
@Component
@IdentityCoreEnabled
final class MfaRateControl {
    private final RateControlService rates;
    private final RateKeyDeriver keys;

    MfaRateControl(RateControlService rates, RateKeyDeriver keys) {
        this.rates = rates;
        this.keys = keys;
    }

    void check(String control, UUID userId, HttpServletRequest request) {
        require(control, "source:" + request.getRemoteAddr());
        require(control, "subject:" + userId);
        require("IDENTITY_GLOBAL", "whole-deployment");
    }

    private void require(String control, String material) {
        rates.check(new RateLimitPort.Request(new RateLimitPort.ControlClass(control),
                keys.derive(control, material), 1), RateControlService.Policy.SECURITY_CRITICAL);
    }
}
