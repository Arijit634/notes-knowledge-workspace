package org.notesknowledge.notes;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.*;
import org.notesknowledge.knowledge.spi.PrivateAiSourceCurrentness.Expected;
import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.beans.factory.ObjectProvider;

@Tag("FAST") @Tag("SECURITY")
class NotesPdfRuntimeGateTest {
    @Test @SuppressWarnings("unchecked") void noQualifiedRuntimeMeansNoTransformationOrOriginalFallback() {
        var current=mock(NotesPrivateAiSourceCurrentnessAdapter.class);var attachments=mock(AttachmentRepository.class);
        var clients=mock(ObjectProvider.class);var stores=mock(ObjectProvider.class);var runtimes=mock(ObjectProvider.class);
        var expected=new Expected(new java.util.UUID(0,1),new java.util.UUID(0,2),new java.util.UUID(0,3),1,1,1L);
        when(current.matches(expected)).thenReturn(true);
        when(attachments.content(expected.owner(),expected.noteId(),expected.attachmentId())).thenReturn(java.util.Optional.of(
            new AttachmentContentDescriptor(expected.attachmentId(),"synthetic-custody","application/pdf","synthetic.pdf",10,1)));
        var adapter=new NotesPrivateDerivationSourceAdapter(clients,current,attachments,stores,runtimes,mock(ObjectProvider.class));
        var material=adapter.acquire(expected);
        assertThatThrownBy(()->material.pdfChild(new byte[]{1,2,3},1)).isInstanceOfSatisfying(ApiFailureException.class,
            failure->assertThat(failure.kind()).isEqualTo(ApiFailureException.Kind.SERVICE_UNAVAILABLE));
        verifyNoInteractions(stores);
    }
    @Test @SuppressWarnings("unchecked") void noQualifiedVideoRuntimeMeansNoPlanningOrClipFallback() {
        var current=mock(NotesPrivateAiSourceCurrentnessAdapter.class);var attachments=mock(AttachmentRepository.class);
        var clients=mock(ObjectProvider.class);var stores=mock(ObjectProvider.class);var runtimes=mock(ObjectProvider.class);
        var expected=new Expected(new java.util.UUID(0,1),new java.util.UUID(0,2),new java.util.UUID(0,3),1,1,1L);
        when(current.matches(expected)).thenReturn(true);
        when(attachments.content(expected.owner(),expected.noteId(),expected.attachmentId())).thenReturn(java.util.Optional.of(
            new AttachmentContentDescriptor(expected.attachmentId(),"synthetic-custody","video/mp4","synthetic.mp4",10,1)));
        var material=new NotesPrivateDerivationSourceAdapter(clients,current,attachments,stores,runtimes,mock(ObjectProvider.class)).acquire(expected);
        assertThatThrownBy(()->material.videoPlan(new byte[8])).isInstanceOfSatisfying(ApiFailureException.class,
            failure->assertThat(failure.kind()).isEqualTo(ApiFailureException.Kind.SERVICE_UNAVAILABLE));
        assertThatThrownBy(()->material.videoChild(new byte[8],new org.notesknowledge.knowledge.spi.PrivateDerivationSource.VideoPlan(1000,java.util.List.of(1000L)),0,1))
            .isInstanceOfSatisfying(ApiFailureException.class,failure->assertThat(failure.kind()).isEqualTo(ApiFailureException.Kind.SERVICE_UNAVAILABLE));
        verifyNoInteractions(stores);
    }
}
