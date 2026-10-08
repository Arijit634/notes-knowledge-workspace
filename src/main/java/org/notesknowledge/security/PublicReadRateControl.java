package org.notesknowledge.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/** Existing transient control, bounded subject/class; no persistent viewer ledger. */
@Component
public class PublicReadRateControl {
    private final RateControlService control;
    private final RateKeyDeriver keys;
    PublicReadRateControl(RateControlService control,RateKeyDeriver keys){this.control=control;this.keys=keys;}
    public void check(HttpServletRequest request) {
        control.check(new RateLimitPort.Request(new RateLimitPort.ControlClass("PUBLIC_READ"),
            keys.derive("PUBLIC_READ",request.getRemoteAddr()),1),RateControlService.Policy.BEST_EFFORT);
    }
}
