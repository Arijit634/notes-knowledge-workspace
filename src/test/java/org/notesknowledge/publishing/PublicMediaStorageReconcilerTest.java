package org.notesknowledge.publishing;

import static org.mockito.Mockito.*;
import java.time.Clock;
import org.junit.jupiter.api.*;

@Tag("FAST")
class PublicMediaStorageReconcilerTest {
    @Test void boundedCursorIsAnOptimizationNotNewDurableAuthority(){
        var service=mock(PublicationService.class);String key="public-publication-media/"+"a".repeat(64);
        when(service.reconcile(null,5)).thenReturn(key);
        var sweeper=new PublicMediaStorageReconciler(service,Clock.systemUTC());sweeper.sweep();sweeper.sweep();
        verify(service).reconcile(null,5);verify(service).reconcile(key,5);verifyNoMoreInteractions(service);
        new PublicMediaStorageReconciler(service,Clock.systemUTC()).sweep();verify(service,times(2)).reconcile(null,5);
    }
}
