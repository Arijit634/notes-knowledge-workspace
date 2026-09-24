package org.notesknowledge.identity;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Bounded local worker policy; PostgreSQL remains the work authority. */
@ConfigurationProperties(prefix = "identity.delivery")
record SecurityEmailDeliveryProperties(int batchSize, int workerConcurrency,
        int queueCapacity, Duration leaseDuration, Duration providerTimeout,
        int maxAttempts, Duration minBackoff, Duration maxBackoff,
        Duration retryJitter, Duration pollDelay, String workerAlias) {
    SecurityEmailDeliveryProperties {
        if (batchSize < 1 || batchSize > 1_000 || workerConcurrency < 1
                || workerConcurrency > 32 || queueCapacity < 1 || queueCapacity > 1_000
                || maxAttempts < 1 || maxAttempts > 100
                || leaseDuration == null || providerTimeout == null
                || minBackoff == null || maxBackoff == null || retryJitter == null
                || pollDelay == null
                || providerTimeout.isZero() || providerTimeout.isNegative()
                || providerTimeout.compareTo(Duration.ofMinutes(5)) > 0
                || leaseDuration.compareTo(providerTimeout) <= 0
                || leaseDuration.compareTo(providerTimeout.multipliedBy(
                        (queueCapacity + workerConcurrency - 1) / workerConcurrency + 1L)) <= 0
                || leaseDuration.compareTo(Duration.ofDays(1)) > 0
                || minBackoff.isZero() || minBackoff.isNegative()
                || maxBackoff.compareTo(minBackoff) < 0
                || maxBackoff.compareTo(Duration.ofDays(1)) > 0
                || retryJitter.isNegative() || retryJitter.compareTo(minBackoff) > 0
                || pollDelay.isZero() || pollDelay.isNegative()
                || pollDelay.compareTo(Duration.ofMinutes(10)) > 0
                || workerAlias == null || !workerAlias.matches("[a-z][a-z0-9_]{0,63}")) {
            throw new IllegalArgumentException("Invalid bounded security-email delivery policy");
        }
    }
}
