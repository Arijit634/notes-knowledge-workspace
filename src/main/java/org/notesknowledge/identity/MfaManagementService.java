package org.notesknowledge.identity;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@IdentityCoreEnabled
final class MfaManagementService {
    record Setup(String enrollmentId, String manualSecret, String otpauthUri) { }
    private final MfaRepository repository;
    private final MfaSecretCipher cipher;
    private final MfaEnrollmentHandle handles;
    private final TotpEngine totp;
    private final RecoveryCodeService recovery;
    private final IdentityPersistence identity;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final MfaProperties policy;
    private final SecureRandom random = new SecureRandom();

    MfaManagementService(MfaRepository repository, MfaSecretCipher cipher,
            MfaEnrollmentHandle handles, TotpEngine totp, RecoveryCodeService recovery,
            IdentityPersistence identity, org.springframework.transaction.PlatformTransactionManager manager,
            Clock clock, MfaProperties policy) {
        this.repository = repository;
        this.cipher = cipher;
        this.handles = handles;
        this.totp = totp;
        this.recovery = recovery;
        this.identity = identity;
        this.transactions = new TransactionTemplate(manager);
        this.clock = clock;
        this.policy = policy;
    }

    Setup begin(UUID userId) {
        if (!identity.isActive(userId) || repository.configuration(userId)
                .map(c -> "active".equals(c.state())).orElse(false)) {
            throw ApiFailureException.of(ApiFailureException.Kind.INVALID_LIFECYCLE_TRANSITION);
        }
        byte[] seed = new byte[20];
        random.nextBytes(seed);
        MfaSecretCipher.Envelope envelope = cipher.seal(userId, seed);
        String handle = handles.forPending(userId, envelope.nonce());
        Instant now = clock.instant();
        try {
            transactions.executeWithoutResult(status -> {
                if (!identity.isActive(userId) || repository.begin(userId, envelope, now) != 1) {
                    throw ApiFailureException.of(ApiFailureException.Kind.INVALID_LIFECYCLE_TRANSITION);
                }
                identity.audit(userId, "mfa_enrollment", "pending", now);
            });
            String secret = base32(seed);
            return new Setup(handle, secret, "otpauth://totp/Notes%20Knowledge%20Workspace"
                    + "?secret=" + secret + "&issuer=Notes%20Knowledge%20Workspace"
                    + "&algorithm=SHA1&digits=6&period=30");
        } finally {
            Arrays.fill(seed, (byte) 0);
        }
    }

    List<String> confirm(UUID userId, String handle, String proof) {
        Instant now = clock.instant();
        MfaRepository.Configuration pending = repository.configuration(userId).orElseThrow(() ->
                ApiFailureException.of(ApiFailureException.Kind.RESOURCE_NOT_FOUND));
        if (!identity.isActive(userId) || !"enrollment_pending".equals(pending.state())
                || !handles.matches(handle, userId, pending.seed().nonce())
                || !pending.enrolledAt().plus(policy.enrollmentLifetime()).isAfter(now)) {
            throw ApiFailureException.of(ApiFailureException.Kind.RESOURCE_NOT_FOUND);
        }
        byte[] seed = cipher.open(userId, pending.seed());
        long step;
        try { step = totp.matchingStep(seed, proof, now, null); }
        finally { Arrays.fill(seed, (byte) 0); }
        if (step < 0) {
            identity.audit(userId, "mfa_enrollment", "denied", now);
            throw ApiFailureException.of(ApiFailureException.Kind.INVALID_INPUT);
        }
        List<String> codes = recovery.issue();
        transactions.executeWithoutResult(status -> {
            if (!identity.isActive(userId)
                    || repository.activate(userId, pending.seed().nonce(), step, now) != 1) {
                throw ApiFailureException.of(ApiFailureException.Kind.INVALID_LIFECYCLE_TRANSITION);
            }
            for (String code : codes) {
                repository.insertRecovery(userId, 1, recovery.digest(code), now);
            }
            identity.audit(userId, "mfa_enrollment", "activated", now);
        });
        return codes;
    }

    private static String base32(byte[] bytes) {
        final char[] alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
        StringBuilder result = new StringBuilder();
        int bits = 0;
        int value = 0;
        for (byte item : bytes) {
            value = (value << 8) | (item & 0xff);
            bits += 8;
            while (bits >= 5) {
                result.append(alphabet[(value >>> (bits - 5)) & 31]);
                bits -= 5;
            }
        }
        if (bits > 0) result.append(alphabet[(value << (5 - bits)) & 31]);
        return result.toString();
    }
}
