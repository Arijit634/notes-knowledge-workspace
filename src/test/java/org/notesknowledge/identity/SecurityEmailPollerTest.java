package org.notesknowledge.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("FAST")
class SecurityEmailPollerTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-23T00:00:00Z"),
            ZoneOffset.UTC);

    @Test
    void fullReadyQueueStillAdmitsExpiredClaim() {
        var repo = mock(SecurityEmailDeliveryRepository.class);
        var worker = mock(SecurityEmailWorker.class);
        var expired = mock(SecurityEmailDeliveryRepository.Claim.class);
        var ready = mock(SecurityEmailDeliveryRepository.Claim.class);
        var completed = new CountDownLatch(2);
        when(repo.reclaimExpired(any(), any(), any(), anyInt()))
                .thenReturn(List.of(expired));
        when(repo.claimReady(any(), any(), any(), anyInt()))
                .thenReturn(List.of(ready));
        org.mockito.Mockito.doAnswer(invocation -> {
            completed.countDown();
            return null;
        }).when(worker).process(any());
        var poller = new SecurityEmailPoller(repo, worker, CLOCK, settings(2, 1, 1));
        try {
            poller.poll();
            assertThat(completed.await(2, TimeUnit.SECONDS)).isTrue();
            verify(worker).process(expired);
            verify(worker).process(ready);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        } finally {
            poller.stop();
        }
    }

    @Test
    void singleAdmissionAlternatesReadyAndExpiredUnderContinuousReadyLoad() throws Exception {
        var repo = mock(SecurityEmailDeliveryRepository.class);
        var worker = mock(SecurityEmailWorker.class);
        var ready = mock(SecurityEmailDeliveryRepository.Claim.class);
        var expired = mock(SecurityEmailDeliveryRepository.Claim.class);
        when(repo.claimReady(any(), any(), any(), anyInt())).thenReturn(List.of(ready));
        when(repo.reclaimExpired(any(), any(), any(), anyInt()))
                .thenReturn(List.of(expired));
        var processed = new CountDownLatch(2);
        org.mockito.Mockito.doAnswer(invocation -> {
            processed.countDown();
            return null;
        }).when(worker).process(any());
        var poller = new SecurityEmailPoller(repo, worker, CLOCK, settings(1, 1, 1));
        try {
            poller.poll();
            poller.poll();
            assertThat(processed.await(2, TimeUnit.SECONDS)).isTrue();
            verify(worker).process(ready);
            verify(worker).process(expired);
        } finally {
            poller.stop();
        }
    }

    @Test
    void schedulerDoesNotWaitForProviderAndFullAdmissionDoesNotClaimMore() throws Exception {
        var repo = mock(SecurityEmailDeliveryRepository.class);
        var worker = mock(SecurityEmailWorker.class);
        var first = mock(SecurityEmailDeliveryRepository.Claim.class);
        var second = mock(SecurityEmailDeliveryRepository.Claim.class);
        when(repo.reclaimExpired(any(), any(), any(), anyInt())).thenReturn(List.of());
        when(repo.claimReady(any(), any(), any(), anyInt()))
                .thenReturn(List.of(first, second));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var active = new AtomicInteger();
        var maximum = new AtomicInteger();
        org.mockito.Mockito.doAnswer(invocation -> {
            int running = active.incrementAndGet();
            maximum.accumulateAndGet(running, Math::max);
            entered.countDown();
            try { release.await(2, TimeUnit.SECONDS); }
            finally { active.decrementAndGet(); }
            return null;
        }).when(worker).process(any());
        var poller = new SecurityEmailPoller(repo, worker, CLOCK, settings(3, 1, 1));
        try {
            poller.poll(); // returns with one active and one bounded queued worker
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            poller.poll(); // no free admission permit; must not claim another row
            verify(repo, org.mockito.Mockito.times(1)).claimReady(any(), any(), any(), anyInt());
            assertThat(maximum).hasValue(1);
        } finally {
            release.countDown();
            poller.stop();
        }
        verify(worker, atLeastOnce()).process(first);
    }

    private SecurityEmailDeliveryProperties settings(int batch, int workers, int queue) {
        return new SecurityEmailDeliveryProperties(batch, workers, queue,
                Duration.ofSeconds(5), Duration.ofSeconds(1), 5,
                Duration.ofSeconds(1), Duration.ofMinutes(1), Duration.ofMillis(500),
                Duration.ofSeconds(1),
                "synthetic_worker");
    }
}
