package org.notesknowledge.identity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

import org.springframework.beans.factory.ObjectProvider;
import org.notesknowledge.security.RateKeyDeriver;
import org.notesknowledge.security.RateLimitPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Revalidates authoritative state; external submission never occurs in a DB transaction. */
@Component
@IdentityCoreEnabled
final class SecurityEmailWorker {
    private final IdentityPersistence identity;
    private final SecurityEmailDeliveryRepository delivery;
    private final SecurityEmailMaterialCipher cipher;
    private final SecurityEmailMessageRenderer renderer;
    private final ObjectProvider<SecurityEmailProviderPort> provider;
    private final Clock clock;
    private final SecurityEmailDeliveryProperties policy;
    private final RateLimitPort capacity;
    private final RateKeyDeriver capacityKeys;
    private final DoubleSupplier retryRandom;

    @org.springframework.beans.factory.annotation.Autowired
    SecurityEmailWorker(IdentityPersistence identity, SecurityEmailDeliveryRepository delivery,
            SecurityEmailMaterialCipher cipher, SecurityEmailMessageRenderer renderer,
            ObjectProvider<SecurityEmailProviderPort> provider, Clock clock,
            SecurityEmailDeliveryProperties policy, RateLimitPort capacity,
            RateKeyDeriver capacityKeys) {
        this(identity, delivery, cipher, renderer, provider, clock, policy, capacity,
                capacityKeys, () -> ThreadLocalRandom.current().nextDouble());
    }

    SecurityEmailWorker(IdentityPersistence identity, SecurityEmailDeliveryRepository delivery,
            SecurityEmailMaterialCipher cipher, SecurityEmailMessageRenderer renderer,
            ObjectProvider<SecurityEmailProviderPort> provider, Clock clock,
            SecurityEmailDeliveryProperties policy, RateLimitPort capacity,
            RateKeyDeriver capacityKeys, DoubleSupplier retryRandom) {
        this.identity = identity;
        this.delivery = delivery;
        this.cipher = cipher;
        this.renderer = renderer;
        this.provider = provider;
        this.clock = clock;
        this.policy = policy;
        this.capacity = capacity;
        this.capacityKeys = capacityKeys;
        this.retryRandom = Objects.requireNonNull(retryRandom);
    }

    void process(SecurityEmailDeliveryRepository.Claim claim) {
        Instant now = clock.instant();
        if (!delivery.ownsUsableClaim(claim, now)) {
            return; // Expiry or loss is not capability revocation; leave it reclaimable.
        }
        var destination = identity.currentVerificationDestination(claim.id(), claim.capabilityId(), now);
        if (destination.isEmpty()) {
            delivery.obsolete(claim, now, "authority_not_current");
            return;
        }
        final String rawToken;
        try {
            rawToken = cipher.open(claim.capabilityId(), claim.envelope());
            if (!claim.capabilityId().equals(VerificationToken.locator(rawToken))) {
                delivery.failed(claim, clock.instant(), "material_invalid");
                return;
            }
        } catch (RuntimeException exception) {
            delivery.failed(claim, clock.instant(), "material_unavailable");
            return;
        }
        final SecurityEmailMessageRenderer.Message message;
        try {
            message = renderer.verification(rawToken);
        } catch (RuntimeException exception) {
            retryOrFail(claim, "render_unavailable");
            return;
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Provider invocation inside transaction");
        }
        now = clock.instant();
        if (!delivery.ownsUsableClaim(claim, now)) {
            return;
        }
        var currentDestination = identity.currentVerificationDestination(claim.id(),
                claim.capabilityId(), now);
        if (currentDestination.isEmpty() || !currentDestination.get().equals(destination.get())) {
            delivery.obsolete(claim, clock.instant(), "authority_not_current");
            return;
        }
        SecurityEmailProviderPort adapter = provider.getIfAvailable();
        if (adapter == null) {
            retryOrFail(claim, "provider_unavailable");
            return;
        }
        // Count only an eligible dispatch against the daily provider budget.
        // Unknown-account requests cannot burn the real mail allowance.
        final RateLimitPort.Decision capacityDecision;
        try {
            capacityDecision = capacity.evaluate(new RateLimitPort.Request(
                    new RateLimitPort.ControlClass("SECURITY_EMAIL_PROVIDER"),
                    capacityKeys.derive("SECURITY_EMAIL_PROVIDER", "whole-deployment"), 1));
        } catch (RuntimeException exception) {
            retryOrFail(claim, "provider_capacity_unavailable");
            return;
        }
        if (capacityDecision instanceof RateLimitPort.Throttled throttled) {
            defer(claim, "provider_capacity", Duration.ofSeconds(throttled.retryAfterSeconds()));
            return;
        }
        if (!(capacityDecision instanceof RateLimitPort.Allowed)) {
            retryOrFail(claim, "provider_capacity_unavailable");
            return;
        }
        now = clock.instant();
        if (!delivery.ownsUsableClaim(claim, now)) {
            return;
        }
        var dispatchDestination = identity.currentVerificationDestination(
                claim.id(), claim.capabilityId(), now);
        if (dispatchDestination.isEmpty() || !dispatchDestination.get().equals(destination.get())) {
            delivery.obsolete(claim, now, "authority_not_current");
            return;
        }
        final SecurityEmailProviderPort.Outcome outcome;
        try {
            // Ambiguous acceptance may duplicate mail, never capability authority.
            outcome = adapter.submit(destination.get(), message);
        } catch (RuntimeException exception) {
            retryOrFail(claim, "provider_ambiguous");
            return;
        }
        switch (outcome) {
            case SUBMITTED -> delivery.submitted(claim, clock.instant());
            case NON_RETRYABLE -> delivery.failed(claim, clock.instant(), "provider_permanent");
            case RETRYABLE -> retryOrFail(claim, "provider_retryable");
            case AMBIGUOUS -> retryOrFail(claim, "provider_ambiguous");
        }
    }

    private void retryOrFail(SecurityEmailDeliveryRepository.Claim claim, String reason) {
        Instant now = clock.instant();
        if (claim.attempt() >= policy.maxAttempts()) {
            delivery.failed(claim, now, reason);
        } else {
            delivery.retry(claim, now, now.plus(retryDelay(claim.attempt())), reason);
        }
    }

    Duration retryDelay(int attempt) {
        if (attempt < 1) {
            throw new IllegalArgumentException("Retry attempt must be positive");
        }
        long factor = 1L << Math.min(attempt - 1, 20);
        long millis = Math.min(policy.maxBackoff().toMillis(),
                Math.multiplyExact(policy.minBackoff().toMillis(), factor));
        long jitterBound = Math.min(policy.retryJitter().toMillis(),
                policy.maxBackoff().toMillis() - millis);
        double draw = retryRandom.getAsDouble();
        if (!Double.isFinite(draw) || draw < 0 || draw >= 1) {
            throw new IllegalStateException("Invalid security-email retry jitter source");
        }
        long jitterMillis = (long) (draw * (jitterBound + 1));
        return Duration.ofMillis(millis + jitterMillis);
    }

    private void defer(SecurityEmailDeliveryRepository.Claim claim, String reason, Duration delay) {
        Instant now = clock.instant();
        delivery.deferUnsent(claim, now, now.plus(delay), reason);
    }
}
