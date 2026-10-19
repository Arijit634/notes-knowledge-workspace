package org.notesknowledge.knowledge;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("FAST")
class PublicKnowledgeTelemetryTest {
    @Test void telemetryOutageCannotFailCompletedOrRetryWork() {
        var registry=mock(MeterRegistry.class);
        when(registry.counter(anyString(),org.mockito.ArgumentMatchers.any(String[].class)))
            .thenThrow(new IllegalStateException("synthetic metric outage"));
        var worker=new PublicKnowledgeWorker(null,registry);
        assertThatCode(()->{for(String outcome:new String[]{"completed","obsolete","excluded","retry"})worker.metric(outcome);})
            .doesNotThrowAnyException();
        verify(registry,times(4)).counter(eq("knowledge.public.work"),org.mockito.ArgumentMatchers.any(String[].class));
        verify(registry,times(4)).counter(eq("notes.workspace.knowledge.work.outcome"),org.mockito.ArgumentMatchers.any(String[].class));
        verifyNoMoreInteractions(registry);
    }
}
