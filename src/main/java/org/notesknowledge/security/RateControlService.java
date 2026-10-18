package org.notesknowledge.security;

import java.util.Objects;

import org.notesknowledge.websupport.ApiFailureException;

/** Interprets a typed control decision before a caller invokes its protected operation. */
public final class RateControlService {

    public enum Policy { SECURITY_CRITICAL, BEST_EFFORT }

    private final RateLimitPort port;
    private final SecurityControlRejectionEvents events;
    private final io.micrometer.core.instrument.MeterRegistry meters;

    public RateControlService(RateLimitPort port, SecurityControlRejectionEvents events) {
        this(port,events,null);
    }
    RateControlService(RateLimitPort port,SecurityControlRejectionEvents events,io.micrometer.core.instrument.MeterRegistry meters){
        this.port = Objects.requireNonNull(port);
        this.events = Objects.requireNonNull(events);
        this.meters=meters;
    }

    public void check(RateLimitPort.Request request, Policy policy) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(policy, "policy");
        RateLimitPort.Decision decision = Objects.requireNonNull(port.evaluate(request),
                "RateLimitPort returned no decision");
        observe(request,policy,decision);
        if (decision instanceof RateLimitPort.Allowed) {
            return;
        }
        if (decision instanceof RateLimitPort.Throttled throttled) {
            events.rejected(SecurityControlRejectionEvents.Control.RATE_CONTROL,
                    SecurityControlRejectionEvents.SafeReasonClass.THROTTLED);
            throw ApiFailureException.rateLimited(throttled.retryAfterSeconds());
        }
        if (decision instanceof RateLimitPort.ControlUnavailable) {
            if (policy == Policy.BEST_EFFORT) {
                return;
            }
            events.rejected(SecurityControlRejectionEvents.Control.RATE_CONTROL,
                    SecurityControlRejectionEvents.SafeReasonClass.CONTROL_UNAVAILABLE);
            throw ApiFailureException.of(ApiFailureException.Kind.SERVICE_UNAVAILABLE);
        }
        throw new IllegalStateException("Unsupported rate-control decision");
    }

    private void observe(RateLimitPort.Request request,Policy policy,RateLimitPort.Decision decision){
        String control=request.controlClass().value();
        // Classify only server-owned families; the enforcement key never reaches telemetry.
        String family=control.startsWith("KNOWLEDGE")?"knowledge":control.contains("SEARCH")?"search":
            control.startsWith("ATTACHMENT")?"attachment":control.startsWith("NOTE_")?"notes":
            control.startsWith("MODERATION")||control.startsWith("REPORT")?"moderation":control.startsWith("PUBLIC")?"public":"identity";
        String outcome=decision instanceof RateLimitPort.Allowed?"allowed":decision instanceof RateLimitPort.Throttled?"throttled":
            policy==Policy.BEST_EFFORT?"bypassed":"unavailable";
        try{if(meters!=null)meters.counter("notes.workspace.rate_control","class",family,"outcome",outcome).increment();}
        catch(RuntimeException unavailable){/* diagnostic only */}
    }
}
