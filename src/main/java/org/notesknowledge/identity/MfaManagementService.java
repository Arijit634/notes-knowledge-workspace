package org.notesknowledge.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.notesknowledge.websupport.ApiFailureException;
import org.notesknowledge.DatabaseUuidV7Generator;
import org.springframework.security.core.context.SecurityContextHolder;
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
    private final SecurityEmailDeliveryRepository delivery;
    private final DatabaseUuidV7Generator ids;
    private final SpringSessionAuthorityAdapter sessions;
    private final MfaSessionTransitions transitions;
    private final MfaSessionChallengeRepository staleRequests;
    private final IdentitySessionTransitionCheckpoint checkpoint;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final MfaProperties policy;
    private final SecureRandom random = new SecureRandom();

    MfaManagementService(MfaRepository repository, MfaSecretCipher cipher,
            MfaEnrollmentHandle handles, TotpEngine totp, RecoveryCodeService recovery,
            IdentityPersistence identity, SecurityEmailDeliveryRepository delivery,
            DatabaseUuidV7Generator ids, SpringSessionAuthorityAdapter sessions,
            MfaSessionTransitions transitions, MfaSessionChallengeRepository staleRequests,
            IdentitySessionTransitionCheckpoint checkpoint,
            org.springframework.transaction.PlatformTransactionManager manager,
            Clock clock, MfaProperties policy) {
        this.repository = repository;
        this.cipher = cipher;
        this.handles = handles;
        this.totp = totp;
        this.recovery = recovery;
        this.identity = identity;
        this.delivery = delivery;
        this.ids = ids;
        this.sessions = sessions;
        this.transitions = transitions;
        this.staleRequests = staleRequests;
        this.checkpoint = checkpoint;
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
        return confirm(userId, handle, proof, null, null);
    }

    List<String> confirm(UUID userId, String handle, String proof,
            HttpServletRequest request, HttpServletResponse response) {
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
        String originalSessionId = request == null ? null : request.getSession(false).getId();
        boolean[] sessionMutationStarted = {false};
        try {
        transactions.executeWithoutResult(status -> {
            if (!identity.lockActiveAccount(userId)
                    || repository.activate(userId, pending.seed().nonce(), step, now) != 1) {
                throw ApiFailureException.of(ApiFailureException.Kind.INVALID_LIFECYCLE_TRANSITION);
            }
            if (request != null) {
                var current = sessions.lockCurrent(userId, originalSessionId);
                if (current == null) throw ApiFailureException.of(ApiFailureException.Kind.INVALID_CREDENTIALS);
                IdentitySessionState.requireRecent((Object) current.persisted().getAttribute(
                        IdentitySessionState.RECENT_ATTRIBUTE), userId, now, policy);
            }
            for (String code : codes) {
                repository.insertRecovery(userId, 1, recovery.digest(code), now);
            }
            identity.audit(userId, "mfa_enrollment", "activated", now);
            if (request != null) {
                sessionMutationStarted[0] = true;
                transitions.establish(userId, true, request, response);
                checkpoint.afterSessionMutation(request);
            }
        });
        } catch (RuntimeException failure) {
            if (sessionMutationStarted[0]) {
                staleRequests.discardStaleRequestSession(request);
                SecurityContextHolder.clearContext();
            }
            throw failure;
        }
        return codes;
    }

    void disable(UUID userId, HttpServletRequest request, HttpServletResponse response) {
        securityChange(userId, request, response, false, List.of());
    }

    List<String> regenerateRecovery(UUID userId, HttpServletRequest request,
            HttpServletResponse response) {
        List<String> codes = recovery.issue();
        // Digest computation and entropy generation finish before any authoritative lock.
        List<byte[]> digests = codes.stream().map(recovery::digest).toList();
        securityChange(userId, request, response, true, digests);
        return codes;
    }

    private void securityChange(UUID userId, HttpServletRequest request,
            HttpServletResponse response, boolean regenerate, List<byte[]> digests) {
        HttpSession requestSession = request.getSession(false);
        if (requestSession == null) throw unauthenticated();
        String originalSessionId = requestSession.getId();
        UUID eventId = ids.generate();
        boolean[] sessionMutationStarted = {false};
        try {
            transactions.executeWithoutResult(status -> {
                if (!identity.lockActiveAccount(userId)) throw unauthenticated();
                var current = sessions.lockCurrent(userId, originalSessionId);
                if (current == null) {
                    staleRequests.discardStaleRequestSession(request);
                    throw unauthenticated();
                }
                Instant now = clock.instant();
                Object persistedRecent = current.persisted().getAttribute(
                        IdentitySessionState.RECENT_ATTRIBUTE);
                IdentitySessionState.requireIndependentRecent(
                        persistedRecent, userId, now, policy);
                if (identity.currentPasswordVerifier(userId).isEmpty()) {
                    throw ApiFailureException.of(
                            ApiFailureException.Kind.RECENT_AUTHENTICATION_REQUIRED);
                }
                var active = repository.activeForUpdate(userId).orElseThrow(() ->
                        ApiFailureException.of(
                                ApiFailureException.Kind.INVALID_LIFECYCLE_TRANSITION));
                if (regenerate) {
                    long next;
                    try {
                        next = Math.addExact(active.recoveryGeneration(), 1);
                    } catch (ArithmeticException overflow) {
                        throw ApiFailureException.of(
                                ApiFailureException.Kind.INVALID_LIFECYCLE_TRANSITION);
                    }
                    repository.revokeUnusedRecovery(userId, now);
                    if (repository.advanceRecoveryGeneration(userId,
                            active.recoveryGeneration(), next) != 1) {
                        throw new IllegalStateException("Locked MFA generation did not advance");
                    }
                    for (byte[] digest : digests) {
                        repository.insertRecovery(userId, next, digest, now);
                    }
                } else {
                    repository.deleteRecovery(userId);
                    if (repository.deleteActive(userId) != 1) {
                        throw new IllegalStateException("Locked MFA configuration did not delete");
                    }
                }
                checkpoint.afterMfaMutation(request);
                sessions.revokeOthers(userId, current.primaryId());
                checkpoint.afterOtherSessionRevocation(request);
                sessionMutationStarted[0] = true;
                transitions.establish(userId, true, request, response);
                checkpoint.afterSessionMutation(request);
                identity.auditMfaChange(userId, eventId,
                        regenerate ? "mfa_recovery" : "mfa",
                        regenerate ? "regenerated" : "disabled", now);
                checkpoint.afterMfaAudit(request);
                delivery.queueMfaNotice(userId, eventId,
                        regenerate ? "mfa_reset" : "mfa_disabled", now);
                checkpoint.afterMfaNoticeIntent(request);
            });
        } catch (RuntimeException failure) {
            if (sessionMutationStarted[0]) {
                staleRequests.discardStaleRequestSession(request);
                SecurityContextHolder.clearContext();
            }
            throw failure;
        }
    }

    private static ApiFailureException unauthenticated() {
        return ApiFailureException.of(ApiFailureException.Kind.INVALID_CREDENTIALS);
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
