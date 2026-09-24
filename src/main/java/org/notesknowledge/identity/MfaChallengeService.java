package org.notesknowledge.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;

import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@IdentityCoreEnabled
final class MfaChallengeService {
    private final MfaRepository repository;
    private final MfaSecretCipher cipher;
    private final TotpEngine totp;
    private final RecoveryCodeService recovery;
    private final IdentityPersistence identity;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final MfaProperties policy;
    private final SecureRandom random = new SecureRandom();

    MfaChallengeService(MfaRepository repository, MfaSecretCipher cipher,
            TotpEngine totp, RecoveryCodeService recovery, IdentityPersistence identity,
            PlatformTransactionManager manager, Clock clock, MfaProperties policy) {
        this.repository = repository;
        this.cipher = cipher;
        this.totp = totp;
        this.recovery = recovery;
        this.identity = identity;
        this.transactions = new TransactionTemplate(manager);
        this.clock = clock;
        this.policy = policy;
    }

    boolean active(UUID userId) {
        return repository.configuration(userId)
                .map(config -> "active".equals(config.state())).orElse(false);
    }

    String begin(UUID userId, HttpServletRequest request) {
        MfaRepository.Configuration config = activeConfiguration(userId);
        byte[] randomId = new byte[32];
        random.nextBytes(randomId);
        String id = Base64.getUrlEncoder().withoutPadding().encodeToString(randomId);
        request.getSession().setAttribute(IdentitySessionState.CHALLENGE_ATTRIBUTE,
                new IdentitySessionState.Challenge(id, userId, config.activatedAt(),
                        clock.instant().plus(policy.challengeLifetime())));
        return id;
    }

    void completeTotp(UUID userId, String id, String proof, HttpServletRequest request) {
        MfaRepository.Configuration config = validate(userId, id, request);
        byte[] seed = cipher.open(userId, config.seed());
        long step;
        try { step = totp.matchingStep(seed, proof, clock.instant(), config.lastAcceptedStep()); }
        finally { Arrays.fill(seed, (byte) 0); }
        if (step < 0) {
            identity.audit(userId, "mfa_challenge", "denied", clock.instant());
            deny();
        }
        Instant now = clock.instant();
        Boolean accepted = transactions.execute(status -> {
            if (!identity.isActive(userId)
                    || repository.advanceStep(userId, config.activatedAt(), step) != 1) {
                return false;
            }
            identity.audit(userId, "mfa_challenge", "totp_accepted", now);
            return true;
        });
        if (!Boolean.TRUE.equals(accepted)) {
            identity.audit(userId, "mfa_challenge", "denied", now);
            deny();
        }
    }

    void completeRecovery(UUID userId, String id, String code, HttpServletRequest request) {
        validate(userId, id, request);
        byte[] digest = recovery.digest(code);
        Instant now = clock.instant();
        Boolean accepted = transactions.execute(status -> {
            if (!identity.isActive(userId) || !repository.consumeRecovery(userId, digest, now)) {
                return false;
            }
            identity.audit(userId, "mfa_challenge", "recovery_accepted", now);
            return true;
        });
        if (!Boolean.TRUE.equals(accepted)) {
            identity.audit(userId, "mfa_challenge", "denied", now);
            deny();
        }
    }

    private MfaRepository.Configuration validate(UUID userId, String id,
            HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Object saved = session == null ? null : session.getAttribute(IdentitySessionState.CHALLENGE_ATTRIBUTE);
        if (!(saved instanceof IdentitySessionState.Challenge challenge)) {
            throw ApiFailureException.of(ApiFailureException.Kind.RESOURCE_NOT_FOUND);
        }
        boolean matches = id != null && id.matches("[A-Za-z0-9_-]{43}")
                && MessageDigest.isEqual(challenge.id().getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                        id.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        if (!matches || !userId.equals(challenge.userId())
                || !challenge.expiresAt().isAfter(clock.instant()) || !identity.isActive(userId)) {
            session.removeAttribute(IdentitySessionState.CHALLENGE_ATTRIBUTE);
            throw ApiFailureException.of(ApiFailureException.Kind.RESOURCE_NOT_FOUND);
        }
        MfaRepository.Configuration config = activeConfiguration(userId);
        if (!challenge.activation().equals(config.activatedAt())) {
            session.removeAttribute(IdentitySessionState.CHALLENGE_ATTRIBUTE);
            throw ApiFailureException.of(ApiFailureException.Kind.RESOURCE_NOT_FOUND);
        }
        return config;
    }

    private MfaRepository.Configuration activeConfiguration(UUID userId) {
        return repository.configuration(userId).filter(c -> "active".equals(c.state()))
                .orElseThrow(() -> ApiFailureException.of(ApiFailureException.Kind.RESOURCE_NOT_FOUND));
    }

    private void deny() {
        throw ApiFailureException.of(ApiFailureException.Kind.INVALID_CREDENTIALS);
    }
}
