package org.notesknowledge.identity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.notesknowledge.DatabaseUuidV7Generator;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
    private final TransactionTemplate transactions;

    RegistrationService(AccountRepository accounts, IdentityPersistence identity,
            SecurityEmailDeliveryRepository delivery, SecurityEmailMaterialCipher cipher,
            DatabaseUuidV7Generator ids, PasswordEncoder passwords, Clock clock,
            PlatformTransactionManager transactionManager) {
        this.accounts = accounts;
        this.identity = identity;
        this.delivery = delivery;
        this.cipher = cipher;
        this.ids = ids;
        this.passwords = passwords;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    void begin(String email, String password) {
        String canonical = IdentityInput.canonicalEmail(email);
        IdentityInput.password(password);
        Instant now = clock.instant();
        UUID userId = ids.generate();
        UUID capabilityId = ids.generate();
        String token = VerificationToken.issue(capabilityId);
        var sealed = cipher.seal(capabilityId, token);
        byte[] digest = VerificationToken.digest(token);
        String verifier = passwords.encode(password);
        transactions.executeWithoutResult(status -> {
            accounts.saveAndFlush(new Account(userId, canonical, email.trim(), verifier, now));
            identity.issue(capabilityId, userId, digest, now,
                    now.plus(VERIFICATION_LIFETIME));
            delivery.queueCapability(capabilityId, sealed, now);
            identity.audit(userId, "registration", "pending_verification", now);
        });
    }
}
