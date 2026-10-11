package org.notesknowledge.knowledge;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Offline scope/encoding tests, not a transcription/OCR or Gemini relevance benchmark. */
@Tag("FAST") @Tag("SECURITY") @Tag("RETRIEVAL") @Tag("EVALUATION")
class MediaProcessingScopeTest {
    @Test void countsBase64Utf8EnvelopeAndStrictSafetyMargin() {
        var image=new MediaUnderstandingRequest("image/png",null,null,null);
        String prompt="Synthetic é".repeat(100),schema=GoogleDerivationAdapters.mediaSchema();
        int overhead=MediaUnderstandingRequest.ENVELOPE_RESERVE_BYTES+prompt.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
            +schema.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        int largest=3*((MediaUnderstandingRequest.MAX_INLINE_REQUEST_BYTES-overhead-1)/4);
        image.requireInlineBudget(largest,prompt,schema);
        assertThatThrownBy(()->image.requireInlineBudget(largest+3,prompt,schema)).isInstanceOf(DerivationFailure.class);
        assertThatThrownBy(()->image.requireInlineBudget(20_000_000,prompt,schema)).isInstanceOf(DerivationFailure.class);
        assertThatThrownBy(()->image.requireInlineBudget(Integer.MAX_VALUE,prompt,schema)).isInstanceOf(DerivationFailure.class);
        assertThatThrownBy(()->image.requireInlineBudget(0,prompt,schema)).isInstanceOf(DerivationFailure.class);
        assertThat(MediaUnderstandingRequest.MAX_INLINE_REQUEST_BYTES).isLessThan(20_000_000);
    }
    @Test void overBudgetOrLongVideoFailsBeforeDispatchWithoutChangingUploadDuration() {
        new MediaUnderstandingRequest("video/mp4",null,null,30.).requireInlineBudget(1_000,"synthetic","{}");
        for(double duration:new double[]{30.001,300.}) {
            var video=new MediaUnderstandingRequest("video/mp4",null,null,duration);
            assertThatThrownBy(()->video.requireInlineBudget(1_000,"synthetic","{}"))
                .isInstanceOfSatisfying(DerivationFailure.class,f->assertThat(f.category).isEqualTo(KnowledgeWork.Failure.BUDGET_EXCEEDED));
        }
    }
    @Test void fullAcceptedWavFitsEncodedBudgetWithoutTransformingApprovedSource() {
        var request=new MediaUnderstandingRequest("audio/wav",null,null,300.);
        request.requireInlineBudget(10*1024*1024,GoogleDerivationAdapters.mediaInstruction(request),GoogleDerivationAdapters.mediaSchema());
    }
    @Test void pdfRequiresExactProcessedPageScopeAndRejectsPartialOutput() {
        var request=new MediaUnderstandingRequest("application/pdf",9,16,null);
        var pages=java.util.stream.IntStream.rangeClosed(9,16).boxed().toList();
        var fact=new DerivedSegment("Synthetic table: copper, 7 units.","pdf_text","",null,null,16,null,null,null,null,null,null);
        assertThat(GoogleDerivationAdapters.validateMediaOutput(request,new GoogleDerivationAdapters.MediaOutput(true,pages,false,List.of(fact)))).containsExactly(fact);
        for(var bad:List.of(new GoogleDerivationAdapters.MediaOutput(false,pages,false,List.of(fact)),
                new GoogleDerivationAdapters.MediaOutput(true,List.of(16),false,List.of(fact)),
                new GoogleDerivationAdapters.MediaOutput(true,pages,true,List.of())))
            assertThatThrownBy(()->GoogleDerivationAdapters.validateMediaOutput(request,bad)).isInstanceOf(DerivationFailure.class);
        var wrong=new DerivedSegment("Other page","pdf_text","",null,null,8,null,null,null,null,null,null);
        assertThatThrownBy(()->GoogleDerivationAdapters.validateScope(request,List.of(wrong))).isInstanceOf(DerivationFailure.class);
        assertThatThrownBy(()->new MediaUnderstandingRequest("application/pdf",1,9,null)).isInstanceOf(DerivationFailure.class);
    }
    @Test void silenceIsQualifiedAndUnknownTimingCannotBeInvented() {
        var audio=new MediaUnderstandingRequest("audio/wav",null,null,4.);
        var silent=GoogleDerivationAdapters.validateMediaOutput(audio,new GoogleDerivationAdapters.MediaOutput(true,List.of(),true,List.of()));
        assertThat(silent).hasSize(1);assertThat(silent.getFirst().text()).contains("No intelligible speech");
        assertThat(silent.getFirst().timeStart()).isZero();assertThat(silent.getFirst().timeEnd()).isEqualTo(4);
        assertThatThrownBy(()->GoogleDerivationAdapters.validateMediaOutput(audio,new GoogleDerivationAdapters.MediaOutput(false,List.of(),false,List.of())))
            .isInstanceOf(DerivationFailure.class);
        assertThatThrownBy(()->new DerivedSegment("uncertain","transcript","",null,null,null,null,null,null,null,null,null)).isInstanceOf(DerivationFailure.class);
        var late=new DerivedSegment("uncertain","transcript","",null,null,null,0.,5.,null,null,null,null);
        assertThatThrownBy(()->GoogleDerivationAdapters.validateScope(audio,List.of(late))).isInstanceOf(DerivationFailure.class);
    }
    @Test void videoOnlyScenesCannotAcceptInventedSpeechAndPartialCoverageIsRejected() {
        var video=new MediaUnderstandingRequest("video/mp4",null,null,2.);
        var scene=new DerivedSegment("Synthetic blue square","video_scene","",null,null,null,0.,1.,null,null,null,null);
        var speech=new DerivedSegment("Invented speech","transcript","",null,null,null,0.,1.,null,null,null,null);
        assertThat(GoogleDerivationAdapters.validateMediaOutput(video,new GoogleDerivationAdapters.MediaOutput(true,List.of(),false,List.of(scene)))).containsExactly(scene);
        assertThatThrownBy(()->GoogleDerivationAdapters.validateScope(video,List.of(speech))).isInstanceOf(DerivationFailure.class);
        assertThatThrownBy(()->GoogleDerivationAdapters.validateMediaOutput(video,new GoogleDerivationAdapters.MediaOutput(false,List.of(),false,List.of(scene)))).isInstanceOf(DerivationFailure.class);
        assertThat(GoogleDerivationAdapters.mediaInstruction(video)).contains("NO accepted audio track","do not claim exhaustive");
    }
    @Test void imageHasMultipleGroundedFactsAndStrictTotalTextBudget() {
        var image=new MediaUnderstandingRequest("image/png",null,null,null);
        var facts=List.of(new DerivedSegment("Blue square left of red circle; ASTER below.","whole_image","",null,null,null,null,null,null,null,null,null),
            new DerivedSegment("Blue square","image_region","",null,null,null,null,null,.05,.08,.3,.5));
        assertThat(GoogleDerivationAdapters.validateMediaOutput(image,new GoogleDerivationAdapters.MediaOutput(true,List.of(),false,facts))).containsExactlyElementsOf(facts);
        var huge=new DerivedSegment("x".repeat(12000),"whole_image","",null,null,null,null,null,null,null,null,null);
        assertThatThrownBy(()->GoogleDerivationAdapters.validateScope(image,Collections.nCopies(5,huge))).isInstanceOf(DerivationFailure.class);
        assertThatThrownBy(()->new DerivedSegment("made-up region","image_region","",null,null,null,null,null,.8,.8,.5,.5)).isInstanceOf(DerivationFailure.class);
    }
}
