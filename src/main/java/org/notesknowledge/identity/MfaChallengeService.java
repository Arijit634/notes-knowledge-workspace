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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@IdentityCoreEnabled
final class MfaChallengeService {
    private enum Outcome { ACCEPTED, DENIED, MISSING }

    private final MfaRepository repository;
    private final MfaSessionChallengeRepository challenges;
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

    MfaChallengeService(MfaRepository repository, MfaSessionChallengeRepository challenges,
            MfaSecretCipher cipher, TotpEngine totp, RecoveryCodeService recovery,
            IdentityPersistence identity, PlatformTransactionManager manager, Clock clock,
            MfaProperties policy, MfaSessionTransitions sessions,
            IdentitySessionTransitionCheckpoint checkpoint) {
        this.repository = repository;
        this.challenges = challenges;
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
        return begin(userId, request, "password");
    }

    String begin(UUID userId, HttpServletRequest request, String primaryMethod) {
        MfaRepository.Configuration config = activeConfiguration(userId);
        byte[] randomId = new byte[32];
        random.nextBytes(randomId);
        String id = Base64.getUrlEncoder().withoutPadding().encodeToString(randomId);
        request.getSession().setAttribute(IdentitySessionState.CHALLENGE_ATTRIBUTE,
                new IdentitySessionState.Challenge(id, userId, config.activatedAt(),
                        clock.instant().plus(policy.challengeLifetime()), 0, primaryMethod));
        return id;
    }

    void completeTotp(UUID userId, String id, String proof, HttpServletRequest request,
            HttpServletResponse response) {
        complete(userId, id, request, response, "totp_accepted", config -> {
            byte[] seed = cipher.open(userId, config.seed());
            long step;
            try {
                step = totp.matchingStep(seed, proof, clock.instant(), config.lastAcceptedStep());
            } finally {
                Arrays.fill(seed, (byte) 0);
            }
            return step >= 0 && repository.advanceStep(userId, config.activatedAt(), step) == 1;
        });
    }

    void completeRecovery(UUID userId, String id, String code, HttpServletRequest request,
            HttpServletResponse response) {
        complete(userId, id, request, response, "recovery_accepted", config ->
                repository.consumeRecovery(userId, recovery.digest(code), clock.instant()));
    }

    private void complete(UUID userId, String id, HttpServletRequest request,
            HttpServletResponse response, String acceptedOutcome,
            java.util.function.Predicate<MfaRepository.Configuration> consume) {
        HttpSession requestSession = request.getSession(false);
        if (requestSession == null) missing();
        // Test coordination occurs after the request's Spring Session snapshot is loaded.
        checkpoint.beforeChallengeLock(request);
        boolean[] mutatedRequestSession = {false};
        Outcome outcome;
        try {
            outcome = transactions.execute(status -> {
                IdentitySessionState.Challenge current = challenges.lockAndRead(
                        requestSession.getId(), userId);
                if (current == null) {
                    // The winning request may already have rotated this session ID.
                    // Detach, never invalidate: the stable primary key can now
                    // belong to the winning rotated session.
                    challenges.discardStaleRequestSession(request);
                    return Outcome.MISSING;
                }
                Instant now = clock.instant();
                boolean matches = id != null && id.matches("[A-Za-z0-9_-]{43}")
                        && MessageDigest.isEqual(current.id().getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                                id.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                if (!matches || !userId.equals(current.userId())
                        || current.failedAttempts() >= policy.maxChallengeFailures()
                        || !current.expiresAt().isAfter(now) || !identity.isActive(userId)) {
                    requestSession.removeAttribute(IdentitySessionState.CHALLENGE_ATTRIBUTE);
                    mutatedRequestSession[0] = true;
                    return Outcome.MISSING;
                }
                MfaRepository.Configuration config = activeConfiguration(userId);
                if (!current.activation().equals(config.activatedAt())) {
                    requestSession.removeAttribute(IdentitySessionState.CHALLENGE_ATTRIBUTE);
                    mutatedRequestSession[0] = true;
                    return Outcome.MISSING;
                }
                // Synchronize the request wrapper before writing so its eventual save
                // cannot publish the stale failed-attempt count it loaded at entry.
                requestSession.setAttribute(IdentitySessionState.CHALLENGE_ATTRIBUTE, current);
                mutatedRequestSession[0] = true;
                if (!consume.test(config)) {
                    IdentitySessionState.Challenge failed = current.failed();
                    if (failed.failedAttempts() >= policy.maxChallengeFailures()) {
                        requestSession.removeAttribute(IdentitySessionState.CHALLENGE_ATTRIBUTE);
                    } else {
                        requestSession.setAttribute(IdentitySessionState.CHALLENGE_ATTRIBUTE, failed);
                    }
                    identity.audit(userId, "mfa_challenge", "denied", now);
                    return Outcome.DENIED;
                }
                identity.audit(userId, "mfa_challenge", acceptedOutcome, now);
                sessions.establish(userId, true, request, response);
                checkpoint.afterSessionMutation(request);
                return Outcome.ACCEPTED;
            });
        } catch (RuntimeException failure) {
            if (mutatedRequestSession[0]) {
                // A JDBC rollback does not roll back the request wrapper's Java state.
                try {
                    requestSession.invalidate();
                } finally {
                    SecurityContextHolder.clearContext();
                }
            }
            throw failure;
        }
        if (outcome == Outcome.MISSING) missing();
        if (outcome == Outcome.DENIED) deny();
    }

    private MfaRepository.Configuration activeConfiguration(UUID userId) {
        return repository.configuration(userId).filter(c -> "active".equals(c.state()))
                .orElseThrow(() -> ApiFailureException.of(ApiFailureException.Kind.RESOURCE_NOT_FOUND));
    }

    private void missing() {
        throw ApiFailureException.of(ApiFailureException.Kind.RESOURCE_NOT_FOUND);
    }

    private void deny() {
        throw ApiFailureException.of(ApiFailureException.Kind.INVALID_CREDENTIALS);
    }
}
