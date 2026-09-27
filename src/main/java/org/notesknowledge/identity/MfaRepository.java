package org.notesknowledge.identity;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Identity-private authoritative MFA persistence contract. */
interface MfaRepository {
    record Configuration(UUID userId, String state, MfaSecretCipher.Envelope seed,
            Long lastAcceptedStep, long recoveryGeneration, Instant enrolledAt,
            Instant activatedAt) { }

    Optional<Configuration> configuration(UUID userId);
    Optional<Configuration> activeForUpdate(UUID userId);
    int deleteRecovery(UUID userId);
    int deleteActive(UUID userId);
    int revokeUnusedRecovery(UUID userId, Instant now);
    int advanceRecoveryGeneration(UUID userId, long expected, long next);
    int begin(UUID userId, MfaSecretCipher.Envelope seed, Instant now);
    int activate(UUID userId, byte[] expectedNonce, long acceptedStep, Instant now);
    int advanceStep(UUID userId, Instant expectedActivation, long step);
    void insertRecovery(UUID userId, long generation, byte[] digest, Instant now);
    boolean consumeRecovery(UUID userId, byte[] digest, Instant now);
}
