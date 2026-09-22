package org.notesknowledge.security;

import java.util.Objects;

import org.notesknowledge.websupport.ApiFailureException;

/** Interprets a typed control decision before a caller invokes its protected operation. */
public final class RateControlService {

    public enum Policy { SECURITY_CRITICAL, BEST_EFFORT }

    private final RateLimitPort port;
    private final SecurityControlRejectionEvents events;

    public RateControlService(RateLimitPort port, SecurityControlRejectionEvents events) {
        this.port = Objects.requireNonNull(port);
        this.events = Objects.requireNonNull(events);
    }

    public void check(RateLimitPort.Request request, Policy policy) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(policy, "policy");
        RateLimitPort.Decision decision = Objects.requireNonNull(port.evaluate(request),
                "RateLimitPort returned no decision");
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
}
