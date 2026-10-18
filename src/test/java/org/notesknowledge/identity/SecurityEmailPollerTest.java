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
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("FAST")
@Tag("SECURITY")
@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
class SecurityEmailPollerTest {
    @Test void closingContextStopsNewClaimsBeforeExecutorDrain(){
        var repo=mock(SecurityEmailDeliveryRepository.class);var worker=mock(SecurityEmailWorker.class);
        var poller=new SecurityEmailPoller(repo,worker,CLOCK,settings(1,1,1));
        try{poller.stopClaiming(new org.springframework.context.event.ContextClosedEvent(new org.springframework.context.support.GenericApplicationContext()));
            poller.poll();org.mockito.Mockito.verifyNoInteractions(repo,worker);
        }finally{poller.stop();}
    }
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-23T00:00:00Z"),
            ZoneOffset.UTC);

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"database", "transaction"})
    void databasePollFailureIsSanitizedAndAnIndependentPollCanRecover(
            String kind, org.springframework.boot.test.system.CapturedOutput output) {
        var repo = mock(SecurityEmailDeliveryRepository.class);
        var worker = mock(SecurityEmailWorker.class);
        when(repo.failExhausted(any(), anyInt())).thenThrow(failure(kind, "synthetic-private-poll-canary"))
                .thenReturn(0);
        var poller = new SecurityEmailPoller(repo, worker, CLOCK, settings(1, 1, 1));
        try {
            org.assertj.core.api.Assertions.assertThatCode(poller::poll).doesNotThrowAnyException();
            poller.poll();
            verify(repo).claimReady(any(), any(), any(), anyInt());
            org.mockito.Mockito.verifyNoInteractions(worker);
            assertThat(output.getAll()).contains("security_email_poll_unavailable")
                    .doesNotContain("synthetic-private-poll-canary");
        } finally {
            poller.stop();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"database", "transaction", "unexpected"})
    void workerFailureRemainsUnsubmittedAndReleasesAdmissionWithoutRawStderr(
            String kind, org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        var repo = mock(SecurityEmailDeliveryRepository.class);
        var worker = mock(SecurityEmailWorker.class);
        var claim = mock(SecurityEmailDeliveryRepository.Claim.class);
        when(repo.claimReady(any(), any(), any(), anyInt())).thenReturn(List.of(claim));
        org.mockito.Mockito.doThrow(failure(kind, "synthetic-private-worker-canary")).when(worker).process(claim);
        var deferred = new CountDownLatch(1);
        String eventName = kind.equals("unexpected") ? "security_email_worker_failed" : "security_email_worker_deferred";
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(SecurityEmailPoller.class);
        var events = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>() {
            @Override protected void append(ch.qos.logback.classic.spi.ILoggingEvent event) {
                super.append(event);
                if (eventName.equals(event.getFormattedMessage())) deferred.countDown();
            }
        };
        events.start();logger.addAppender(events);
        var poller = new SecurityEmailPoller(repo, worker, CLOCK, settings(1, 1, 1));
        try {
            poller.poll();
            assertThat(deferred.await(10, TimeUnit.SECONDS)).as("safe worker deferral event").isTrue();
        } finally {
            poller.stop();logger.detachAppender(events);events.stop();
        }
        var permits = (Semaphore) org.springframework.test.util.ReflectionTestUtils.getField(poller, "admission");
        assertThat(permits.availablePermits()).isEqualTo(2);
        assertThat(events.list).hasSize(1).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
        assertThat(output.getAll()).doesNotContain("synthetic-private-worker-canary", "Exception in thread");
        verify(repo, org.mockito.Mockito.never()).submitted(any(), any());
        verify(repo, org.mockito.Mockito.never()).releaseUnstarted(any(), any());
    }

    private RuntimeException failure(String kind, String marker) {
        return switch (kind) {
            case "database" -> new org.springframework.dao.DataAccessResourceFailureException(marker);
            case "transaction" -> new org.springframework.transaction.CannotCreateTransactionException(marker);
            default -> new IllegalStateException(marker);
        };
    }

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
