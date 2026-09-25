package org.notesknowledge.identity;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;

import org.notesknowledge.security.RateControlService;
import org.notesknowledge.security.RateKeyDeriver;
import org.notesknowledge.security.RateLimitPort;
import org.springframework.stereotype.Component;

/** Security-critical source, subject (when known), and global OIDC budgets. */
@Component
@IdentityCoreEnabled
final class OidcRateControl {
    private final RateControlService rates;
    private final RateKeyDeriver keys;

    OidcRateControl(RateControlService rates, RateKeyDeriver keys) {
        this.rates = rates;
        this.keys = keys;
    }

    void check(String control, UUID userId, HttpServletRequest request) {
        require(control, "source:" + request.getRemoteAddr());
        if (userId != null) require(control, "subject:" + userId);
        require("IDENTITY_GLOBAL", "whole-deployment");
    }

    private void require(String control, String material) {
        rates.check(new RateLimitPort.Request(new RateLimitPort.ControlClass(control),
                keys.derive(control, material), 1), RateControlService.Policy.SECURITY_CRITICAL);
    }
}
