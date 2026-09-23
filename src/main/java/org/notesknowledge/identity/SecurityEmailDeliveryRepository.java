package org.notesknowledge.identity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.notesknowledge.LeaseOwner;
import org.notesknowledge.LeasePolicy;
import org.notesknowledge.LeaseToken;

interface SecurityEmailDeliveryRepository {
    final class Claim {
        private final UUID id;
        private final UUID capabilityId;
        private final LeaseToken token;
        private final int attempt;
        private final SecurityEmailMaterialCipher.Envelope envelope;

        Claim(UUID id, UUID capabilityId, LeaseToken token, int attempt,
                SecurityEmailMaterialCipher.Envelope envelope) {
            this.id = id;
            this.capabilityId = capabilityId;
            this.token = token;
            this.attempt = attempt;
            this.envelope = envelope;
        }

        UUID id() { return id; }
        UUID capabilityId() { return capabilityId; }
        LeaseToken token() { return token; }
        int attempt() { return attempt; }
        SecurityEmailMaterialCipher.Envelope envelope() { return envelope; }

        @Override public String toString() { return "SecurityEmailClaim[REDACTED]"; }
    }

    void queueCapability(UUID capabilityId, SecurityEmailMaterialCipher.Envelope envelope, Instant now);
    List<Claim> claimReady(Instant now, LeaseOwner owner, LeasePolicy policy, int batchSize);
    List<Claim> reclaimExpired(Instant now, LeaseOwner owner, LeasePolicy policy, int batchSize);
    int failExhausted(Instant now, int batchSize);
    boolean ownsUsableClaim(Claim claim, Instant now);
    boolean releaseUnstarted(Claim claim, Instant now);
    boolean deferUnsent(Claim claim, Instant now, Instant retryAt, String safeReason);
    boolean submitted(Claim claim, Instant now);
    boolean obsolete(Claim claim, Instant now, String safeReason);
    boolean retry(Claim claim, Instant now, Instant retryAt, String safeReason);
    boolean failed(Claim claim, Instant now, String safeReason);
}
