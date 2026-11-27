package org.notesknowledge.knowledge;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.notesknowledge.DatabaseIdleTestFixture;
import org.springframework.beans.factory.ObjectProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("FAST") @Tag("SECURITY")
class IdleSchedulingTest {
    @Test void constrainedWorkerDoesNotSpendAnotherClaimWhileItsOnlySlotIsBusy() throws Exception {
        var reconciler=mock(DerivationReconciler.class);var work=mock(KnowledgeWorkService.class);
        var executor=mock(DerivationExecutor.class);var embedding=mock(TextEmbeddingPort.class);
        @SuppressWarnings("unchecked") ObjectProvider<TextEmbeddingPort> providers=mock(ObjectProvider.class);
        when(providers.getIfAvailable()).thenReturn(embedding);when(embedding.available()).thenReturn(true);
        var claim=mock(KnowledgeWork.Claim.class);when(work.reclaim(any(),eq(1))).thenReturn(java.util.List.of(claim));
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        doAnswer(call->{entered.countDown();if(!release.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("Synthetic work not released");return null;}).when(executor).execute(claim);
        var meters=new SimpleMeterRegistry();
        try(var poller=new DerivationSchedulingConfiguration(reconciler,work,executor,providers,meters,1)) {
            poller.poll();assertThat(entered.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            poller.poll();verify(work,times(1)).reclaim(any(),eq(1));verify(work,never()).claim(any(),anyInt());
            release.countDown();
        }finally{release.countDown();meters.close();}
    }

    @Test void operatorCannotSelectAnUnboundedWorkerCount() {
        for(int capacity:new int[]{0,3,Integer.MAX_VALUE}) {
            assertThatThrownBy(()->new DerivationSchedulingConfiguration(null,null,null,null,null,capacity))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid derivation worker capacity");
        }
    }
    @Test void idlePollersDoNotTouchDatabaseAndAnonymousRequestResumesNormalClaims(){
        var fixture=DatabaseIdleTestFixture.create();var operations=mock(KnowledgeOperationService.class);
        when(operations.databaseAvailable()).thenReturn(true);when(operations.claim(2)).thenReturn(java.util.List.of());
        var publicWorker=mock(PublicKnowledgeWorker.class);when(publicWorker.reconciliationComplete()).thenReturn(true);
        var history=mock(SearchHistoryService.class);when(history.databaseAvailable()).thenReturn(true);
        var meters=new SimpleMeterRegistry();
        try(var privatePoller=new KnowledgeOperationScheduling(operations,meters)){
            privatePoller.idleCoordinator(fixture.coordinator());
            var publicPoller=new PublicKnowledgeScheduling(publicWorker,meters);publicPoller.idleCoordinator(fixture.coordinator());
            var historyPoller=new SearchHistoryMaintenance(history);historyPoller.idleCoordinator(fixture.coordinator());
            fixture.quietBoundary();assertThat(fixture.coordinator().idle()).isTrue();
            privatePoller.poll();publicPoller.poll();historyPoller.expire();verifyNoInteractions(operations,publicWorker,history);
            fixture.request();privatePoller.poll();publicPoller.poll();historyPoller.expire();
            verify(operations).claim(2);verify(publicWorker).poll();verify(history).sweep();
            assertThat(publicPoller.reconciled(fixture.coordinator().activityVersion())).isTrue();
            fixture.request();assertThat(publicPoller.reconciled(fixture.coordinator().activityVersion())).isFalse();
        }finally{meters.close();}
    }
    @Test void privateReconciliationRequiresCompleteInventorySinceLatestActivityAndCanPauseAfterThatCycle(){
        var fixture=DatabaseIdleTestFixture.create();var reconciler=mock(DerivationReconciler.class);
        var work=mock(KnowledgeWorkService.class);var executor=mock(DerivationExecutor.class);var embedding=mock(TextEmbeddingPort.class);
        when(embedding.available()).thenReturn(true);
        @SuppressWarnings("unchecked") ObjectProvider<TextEmbeddingPort> providers=mock(ObjectProvider.class);
        when(providers.getIfAvailable()).thenReturn(embedding);
        var cursor=mock(org.notesknowledge.knowledge.spi.PrivateDerivationSource.Cursor.class);
        when(reconciler.page(null)).thenReturn(cursor);when(reconciler.page(cursor)).thenReturn(null);
        when(work.reclaim(any(),anyInt())).thenReturn(java.util.List.of());when(work.claim(any(),anyInt())).thenReturn(java.util.List.of());
        var meters=new SimpleMeterRegistry();
        try(var poller=new DerivationSchedulingConfiguration(reconciler,work,executor,providers,meters)){
            poller.idleCoordinator(fixture.coordinator());long version=fixture.coordinator().activityVersion();
            poller.poll();assertThat(poller.reconciled(version)).isFalse();
            poller.poll();assertThat(poller.reconciled(version)).isTrue();
            poller.poll();assertThat(poller.reconciled(version)).isTrue();
            fixture.request();assertThat(poller.reconciled(fixture.coordinator().activityVersion())).isFalse();
            poller.poll();assertThat(poller.reconciled(fixture.coordinator().activityVersion())).isFalse();
            poller.poll();poller.poll();assertThat(poller.reconciled(fixture.coordinator().activityVersion())).isTrue();
        }finally{meters.close();}
    }
}
