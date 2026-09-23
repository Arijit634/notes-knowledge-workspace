package org.notesknowledge.identity;

import java.time.Clock;
import java.time.Duration;

import org.notesknowledge.LeaseOwner;
import org.notesknowledge.LeasePolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Same-deployable bounded executor, disabled until explicitly configured. */
@Component
@IdentityCoreEnabled
@ConditionalOnProperty(prefix = "identity.delivery", name = "polling-enabled", havingValue = "true")
final class SecurityEmailPoller {
    private final SecurityEmailDeliveryRepository delivery;
    private final SecurityEmailWorker worker;
    private final Clock clock;
    private final LeaseOwner owner;
    private final LeasePolicy policy = new LeasePolicy(Duration.ofMinutes(2), 10);

    SecurityEmailPoller(SecurityEmailDeliveryRepository delivery, SecurityEmailWorker worker,
            Clock clock, @Value("${identity.delivery.worker-alias:identity_worker}") String alias) {
        this.delivery = delivery;
        this.worker = worker;
        this.clock = clock;
        this.owner = new LeaseOwner(alias);
    }

    @Scheduled(fixedDelayString = "${identity.delivery.poll-delay-ms:5000}")
    void poll() {
        var now = clock.instant();
        delivery.failExhausted(now, policy.maximumBatchSize());
        var ready = delivery.claimReady(now, owner, policy, policy.maximumBatchSize());
        for (var claim : ready) {
            worker.process(claim);
        }
        int remaining = policy.maximumBatchSize() - ready.size();
        if (remaining > 0) {
            for (var claim : delivery.reclaimExpired(clock.instant(), owner, policy, remaining)) {
                worker.process(claim);
            }
        }
    }
}
