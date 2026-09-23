package org.notesknowledge.identity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.beans.factory.ObjectProvider;
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

    SecurityEmailWorker(IdentityPersistence identity, SecurityEmailDeliveryRepository delivery,
            SecurityEmailMaterialCipher cipher, SecurityEmailMessageRenderer renderer,
            ObjectProvider<SecurityEmailProviderPort> provider, Clock clock) {
        this.identity = identity;
        this.delivery = delivery;
        this.cipher = cipher;
        this.renderer = renderer;
        this.provider = provider;
        this.clock = clock;
    }

    void process(SecurityEmailDeliveryRepository.Claim claim) {
        Instant now = clock.instant();
        var destination = identity.currentVerificationDestination(claim.id(), claim.capabilityId(),
                claim.token().value(), now);
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
        var currentDestination = identity.currentVerificationDestination(claim.id(),
                claim.capabilityId(), claim.token().value(), clock.instant());
        if (currentDestination.isEmpty() || !currentDestination.get().equals(destination.get())) {
            delivery.obsolete(claim, clock.instant(), "authority_not_current");
            return;
        }
        SecurityEmailProviderPort adapter = provider.getIfAvailable();
        if (adapter == null) {
            retryOrFail(claim, "provider_unavailable");
            return;
        }
        try {
            // Ambiguous provider acceptance may be retried. The capability remains one-time.
            adapter.submit(destination.get(), message);
        } catch (RuntimeException exception) {
            retryOrFail(claim, "provider_failure");
            return;
        }
        delivery.submitted(claim, clock.instant());
    }

    private void retryOrFail(SecurityEmailDeliveryRepository.Claim claim, String reason) {
        Instant now = clock.instant();
        if (claim.attempt() >= 5) {
            delivery.failed(claim, now, reason);
        } else {
            long seconds = Math.min(3600, 30L << Math.min(claim.attempt() - 1, 6));
            delivery.retry(claim, now, now.plus(Duration.ofSeconds(seconds)), reason);
        }
    }
}
