package org.notesknowledge.identity;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.notesknowledge.DatabaseUuidV7Generator;
import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@IdentityCoreEnabled
class EmailVerificationService {
    private static final Duration VERIFICATION_LIFETIME = Duration.ofHours(24);
    private final IdentityPersistence identity;
    private final SecurityEmailDeliveryRepository delivery;
    private final SecurityEmailMaterialCipher cipher;
    private final DatabaseUuidV7Generator ids;
    private final Clock clock;

    EmailVerificationService(IdentityPersistence identity,
            SecurityEmailDeliveryRepository delivery, SecurityEmailMaterialCipher cipher,
            DatabaseUuidV7Generator ids, Clock clock) {
        this.identity = identity;
        this.delivery = delivery;
        this.cipher = cipher;
        this.ids = ids;
        this.clock = clock;
    }

    @Transactional
    void request(String email) {
        String canonical = IdentityInput.canonicalEmail(email);
        var pending = identity.pendingAccountForUpdate(canonical);
        if (pending.isEmpty()) {
            // No fake Account or capability is written for an unknown/ineligible target.
            UUID dummyId = ids.generate();
            String dummy = VerificationToken.issue(dummyId);
            cipher.seal(dummyId, dummy);
            VerificationToken.digest(dummy);
            return;
        }
        UUID userId = pending.get();
        Instant now = clock.instant();
        UUID capabilityId = ids.generate();
        String token = VerificationToken.issue(capabilityId);
        var sealed = cipher.seal(capabilityId, token);
        identity.supersede(userId, now);
        identity.issue(capabilityId, userId, VerificationToken.digest(token), now,
                now.plus(VERIFICATION_LIFETIME));
        delivery.queueCapability(capabilityId, sealed, now);
        identity.audit(userId, "email_verification", "requested", now);
    }

    @Transactional
    void confirm(String token) {
        final UUID id;
        try {
            id = VerificationToken.locator(token);
        } catch (IllegalArgumentException exception) {
            throw ApiFailureException.of(ApiFailureException.Kind.INVALID_INPUT);
        }
        var candidate = identity.capability(id).orElseThrow(() ->
                ApiFailureException.of(ApiFailureException.Kind.INVALID_INPUT));
        if (!"email_verification".equals(candidate.purpose())
                || !MessageDigest.isEqual(candidate.digest(), VerificationToken.digest(token))) {
            throw ApiFailureException.of(ApiFailureException.Kind.INVALID_INPUT);
        }
        if (!identity.lockPendingAccount(candidate.userId())) {
            throw ApiFailureException.of(ApiFailureException.Kind.INVALID_LIFECYCLE_TRANSITION);
        }
        Instant now = clock.instant();
        if (identity.consume(id, VerificationToken.digest(token), now) != 1
                || identity.verifyAccount(candidate.userId(), now) != 1) {
            throw ApiFailureException.of(ApiFailureException.Kind.INVALID_LIFECYCLE_TRANSITION);
        }
        identity.audit(candidate.userId(), "email_verification", "confirmed", now);
    }
}
