package org.notesknowledge.knowledge;

import java.util.Optional;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("FAST")
class DerivationTelemetryIsolationTest {
    @Test void acousticDiagnosticExportsOnlyBoundedNumericContextAndDoesNotChangeExistingMeters() {
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(RetrievalTelemetry.class);
        var appender=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();appender.start();logger.addAppender(appender);
        var registry=new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        try {
            var telemetry=new RetrievalTelemetry(registry);telemetry.acousticContext(-1);telemetry.acousticContext(2);
            assertThat(appender.list).hasSize(2).allSatisfy(event->{
                assertThat(event.getFormattedMessage()).isEqualTo("knowledge_acoustic_context");
                assertThat(event.getThrowableProxy()).isNull();
                assertThat(event.getKeyValuePairs()).hasSize(1);
                assertThat(event.getKeyValuePairs().getFirst().key).isEqualTo("acousticSegments");
            });
            assertThat(appender.list.get(0).getKeyValuePairs().getFirst().value).isEqualTo(0);
            assertThat(appender.list.get(1).getKeyValuePairs().getFirst().value).isEqualTo(2);
            assertThat(registry.find("notes.workspace.retrieval.candidates").summary()).isNull();
        }finally{logger.detachAppender(appender);appender.stop();registry.close();}
    }
    @Test void aRegistryFailureDoesNotTurnEstablishedObsolescenceIntoAnExecutorFailure(){
        var gate=mock(AiProcessingGate.class);var work=mock(KnowledgeWorkService.class);var meters=mock(MeterRegistry.class);
        when(gate.issue(null)).thenReturn(Optional.empty());
        when(meters.counter("knowledge.derivation.outcome","outcome","obsolete")).thenThrow(new IllegalStateException("Synthetic private failure"));
        var executor=new DerivationExecutor(gate,null,work,null,null,meters,null,null);
        assertThatCode(()->executor.execute(null)).doesNotThrowAnyException();verify(work).obsolete(null);
    }
}
