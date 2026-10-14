package org.notesknowledge.discovery;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.notesknowledge.security.RateLimitPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Bounded single-deployable counting hints, never viewer identity or authorization. */
@Component
final class TransientViewDeduplicator {
    private final Clock clock;
    private final Duration window;
    private final int capacity;
    private final Map<Key, Instant> recent = new LinkedHashMap<>();

    @Autowired
    TransientViewDeduplicator(Clock clock) {
        this(clock, Duration.ofMinutes(10), 4096);
    }

    TransientViewDeduplicator(Clock clock, Duration window, int capacity) {
        if (clock == null || window == null || window.isNegative() || window.isZero()
                || window.compareTo(Duration.ofHours(24)) > 0 || capacity < 1 || capacity > 65536) {
            throw new IllegalArgumentException("Invalid transient view policy");
        }
        this.clock = clock;
        this.window = window;
        this.capacity = capacity;
    }

    synchronized boolean firstWithinWindow(UUID publication, RateLimitPort.OpaqueKey viewer) {
        if (publication == null || viewer == null) return false;
        Instant now = clock.instant();
        recent.entrySet().removeIf(entry -> !now.isBefore(entry.getValue()));
        var key = new Key(publication, viewer.value());
        // Saturation drops a best-effort count instead of evicting a live hint
        // and allowing rapid refreshes to manufacture additional views.
        if (recent.containsKey(key) || recent.size() >= capacity) return false;
        recent.put(key, now.plus(window));
        return true;
    }

    private record Key(UUID publication, String viewer) {
        @Override public String toString() { return "ViewHint[REDACTED]"; }
    }
}
