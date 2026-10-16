package org.notesknowledge.knowledge;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.concurrent.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

/** Fault injection at real executor boundaries; no live provider or database. */
@Tag("FAST") @Tag("SECURITY")
@ExtendWith(OutputCaptureExtension.class)
class KnowledgeSchedulingPrivacyTest {
    @ParameterizedTest @ValueSource(booleans={false,true})
    void operationWorkerUnexpectedFailureCannotEscapeToRawStderr(boolean telemetryFails,CapturedOutput output)throws Exception {
        var service=mock(KnowledgeOperationService.class);var row=mock(KnowledgeOperationRepository.Row.class);
        when(service.databaseAvailable()).thenReturn(true);when(service.claim(2)).thenReturn(List.of(row));
        doThrow(new IllegalStateException("SYNTHETIC_PRIVATE_OPERATION_PAYLOAD")).when(service).execute(row);
        var escaped=new ConcurrentLinkedQueue<RuntimeException>();var exited=new CountDownLatch(1);
        var registry=registry(telemetryFails);
        try(var scheduling=new KnowledgeOperationScheduling(service,registry)) {
            observeWorkers(scheduling,escaped,exited);scheduling.poll();
        } finally {registry.close();}
        assertThat(exited.await(5,TimeUnit.SECONDS)).isTrue();
        assertThat(escaped).as("worker RuntimeException must not reach Thread's raw uncaught-exception path").isEmpty();
        assertThat(output.getAll()).contains("knowledge_operation_worker_failed").doesNotContain("SYNTHETIC_PRIVATE_OPERATION_PAYLOAD","SYNTHETIC_PRIVATE_METRIC_PAYLOAD");
        verify(service,times(1)).execute(row);verifyNoMoreInteractionsBeyondClaim(service);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void derivationWorkerUnexpectedFailureRemainsLeasedWithoutRawStderr(boolean telemetryFails,CapturedOutput output)throws Exception {
        var reconciler=mock(DerivationReconciler.class);var work=mock(KnowledgeWorkService.class);var executor=mock(DerivationExecutor.class);
        var claim=mock(KnowledgeWork.Claim.class);var embedding=mock(TextEmbeddingPort.class);
        @SuppressWarnings("unchecked") ObjectProvider<TextEmbeddingPort> providers=mock(ObjectProvider.class);
        when(providers.getIfAvailable()).thenReturn(embedding);when(embedding.available()).thenReturn(true);
        when(work.reclaim(any(),eq(2))).thenReturn(List.of(claim));
        doThrow(new IllegalStateException("SYNTHETIC_PRIVATE_DERIVATION_PAYLOAD")).when(executor).execute(claim);
        var escaped=new ConcurrentLinkedQueue<RuntimeException>();var exited=new CountDownLatch(1);
        var registry=registry(telemetryFails);
        try(var scheduling=new DerivationSchedulingConfiguration(reconciler,work,executor,providers,registry)) {
            observeWorkers(scheduling,escaped,exited);scheduling.poll();
        } finally {registry.close();}
        assertThat(exited.await(5,TimeUnit.SECONDS)).isTrue();assertThat(escaped).isEmpty();
        assertThat(output.getAll()).contains("knowledge_derivation_worker_failed").doesNotContain("SYNTHETIC_PRIVATE_DERIVATION_PAYLOAD","SYNTHETIC_PRIVATE_METRIC_PAYLOAD");
        verify(executor,times(1)).execute(claim);
        verify(work,never()).retry(any());verify(work,never()).obsolete(any());verify(work,never()).fail(any(),any());
    }
    private static void observeWorkers(Object scheduling,Queue<RuntimeException> escaped,CountDownLatch exited) {
        var pool=(ThreadPoolTaskExecutor)ReflectionTestUtils.getField(scheduling,"pool");
        assertThat(pool).isNotNull();
        // Observe the actual ThreadPoolExecutor worker outside its task wrapper. This
        // intercepts the raw uncaught path deterministically without printing a payload
        // or racing an asynchronous default exception handler's stderr writes.
        pool.getThreadPoolExecutor().setThreadFactory(task->new Thread(()->{
            try {task.run();}catch(RuntimeException failure){escaped.add(failure);}finally{exited.countDown();}
        },"synthetic-knowledge-worker"));
    }
    private static void verifyNoMoreInteractionsBeyondClaim(KnowledgeOperationService service) {
        verify(service).databaseAvailable();verify(service).claim(2);verifyNoMoreInteractions(service);
    }
    private static SimpleMeterRegistry registry(boolean fails) {
        var registry=spy(new SimpleMeterRegistry());
        if(fails)doThrow(new IllegalStateException("SYNTHETIC_PRIVATE_METRIC_PAYLOAD")).when(registry).counter(anyString(),any(String[].class));
        return registry;
    }
}
