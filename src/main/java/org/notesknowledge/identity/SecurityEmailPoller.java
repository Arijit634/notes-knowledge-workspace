package org.notesknowledge.identity;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.annotation.PreDestroy;

import org.notesknowledge.LeaseOwner;
import org.notesknowledge.LeasePolicy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Bounded in-process admission; only PostgreSQL rows are durable work. */
@Component
@IdentityCoreEnabled
@ConditionalOnProperty(prefix = "identity.delivery", name = "polling-enabled", havingValue = "true")
final class SecurityEmailPoller {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(SecurityEmailPoller.class);
    private final SecurityEmailDeliveryRepository delivery;
    private final SecurityEmailWorker worker;
    private final Clock clock;
    private final LeaseOwner owner;
    private final LeasePolicy policy;
    private final SecurityEmailDeliveryProperties settings;
    private final ThreadPoolExecutor executor;
    private final Semaphore admission;
    private boolean reclaimFirst;
    private boolean stopping;
    @org.springframework.context.event.EventListener
    synchronized void stopClaiming(org.springframework.context.event.ContextClosedEvent event){stopping=true;}

    SecurityEmailPoller(SecurityEmailDeliveryRepository delivery, SecurityEmailWorker worker,
            Clock clock, SecurityEmailDeliveryProperties settings) {
        this.delivery = delivery;
        this.worker = worker;
        this.clock = clock;
        this.settings = settings;
        this.owner = new LeaseOwner(settings.workerAlias());
        this.policy = new LeasePolicy(settings.leaseDuration(), settings.batchSize());
        this.admission = new Semaphore(settings.workerConcurrency() + settings.queueCapacity());
        var sequence = new AtomicInteger();
        this.executor = new ThreadPoolExecutor(settings.workerConcurrency(),
                settings.workerConcurrency(), 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(settings.queueCapacity()), task -> {
                    Thread thread = new Thread(task, "security-email-" + sequence.incrementAndGet());
                    thread.setDaemon(false);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    @Scheduled(fixedDelayString = "${identity.delivery.poll-delay}")
    synchronized void poll() {
        if(stopping)return;
        try {
            pollAvailable();
        } catch (org.springframework.dao.DataAccessException | org.springframework.transaction.TransactionException unavailable) {
            // No claim/effect is reported successful. A later scheduled poll or
            // PostgreSQL lease recovery owns progress when the database returns.
            LOG.warn("security_email_poll_unavailable");
        }
    }

    private void pollAvailable() {
        var now = clock.instant();
        delivery.failExhausted(now, policy.maximumBatchSize());
        int reserved = 0;
        while (reserved < settings.batchSize() && admission.tryAcquire()) {
            reserved++;
        }
        if (reserved == 0) {
            return;
        }
        List<SecurityEmailDeliveryRepository.Claim> claims = new ArrayList<>(reserved);
        int handedOff = 0;
        try {
            // Reserve one place for each path when possible. With one place, alternate
            // priority. Empty reservations are filled from the other distinct SQL path.
            int reclaimBudget = reserved == 1 ? (reclaimFirst ? 1 : 0) : 1;
            reclaimFirst = !reclaimFirst;
            if (reclaimBudget > 0) {
                claims.addAll(delivery.reclaimExpired(now, owner, policy, reclaimBudget));
            }
            int readyBudget = reserved - claims.size();
            if (readyBudget > 0) {
                claims.addAll(delivery.claimReady(now, owner, policy, readyBudget));
            }
            int remaining = reserved - claims.size();
            if (remaining > 0) {
                claims.addAll(delivery.reclaimExpired(clock.instant(), owner, policy, remaining));
            }
            for (var claim : claims) {
                try {
                    executor.execute(() -> {
                        try { worker.process(claim); }
                        catch (org.springframework.dao.DataAccessException | org.springframework.transaction.TransactionException unavailable) {
                            LOG.warn("security_email_worker_deferred");
                        } catch (RuntimeException failed) {
                            // Default uncaught-thread stderr can expose private exception
                            // payloads outside structured logging. Retain an ERROR signal,
                            // leave the durable fence/state untouched, and do not retry here.
                            LOG.error("security_email_worker_failed");
                        }
                        finally { admission.release(); }
                    });
                    handedOff++;
                } catch (RejectedExecutionException exception) {
                    break;
                }
            }
        } finally {
            // Any claim not handed to a worker returns to ready under its current
            // fence; if its lease expired meanwhile, normal reclaim recovers it.
            try {
                for (int index = handedOff; index < claims.size(); index++) {
                    delivery.releaseUnstarted(claims.get(index), clock.instant());
                }
            } finally {
                admission.release(reserved - handedOff);
            }
        }
    }

    @PreDestroy
    synchronized void stop() {
        stopping=true;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(settings.providerTimeout().toMillis(),
                    TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
