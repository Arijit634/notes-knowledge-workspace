package org.notesknowledge.knowledge;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@Tag("FAST") @Tag("SECURITY")
class SearchHistoryMaintenanceTest {
    @Test void retentionRemainsEnabledWhenOperationAndProviderSchedulingAreDisabled(){
        var history=mock(SearchHistoryService.class);when(history.databaseAvailable()).thenReturn(true);
        new ApplicationContextRunner().withUserConfiguration(SearchHistoryMaintenance.class)
            .withBean(SearchHistoryService.class,()->history)
            .withPropertyValues("knowledge.operations.scheduling-enabled=false","knowledge.derivation.scheduling-enabled=false","knowledge.history.cleanup-delay-ms=86400000")
            .run(context->{assertThat(context).hasSingleBean(SearchHistoryMaintenance.class);context.getBean(SearchHistoryMaintenance.class).expire();verify(history).sweep();});
    }
    @Test void unavailableDatabaseDefersCleanupWithoutInvokingItsRepository(){
        var history=mock(SearchHistoryService.class);new SearchHistoryMaintenance(history).expire();verify(history,never()).sweep();
    }
    @Test @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void databaseFailureDoesNotEscapeToTheSchedulersPayloadPrintingHandler(org.springframework.boot.test.system.CapturedOutput output){
        var history=mock(SearchHistoryService.class);when(history.databaseAvailable()).thenReturn(true);
        doThrow(new IllegalStateException("synthetic-private-marker")).when(history).sweep();
        assertThatCode(()->new SearchHistoryMaintenance(history).expire()).doesNotThrowAnyException();
        assertThat(output.getAll()).contains("knowledge_history_cleanup_deferred").doesNotContain("synthetic-private-marker");
    }
}
