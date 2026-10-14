package org.notesknowledge.discovery;

import static org.assertj.core.api.Assertions.*;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.notesknowledge.security.RateLimitPort;

@Tag("FAST") @Tag("SECURITY")
class TransientViewDeduplicatorTest {
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-15T00:00:00Z"));
    private final Clock clock = new Clock() {
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now.get(); }
    };
    private final UUID first = new UUID(0, 1), second = new UUID(0, 2);
    private final RateLimitPort.OpaqueKey a = new RateLimitPort.OpaqueKey("synthetic_viewer_a"),
        b = new RateLimitPort.OpaqueKey("synthetic_viewer_b");

    @Test void valueEqualityAndPublicationScopeDetermineDuplicates() {
        var hints = new TransientViewDeduplicator(clock);
        assertThat(hints.firstWithinWindow(first, a)).isTrue();
        assertThat(hints.firstWithinWindow(first, new RateLimitPort.OpaqueKey(a.value()))).isFalse();
        assertThat(hints.firstWithinWindow(first, b)).isTrue();
        assertThat(hints.firstWithinWindow(second, a)).isTrue();
    }

    @Test void expiryIsExactAndRefreshesDoNotExtendTheCountingWindow() {
        var hints = new TransientViewDeduplicator(clock);
        assertThat(hints.firstWithinWindow(first, a)).isTrue();
        now.updateAndGet(value -> value.plusSeconds(599));
        assertThat(hints.firstWithinWindow(first, a)).isFalse();
        now.updateAndGet(value -> value.plusSeconds(1));
        assertThat(hints.firstWithinWindow(first, a)).isTrue();
        now.updateAndGet(value -> value.minusSeconds(30));
        assertThat(hints.firstWithinWindow(first, a)).isFalse();
    }

    @Test void saturationDropsCountsWithoutEvictingLiveHintsAndExpiryRecoversCapacity() {
        var hints = new TransientViewDeduplicator(clock, Duration.ofMinutes(1), 1);
        assertThat(hints.firstWithinWindow(first, a)).isTrue();
        assertThat(hints.firstWithinWindow(first, b)).isFalse();
        assertThat(hints.firstWithinWindow(first, a)).isFalse();
        now.updateAndGet(value -> value.plusSeconds(60));
        assertThat(hints.firstWithinWindow(first, b)).isTrue();
    }

    @Test void absentViewerNeverAcquiresACountingIdentityAndRestartOnlyLosesHints() {
        var hints = new TransientViewDeduplicator(clock);
        assertThat(hints.firstWithinWindow(first, null)).isFalse();
        assertThat(hints.firstWithinWindow(first, a)).isTrue();
        assertThat(new TransientViewDeduplicator(clock).firstWithinWindow(first, a)).isTrue();
        assertThat(a.toString()).doesNotContain(a.value());
    }

    @Test void coordinatedConcurrentRequestsCountOnce() throws Exception {
        var hints = new TransientViewDeduplicator(clock);
        var gate = new CyclicBarrier(4);
        try (var workers = Executors.newFixedThreadPool(4)) {
            var tasks = new java.util.ArrayList<Future<Boolean>>();
            for (int i = 0; i < 4; i++) tasks.add(workers.submit(() -> {
                gate.await(10, TimeUnit.SECONDS);
                return hints.firstWithinWindow(first, a);
            }));
            int accepted = 0;
            for (var task : tasks) if (task.get(15, TimeUnit.SECONDS)) accepted++;
            assertThat(accepted).isEqualTo(1);
        }
    }

    @Test void invalidPoliciesAreRejected() {
        for (var window : new Duration[]{Duration.ZERO, Duration.ofSeconds(-1), Duration.ofHours(25)})
            assertThatThrownBy(() -> new TransientViewDeduplicator(clock, window, 1)).isInstanceOf(IllegalArgumentException.class);
        for (int capacity : new int[]{0, 65537})
            assertThatThrownBy(() -> new TransientViewDeduplicator(clock, Duration.ofMinutes(1), capacity)).isInstanceOf(IllegalArgumentException.class);
    }
}
