package org.notesknowledge.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@Tag("FAST")
@ExtendWith(OutputCaptureExtension.class)
class RateControlServiceTest {

    private static final RateLimitPort.Request REQUEST = new RateLimitPort.Request(
            new RateLimitPort.ControlClass("SYNTHETIC_CONTROL"),
            new RateLimitPort.OpaqueKey("syntheticOpaqueKey123"), 1);

    @Test
    void boundedTypesRejectInvalidInputs() {
        assertThatThrownBy(() -> new RateLimitPort.ControlClass("/api/notes?user=private"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimitPort.ControlClass("x".repeat(49)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimitPort.OpaqueKey("person@example.invalid"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimitPort.Request(
                REQUEST.controlClass(), REQUEST.enforcementKey(), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimitPort.Request(
                REQUEST.controlClass(), REQUEST.enforcementKey(), 1_000_001))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimitPort.Throttled(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimitPort.Throttled(86_401))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(REQUEST.toString()).doesNotContain("syntheticOpaqueKey123");
    }

    @Test
    void explicitPolicyControlsOnlyUnavailableDegradation(CapturedOutput output) {
        MutableClock clock = new MutableClock();
        SecurityControlRejectionEvents events = new SecurityControlRejectionEvents(clock);
        FakePort port = new FakePort();
        RateControlService service = new RateControlService(port, events);
        AtomicInteger operationRuns = new AtomicInteger();

        port.decision = new RateLimitPort.Allowed();
        service.check(REQUEST, RateControlService.Policy.SECURITY_CRITICAL);
        operationRuns.incrementAndGet();
        assertThat(output.getAll()).doesNotContain("security.control.rejected");

        port.decision = new RateLimitPort.Throttled(17);
        assertThatThrownBy(() -> {
            service.check(REQUEST, RateControlService.Policy.BEST_EFFORT);
            operationRuns.incrementAndGet();
        }).isInstanceOf(ApiFailureException.class).hasMessage("rate_limited");
        assertThat(operationRuns).hasValue(1);
        assertThat(output.getAll()).contains("security.control.rejected", "RATE_CONTROL", "THROTTLED")
                .doesNotContain("syntheticOpaqueKey123");

        port.decision = new RateLimitPort.ControlUnavailable();
        assertThatThrownBy(() -> {
            service.check(REQUEST, RateControlService.Policy.SECURITY_CRITICAL);
            operationRuns.incrementAndGet();
        }).isInstanceOf(ApiFailureException.class).hasMessage("service_unavailable");
        assertThat(operationRuns).hasValue(1);
        assertThat(output.getAll()).contains("CONTROL_UNAVAILABLE");

        String prior = output.getAll();
        service.check(REQUEST, RateControlService.Policy.BEST_EFFORT);
        operationRuns.incrementAndGet();
        assertThat(operationRuns).hasValue(2);
        assertThat(output.getAll()).isEqualTo(prior);
        assertThatThrownBy(() -> service.check(REQUEST, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void duplicateTelemetryIsBoundedByFinitePairAndClock(CapturedOutput output) {
        MutableClock clock = new MutableClock();
        SecurityControlRejectionEvents events = new SecurityControlRejectionEvents(clock);
        events.rejected(SecurityControlRejectionEvents.Control.CSRF,
                SecurityControlRejectionEvents.SafeReasonClass.INVALID_CSRF);
        events.rejected(SecurityControlRejectionEvents.Control.CSRF,
                SecurityControlRejectionEvents.SafeReasonClass.INVALID_CSRF);
        events.rejected(SecurityControlRejectionEvents.Control.AUTHORIZATION,
                SecurityControlRejectionEvents.SafeReasonClass.ACCESS_DENIED);
        assertThat(count(output.getAll(), "security.control.rejected")).isEqualTo(2);
        clock.advanceSeconds(1);
        events.rejected(SecurityControlRejectionEvents.Control.CSRF,
                SecurityControlRejectionEvents.SafeReasonClass.INVALID_CSRF);
        assertThat(count(output.getAll(), "security.control.rejected")).isEqualTo(3);
    }

    private long count(String text, String needle) {
        return text.lines().filter(line -> line.contains(needle)).count();
    }

    private static final class FakePort implements RateLimitPort {
        private Decision decision;

        @Override
        public Decision evaluate(Request request) {
            assertThat(request).isSameAs(REQUEST);
            return decision;
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-22T00:00:00Z");

        void advanceSeconds(long seconds) {
            now = now.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
