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
    public void search(HttpServletRequest request) {
        enforce("PUBLIC_SEARCH",request.getRemoteAddr());
        enforce("PUBLIC_SEARCH_GLOBAL","aggregate");
    }
    public void like(HttpServletRequest request,java.util.UUID viewer) {
        enforce("PUBLIC_LIKE",viewer.toString());
        enforce("PUBLIC_LIKE_NETWORK",request.getRemoteAddr());
    }
    private void enforce(String name,String subject) {
        control.check(new RateLimitPort.Request(new RateLimitPort.ControlClass(name),keys.derive(name,subject),1),RateControlService.Policy.SECURITY_CRITICAL);
    }
}
