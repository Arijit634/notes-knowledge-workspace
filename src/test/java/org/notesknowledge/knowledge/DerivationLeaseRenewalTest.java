package org.notesknowledge.knowledge;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("FAST") @Tag("SECURITY")
class DerivationLeaseRenewalTest {
    private static final class ManualScheduler {
        final ScheduledExecutorService executor=mock(ScheduledExecutorService.class);
        final ScheduledFuture<?> ticket=mock(ScheduledFuture.class);
        final AtomicReference<Runnable> pulse=new AtomicReference<>();
        ManualScheduler() {
            doAnswer(call->{pulse.set(call.getArgument(0));return ticket;}).when(executor)
                    .scheduleWithFixedDelay(any(Runnable.class),eq(10L),eq(10L),eq(TimeUnit.SECONDS));
        }
    }

    @Test void renewalUsesOnlyTheExistingClaimAndStopsWithTheAttempt() {
        var manual=new ManualScheduler();var calls=new AtomicInteger();
        try(var renewal=new DerivationLeaseRenewal(()->{calls.incrementAndGet();return true;},manual.executor)) {
            manual.pulse.get().run();renewal.requireCurrent();assertThat(calls).hasValue(1);
            renewal.close();manual.pulse.get().run();assertThat(calls).hasValue(1);
        }
        verify(manual.ticket).cancel(true);verify(manual.executor).shutdownNow();
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test void lostLeaseInterruptsLocalWorkAndCannotReachDispatchOrActivation() throws Exception {
        failedRenewal(false);
    }

    @Test void unavailableHeartbeatFailsClosedWithoutRetainingItsException() throws Exception {
        failedRenewal(true);
    }

    private void failedRenewal(boolean unavailable) throws Exception {
        var manual=new ManualScheduler();var entered=new CountDownLatch(1);
        var closed=new AtomicBoolean();var dispatched=new AtomicInteger();var renewed=new AtomicInteger();
        var interruptAfterClose=new AtomicBoolean(true);
        try(var tasks=Executors.newVirtualThreadPerTaskExecutor()) {
            var result=tasks.submit(()->{
                try(var renewal=new DerivationLeaseRenewal(()->{
                    renewed.incrementAndGet();if(unavailable)throw new IllegalStateException("synthetic-unavailable");return false;
                },manual.executor)) {
                    entered.countDown();
                    try{new CountDownLatch(1).await();}catch(InterruptedException cancelled){Thread.currentThread().interrupt();}
                    assertThatThrownBy(renewal::requireCurrent).isInstanceOfSatisfying(DerivationFailure.class,
                            e->{assertThat(e.category).isEqualTo(KnowledgeWork.Failure.INVALID_SOURCE);assertThat(e.getCause()).isNull();});
                    if(!Thread.currentThread().isInterrupted())dispatched.incrementAndGet();
                }finally{interruptAfterClose.set(Thread.currentThread().isInterrupted());closed.set(true);}
            });
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();manual.pulse.get().run();result.get(5,TimeUnit.SECONDS);
        }
        assertThat(closed).isTrue();assertThat(dispatched).hasValue(0);assertThat(renewed).hasValue(1);
        assertThat(interruptAfterClose).isFalse();
        manual.pulse.get().run();assertThat(renewed).hasValue(1);
    }

    @Test void externalCancellationRemainsInterruptedAfterScopeCleanup() throws Exception {
        var manual=new ManualScheduler();var entered=new CountDownLatch(1);var finished=new CountDownLatch(1);
        var interrupted=new AtomicBoolean();
        try(var tasks=Executors.newVirtualThreadPerTaskExecutor()) {
            var result=tasks.submit(()->{
                try(var renewal=new DerivationLeaseRenewal(()->true,manual.executor)) {
                    entered.countDown();try{new CountDownLatch(1).await();}
                    catch(InterruptedException cancelled){Thread.currentThread().interrupt();}
                }finally{interrupted.set(Thread.currentThread().isInterrupted());finished.countDown();}
            });
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();result.cancel(true);
            assertThat(finished.await(5,TimeUnit.SECONDS)).isTrue();assertThat(interrupted).isTrue();
        }
    }

    @Test void heartbeatFinishingAfterCloseCannotInterruptTheCaller() throws Exception {
        var manual=new ManualScheduler();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var renewal=new DerivationLeaseRenewal(()->{
            entered.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("Synthetic coordination timed out");}
            catch(InterruptedException cancelled){throw new AssertionError(cancelled);}return false;
        },manual.executor);
        try(var tasks=Executors.newVirtualThreadPerTaskExecutor()) {
            var pulse=tasks.submit(manual.pulse.get());assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            renewal.close();release.countDown();pulse.get(5,TimeUnit.SECONDS);
            assertThat(Thread.currentThread().isInterrupted()).isFalse();
        }finally{release.countDown();renewal.close();}
    }
}
