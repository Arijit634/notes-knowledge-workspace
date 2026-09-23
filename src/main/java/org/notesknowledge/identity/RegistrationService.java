package org.notesknowledge.identity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.notesknowledge.DatabaseUuidV7Generator;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@IdentityCoreEnabled
class RegistrationService {
    private static final Duration VERIFICATION_LIFETIME = Duration.ofHours(24);
    private final AccountRepository accounts;
    private final IdentityPersistence identity;
    private final SecurityEmailDeliveryRepository delivery;
    private final SecurityEmailMaterialCipher cipher;
    private final DatabaseUuidV7Generator ids;
    private final PasswordEncoder passwords;
    private final Clock clock;

    RegistrationService(AccountRepository accounts, IdentityPersistence identity,
            SecurityEmailDeliveryRepository delivery, SecurityEmailMaterialCipher cipher,
            DatabaseUuidV7Generator ids, PasswordEncoder passwords, Clock clock) {
        this.accounts = accounts;
        this.identity = identity;
        this.delivery = delivery;
        this.cipher = cipher;
        this.ids = ids;
        this.passwords = passwords;
        this.clock = clock;
    }

    @Transactional
    void begin(String email, String password) {
        String canonical = IdentityInput.canonicalEmail(email);
        IdentityInput.password(password);
        Instant now = clock.instant();
        UUID userId = ids.generate();
        UUID capabilityId = ids.generate();
        String token = VerificationToken.issue(capabilityId);
        var sealed = cipher.seal(capabilityId, token);
        String verifier = passwords.encode(password);
        accounts.saveAndFlush(new Account(userId, canonical, email.trim(), verifier, now));
        identity.issue(capabilityId, userId, VerificationToken.digest(token), now,
                now.plus(VERIFICATION_LIFETIME));
        delivery.queueCapability(capabilityId, sealed, now);
        identity.audit(userId, "registration", "pending_verification", now);
    }
}
