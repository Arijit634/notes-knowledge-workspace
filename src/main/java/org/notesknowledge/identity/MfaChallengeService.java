package org.notesknowledge.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
import org.springframework.security.core.context.SecurityContextHolder;
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
    private final MfaSessionTransitions sessions;
    private final IdentitySessionTransitionCheckpoint checkpoint;
    private final SecureRandom random = new SecureRandom();

    MfaChallengeService(MfaRepository repository, MfaSecretCipher cipher,
            TotpEngine totp, RecoveryCodeService recovery, IdentityPersistence identity,
            PlatformTransactionManager manager, Clock clock, MfaProperties policy,
            MfaSessionTransitions sessions, IdentitySessionTransitionCheckpoint checkpoint) {
        this.repository = repository;
        this.cipher = cipher;
        this.totp = totp;
        this.recovery = recovery;
        this.identity = identity;
        this.transactions = new TransactionTemplate(manager);
        this.clock = clock;
        this.policy = policy;
        this.sessions = sessions;
        this.checkpoint = checkpoint;
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
                        clock.instant().plus(policy.challengeLifetime()), 0));
        return id;
    }

    void completeTotp(UUID userId, String id, String proof, HttpServletRequest request,
            HttpServletResponse response) {
        MfaRepository.Configuration config = validate(userId, id, request);
        byte[] seed = cipher.open(userId, config.seed());
        long step;
        try { step = totp.matchingStep(seed, proof, clock.instant(), config.lastAcceptedStep()); }
        finally { Arrays.fill(seed, (byte) 0); }
        if (step < 0) {
            denyProof(userId, request);
        }
        Instant now = clock.instant();
        Boolean accepted = complete(userId, request, response, now, "totp_accepted",
                () -> repository.advanceStep(userId, config.activatedAt(), step) == 1);
        if (!Boolean.TRUE.equals(accepted)) {
            denyProof(userId, request);
        }
    }

    void completeRecovery(UUID userId, String id, String code, HttpServletRequest request,
            HttpServletResponse response) {
        validate(userId, id, request);
        byte[] digest = recovery.digest(code);
        Instant now = clock.instant();
        Boolean accepted = complete(userId, request, response, now, "recovery_accepted",
                () -> repository.consumeRecovery(userId, digest, now));
        if (!Boolean.TRUE.equals(accepted)) {
            denyProof(userId, request);
        }
    }

    private Boolean complete(UUID userId, HttpServletRequest request,
            HttpServletResponse response, Instant now, String outcome,
            java.util.function.BooleanSupplier consume) {
        boolean[] attemptedAuthorityChange = {false};
        try {
            return transactions.execute(status -> {
                if (!identity.isActive(userId) || !consume.getAsBoolean()) {
                    return false;
                }
                identity.audit(userId, "mfa_challenge", outcome, now);
                attemptedAuthorityChange[0] = true;
                sessions.establish(userId, true, request, response);
                checkpoint.afterSessionMutation(request);
                return true;
            });
        } catch (RuntimeException failure) {
            if (attemptedAuthorityChange[0]) {
                // A request wrapper may retain mutated in-memory state after rollback.
                // Invalidate it so end-of-request saving cannot publish full authority.
                try {
                    HttpSession session = request.getSession(false);
                    if (session != null) session.invalidate();
                } finally {
                    SecurityContextHolder.clearContext();
                }
            }
            throw failure;
        }
    }

    private void denyProof(UUID userId, HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            Object saved = session.getAttribute(IdentitySessionState.CHALLENGE_ATTRIBUTE);
            if (saved instanceof IdentitySessionState.Challenge challenge) {
                IdentitySessionState.Challenge failed = challenge.failed();
                if (failed.failedAttempts() >= policy.maxChallengeFailures()) {
                    session.removeAttribute(IdentitySessionState.CHALLENGE_ATTRIBUTE);
                } else {
                    session.setAttribute(IdentitySessionState.CHALLENGE_ATTRIBUTE, failed);
                }
            }
        }
        identity.audit(userId, "mfa_challenge", "denied", clock.instant());
        deny();
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
                || challenge.failedAttempts() >= policy.maxChallengeFailures()
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
