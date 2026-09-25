package org.notesknowledge.identity;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.notesknowledge.DatabaseUuidV7Generator;
import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@IdentityCoreEnabled
final class PasswordRecoveryService {
    private static final Duration RESET_LIFETIME = Duration.ofHours(1);
    private final IdentityPersistence identity;
    private final SecurityEmailDeliveryRepository delivery;
    private final SecurityEmailMaterialCipher cipher;
    private final SpringSessionAuthorityAdapter sessions;
    private final DatabaseUuidV7Generator ids;
    private final PasswordEncoder passwords;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final TransactionTemplate denialAuditTransactions;

    PasswordRecoveryService(IdentityPersistence identity,
            SecurityEmailDeliveryRepository delivery, SecurityEmailMaterialCipher cipher,
            SpringSessionAuthorityAdapter sessions, DatabaseUuidV7Generator ids,
            PasswordEncoder passwords, Clock clock,
            PlatformTransactionManager transactionManager) {
        this.identity = identity;
        this.delivery = delivery;
        this.cipher = cipher;
        this.sessions = sessions;
        this.ids = ids;
        this.passwords = passwords;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
        this.denialAuditTransactions = new TransactionTemplate(transactionManager);
        this.denialAuditTransactions.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    void request(String email) {
        String canonical = IdentityInput.canonicalEmail(email);
        UUID capabilityId = ids.generate();
        String token = PasswordResetToken.issue(capabilityId);
        byte[] digest = PasswordResetToken.digest(token);
        var sealed = cipher.seal("password_reset", capabilityId, token);
        transactions.executeWithoutResult(status -> {
            var eligible = identity.activeAccountForUpdate(canonical);
            if (eligible.isEmpty()) return;
            UUID userId = eligible.get();
            Instant now = clock.instant();
            identity.supersedeReset(userId, now);
            identity.issueReset(capabilityId, userId, digest, now,
                    now.plus(RESET_LIFETIME));
            delivery.queueCapability(capabilityId, sealed, now);
            identity.audit(userId, "password_reset", "requested", now);
        });
    }

    void confirm(String token, String newPassword) {
        final UUID id;
        try {
            id = PasswordResetToken.locator(token);
        } catch (IllegalArgumentException exception) {
            throw ApiFailureException.of(ApiFailureException.Kind.INVALID_INPUT);
        }
        IdentityInput.password(newPassword);
        byte[] digest = PasswordResetToken.digest(token);
        var candidate = identity.capability(id).orElseThrow(PasswordRecoveryService::invalidReset);
        if (!"password_reset".equals(candidate.purpose())) {
            throw invalidReset();
        }
        if (!MessageDigest.isEqual(candidate.digest(), digest)) {
            deny(candidate.userId(), "invalid_proof");
        }
        // Argon2 and any key preparation stay outside the account/session row locks.
        String verifier = passwords.encode(newPassword);
        String denial = transactions.execute(status -> {
            if (!identity.lockActiveAccount(candidate.userId())) return "account_ineligible";
            Instant now = clock.instant();
            var state = identity.resetStateForUpdate(id).orElse(null);
            if (state == null) return "invalid_proof";
            if (state.consumedAt() != null) return "replayed";
            if (state.supersededAt() != null) return "superseded";
            if (state.revokedAt() != null) return "invalid_proof";
            if (!state.expiresAt().isAfter(now)) return "expired";
            if (identity.consumeReset(id, digest, now) != 1) return "invalid_proof";
            if (identity.replacePassword(candidate.userId(), verifier, now) != 1) {
                throw new IllegalStateException("Locked account became ineligible");
            }
            identity.obsoleteConsumedReset(id, now);
            sessions.revokeAll(candidate.userId());
            UUID eventId = ids.generate();
            identity.auditResetCompleted(candidate.userId(), eventId, now);
            delivery.queueResetNotice(candidate.userId(), eventId, now);
            return null;
        });
        if (denial != null) deny(candidate.userId(), denial);
    }

    private void deny(UUID userId, String reason) {
        denialAuditTransactions.executeWithoutResult(status ->
                identity.auditFailure(userId, "password_reset", reason, clock.instant()));
        throw invalidReset();
    }

    private static ApiFailureException invalidReset() {
        return ApiFailureException.of(ApiFailureException.Kind.INVALID_LIFECYCLE_TRANSITION);
    }
}
