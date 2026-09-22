package org.notesknowledge.security;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Finite, privacy-safe, logging-only rejection telemetry. Never affects enforcement. */
@Component
public final class SecurityControlRejectionEvents {

    private static final Logger LOGGER = LoggerFactory.getLogger(SecurityControlRejectionEvents.class);

    public enum Control { CSRF, AUTHORIZATION, RATE_CONTROL }

    public enum SafeReasonClass { INVALID_CSRF, ACCESS_DENIED, THROTTLED, CONTROL_UNAVAILABLE }

    private final Clock clock;
    private final Instant[][] lastEmitted =
            new Instant[Control.values().length][SafeReasonClass.values().length];

    public SecurityControlRejectionEvents(Clock clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    /** At most one event per finite control/reason pair per second, including under concurrency. */
    public synchronized void rejected(Control control, SafeReasonClass reason) {
        Objects.requireNonNull(control);
        Objects.requireNonNull(reason);
        Instant now = clock.instant();
        Instant prior = lastEmitted[control.ordinal()][reason.ordinal()];
        if (prior != null && now.isBefore(prior.plusSeconds(1))) {
            return;
        }
        lastEmitted[control.ordinal()][reason.ordinal()] = now;
        LOGGER.atWarn()
                .addKeyValue("event.name", "security.control.rejected")
                .addKeyValue("control", control.name())
                .addKeyValue("safeReasonClass", reason.name())
                .log("security.control.rejected");
    }
}
