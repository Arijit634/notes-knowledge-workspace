package org.notesknowledge.knowledge;

import java.util.Optional;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("FAST")
class DerivationTelemetryIsolationTest {
    @Test void aRegistryFailureDoesNotTurnEstablishedObsolescenceIntoAnExecutorFailure(){
        var gate=mock(AiProcessingGate.class);var work=mock(KnowledgeWorkService.class);var meters=mock(MeterRegistry.class);
        when(gate.issue(null)).thenReturn(Optional.empty());
        when(meters.counter("knowledge.derivation.outcome","outcome","obsolete")).thenThrow(new IllegalStateException("Synthetic private failure"));
        var executor=new DerivationExecutor(gate,null,work,null,null,meters,null,null);
        assertThatCode(()->executor.execute(null)).doesNotThrowAnyException();verify(work).obsolete(null);
    }
}
