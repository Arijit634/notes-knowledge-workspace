package org.notesknowledge;

import java.time.Duration;
import java.util.Objects;

/** Technical ceilings only; Product retry, backoff, and provider limits belong to owners. */
public record LeasePolicy(Duration leaseDuration, int maximumBatchSize) {

    private static final Duration MAX_TECHNICAL_LEASE = Duration.ofDays(1);
    private static final int MAX_TECHNICAL_BATCH = 1_000;

    public LeasePolicy {
        Objects.requireNonNull(leaseDuration, "leaseDuration");
        if (leaseDuration.isZero() || leaseDuration.isNegative()
                || leaseDuration.compareTo(MAX_TECHNICAL_LEASE) > 0
                || maximumBatchSize < 1 || maximumBatchSize > MAX_TECHNICAL_BATCH) {
            throw new IllegalArgumentException("Invalid technical lease policy");
        }
    }

    public int checkedBatchSize(int requested) {
        if (requested < 1 || requested > maximumBatchSize) {
            throw new IllegalArgumentException("Claim batch exceeds configured bound");
        }
        return requested;
    }
}
