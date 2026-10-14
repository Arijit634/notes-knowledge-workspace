package org.notesknowledge.notes;

import static org.mockito.Mockito.*;
import org.junit.jupiter.api.*;

@Tag("FAST")
class AttachmentStorageReconcilerTest {
    @Test void boundedSweepsAdvanceIndependentCursorsAndFailureDoesNotBlockOtherCleanup(){
        var deletion=mock(AttachmentDeleteService.class);var upload=mock(AttachmentUploadService.class);
        var id=java.util.UUID.fromString("01990a55-9e12-7ac4-8f5b-31aa4a91d401");String key="private-attachment/"+"a".repeat(64);
        when(deletion.reconcilePendingPage(null,5)).thenReturn(id);when(upload.reconcile(null,5)).thenReturn(key);
        when(deletion.reconcilePendingPage(id,5)).thenThrow(new IllegalStateException("synthetic"));
        var sweeper=new AttachmentStorageReconciler(deletion,upload,java.time.Clock.systemUTC());
        sweeper.sweep();sweeper.sweep();
        verify(deletion).reconcilePendingPage(null,5);verify(deletion).reconcilePendingPage(id,5);
        verify(upload).reconcile(null,5);verify(upload).reconcile(key,5);
        verifyNoMoreInteractions(deletion,upload);
        // A fresh instance resets only optimization cursors, never durable state.
        new AttachmentStorageReconciler(deletion,upload,java.time.Clock.systemUTC()).sweep();
        verify(deletion,times(2)).reconcilePendingPage(null,5);
    }
}
