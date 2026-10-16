package org.notesknowledge.knowledge;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.notesknowledge.knowledge.spi.PrivateAiSourceCurrentness.Expected;

/** Offline recorder/metric tests. Mock permits here do NOT qualify production authorization or Gemini quality. */
@Tag("FAST") @Tag("SECURITY") @Tag("EVALUATION")
class MediaQualityQualificationHarnessTest {
    @TempDir Path temporary;
    private final MediaUnderstandingPort delegate=mock(MediaUnderstandingPort.class);
    private final MutableClock clock=new MutableClock();
    private final MediaQualityQualificationHarness.Consent consent=new MediaQualityQualificationHarness.Consent(true,true,4,16_000_000,32768,15);
    private final Expected expected=new Expected(UUID.fromString("01990111-1111-7111-8111-111111111111"),
        UUID.fromString("01990222-2222-7222-8222-222222222222"),UUID.fromString("01990333-3333-7333-8333-333333333333"),1,1,1L);

    @Test void defaultOrUnpaidUnconfirmedConsentCreatesNothingAndNeverCallsProvider() {
        for(var invalid:List.of(new MediaQualityQualificationHarness.Consent(false,true,4,16_000_000,32768,15),
                new MediaQualityQualificationHarness.Consent(true,false,4,16_000_000,32768,15),
                new MediaQualityQualificationHarness.Consent(true,true,13,16_000_000,32768,15),
                new MediaQualityQualificationHarness.Consent(true,true,4,16_000_000,32768,1))) {
            Path absent=temporary.resolve("not-created");
            assertThatThrownBy(()->new MediaQualityQualificationHarness(absent,invalid,delegate,clock)).isInstanceOf(IllegalStateException.class);
            assertThat(absent).doesNotExist();
        }
        verifyNoInteractions(delegate);
    }
    @Test void corpusIsRepeatableDefensiveAndIncludesFrozenLongVideoAndNonSpeech() throws Exception {
        var first=MediaQualityQualificationHarness.frozenCorpus();var second=MediaQualityQualificationHarness.frozenCorpus();
        assertThat(first).extracting(MediaQualityQualificationHarness.Fixture::id).containsExactly("image","mixed","audio","tones","video","long-video","diagram-image","diagram-pdf");
        for(int i=0;i<first.size();i++)assertThat(first.get(i).bytes()).isEqualTo(second.get(i).bytes());
        byte[] saved=first.getFirst().bytes();first.getFirst().bytes()[0]=0;assertThat(first.getFirst().bytes()).isEqualTo(saved);
    }
    @Test void imageAllowlistUsesActualCanonicalUploadCustodyRatherThanOriginalEncoding() throws Exception {
        for(String id:List.of("image","diagram-image")) {
            byte[] accepted=org.notesknowledge.notes.MeaningfulMediaFixtures.acceptedImage(id).getBytes();
            assertThat(fixture(id)).isEqualTo(accepted);
        }
        assertThat(fixture("image")).isNotEqualTo(org.notesknowledge.notes.MeaningfulMediaFixtures.media("image").getBytes());
    }
    @Test void reservationIsForcedBeforeDispatchAndSuccessRecordsOnlyBoundedMetrics() throws Exception {
        var permit=permit();var image=fixture("image");var request=imageRequest();
        when(delegate.describe(permit,image,request)).thenAnswer(call->{
            assertThat(Files.readString(temporary.resolve("000001.json"))).contains("reserved").doesNotContain("completed");
            return List.of(new DerivedSegment("ASTER blue square left of red circle","whole_image","",null,null,null,null,null,null,null,null,null));
        });
        try(var recorder=open(consent)) {
            assertThat(recorder.describe(permit,image,request)).hasSize(1);
            assertThat(recorder.ledger()).extracting(MediaQualityQualificationHarness.Event::state).containsExactly("reserved","completed");
            assertThat(recorder.ledger().getFirst().estimatedBytes()).isGreaterThan(image.length);
            assertThat(recorder.ledger().getFirst().reservedOutputTokens()).isEqualTo(8192);
            assertThat(Files.readString(temporary.resolve("000002.json"))).contains("requires independent judgment").doesNotContain("ASTER blue square left of red circle");
            clock.advance();assertThatThrownBy(()->recorder.describe(permit,image,request)).isInstanceOf(IllegalStateException.class);
        }
        verify(delegate,times(1)).describe(permit,image,request);
    }
    @Test void permissionDenialAndUnknownBytesConsumeNoReservation() throws Exception {
        var permit=permit();doThrow(new DerivationFailure(KnowledgeWork.Failure.INVALID_SOURCE)).when(permit).requireGoogleDispatch();
        try(var recorder=open(consent)) {
            assertThatThrownBy(()->recorder.describe(permit,fixture("image"),imageRequest())).isInstanceOf(DerivationFailure.class);
            assertThatThrownBy(()->recorder.describe(permit(),new byte[]{1,2,3},imageRequest())).isInstanceOf(IllegalStateException.class);
            assertThat(recorder.ledger()).isEmpty();
        }
        verifyNoInteractions(delegate);
    }
    @Test void failureIsChargedSanitizedAndRestartCannotReplayOrRaiseBudget() throws Exception {
        var permit=permit();var image=fixture("image");var request=imageRequest();
        when(delegate.describe(permit,image,request)).thenThrow(new IllegalStateException("SYNTHETIC_PRIVATE_PROVIDER_BODY"));
        try(var recorder=open(consent)) {
            assertThatThrownBy(()->recorder.describe(permit,image,request)).hasMessage("Qualification provider failure").hasNoCause();
            assertThat(recorder.ledger()).extracting(MediaQualityQualificationHarness.Event::state).containsExactly("reserved","failed");
            assertThat(Files.readString(temporary.resolve("000002.json"))).doesNotContain("SYNTHETIC_PRIVATE_PROVIDER_BODY");
        }
        try(var restarted=open(consent)) {
            clock.advance();assertThatThrownBy(()->restarted.describe(permit,image,request)).isInstanceOf(IllegalStateException.class);
        }
        var changed=new MediaQualityQualificationHarness.Consent(true,true,5,16_000_000,40960,15);
        assertThatThrownBy(()->open(changed)).hasMessage("Qualification identity or budget changed");
        verify(delegate,times(1)).describe(permit,image,request);
    }
    @Test void interruptedReservationCannotBeAutomaticallyReplayed() throws Exception {
        var permit=permit();var image=fixture("image");var request=imageRequest();
        when(delegate.describe(permit,image,request)).thenThrow(new SimulatedProcessDeath());
        try(var recorder=open(consent)) {
            assertThatThrownBy(()->recorder.describe(permit,image,request)).isInstanceOf(SimulatedProcessDeath.class);
            assertThat(recorder.ledger()).extracting(MediaQualityQualificationHarness.Event::state).containsExactly("reserved");
            clock.advance();assertThatThrownBy(()->recorder.describe(permit,fixture("audio"),new MediaUnderstandingRequest("audio/wav",null,null,8.)))
                .isInstanceOf(IllegalStateException.class);
        }
        try(var restarted=open(consent)) {
            clock.advance();assertThatThrownBy(()->restarted.describe(permit,image,request)).isInstanceOf(IllegalStateException.class);
        }
        verify(delegate,times(1)).describe(permit,image,request);
    }
    @Test void concurrentCallsCannotOverbookOneBudgetOrBypassSpacing() throws Exception {
        var permit=permit();var image=fixture("image");var request=imageRequest();
        var entered=new java.util.concurrent.CountDownLatch(1);var resume=new java.util.concurrent.CountDownLatch(1);
        when(delegate.describe(permit,image,request)).thenAnswer(call->{
            entered.countDown();if(!resume.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("Synthetic recorder coordination timed out");
            return List.of();
        });
        try(var recorder=open(consent);var workers=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first=workers.submit(()->recorder.describe(permit,image,request));
            assertThat(entered.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var second=workers.submit(()->recorder.describe(permit,image,request));
            resume.countDown();assertThat(first.get(10,java.util.concurrent.TimeUnit.SECONDS)).isEmpty();
            assertThatThrownBy(()->second.get(10,java.util.concurrent.TimeUnit.SECONDS))
                .isInstanceOf(java.util.concurrent.ExecutionException.class).hasCauseInstanceOf(IllegalStateException.class);
            assertThat(recorder.ledger().stream().filter(e->e.state().equals("reserved")).count()).isEqualTo(1);
        } finally {resume.countDown();}
        verify(delegate,times(1)).describe(permit,image,request);
    }
    @Test void secondProcessRecorderCannotShareAnActiveJournal() throws Exception {
        try(var first=open(consent)) {
            assertThatThrownBy(()->open(consent)).isInstanceOf(java.nio.channels.OverlappingFileLockException.class);
            assertThat(first.ledger()).isEmpty();
        }
        try(var reopened=open(consent)){assertThat(reopened.ledger()).isEmpty();}
        verifyNoInteractions(delegate);
    }
    @Test void minimumIntervalAndByteAndTokenBudgetsDenyBeforeDispatch() throws Exception {
        var permit=permit();var image=fixture("image");var request=imageRequest();when(delegate.describe(permit,image,request)).thenReturn(List.of());
        try(var recorder=open(consent)) {
            recorder.describe(permit,image,request);
            assertThatThrownBy(()->recorder.describe(permit,fixture("audio"),new MediaUnderstandingRequest("audio/wav",null,null,8.))).isInstanceOf(IllegalStateException.class);
        }
        for(var budget:List.of(new MediaQualityQualificationHarness.Consent(true,true,1,1,8192,15),
                new MediaQualityQualificationHarness.Consent(true,true,2,16_000_000,8192,15))) {
            Path directory=temporary.resolve("budget-"+budget.estimatedRequestBytes());
            try(var recorder=new MediaQualityQualificationHarness(directory,budget,delegate,clock)) {
                if(budget.estimatedRequestBytes()>1){recorder.describe(permit,image,request);clock.advance();}
                assertThatThrownBy(()->recorder.describe(permit,fixture("audio"),new MediaUnderstandingRequest("audio/wav",null,null,8.))).isInstanceOf(IllegalStateException.class);
            }
        }
        verify(delegate,times(2)).describe(permit,image,request);verifyNoMoreInteractions(delegate);
    }
    @Test void frozenCorpusCannotAuthorizeTransformedChildOrSendLongOriginalDirectly() throws Exception {
        var permit=permit();byte[] original=fixture("mixed"),child=fixture("image");
        doThrow(new DerivationFailure(KnowledgeWork.Failure.INVALID_SOURCE)).when(permit).requireGoogleChildDispatch(null,original,child);
        try(var recorder=open(consent)) {
            assertThatThrownBy(()->recorder.describeChild(permit,null,original,child,new MediaUnderstandingRequest("image/png",3,3,null))).isInstanceOf(DerivationFailure.class);
            assertThatThrownBy(()->recorder.describe(permit,fixture("long-video"),new MediaUnderstandingRequest("video/mp4",null,null,40.))).isInstanceOf(DerivationFailure.class);
            assertThat(recorder.ledger()).isEmpty();
        }
        verifyNoInteractions(delegate);
    }
    @Test void transcriptMetricsDoNotTreatAcousticDescriptionsAsWordsOrKeywordsAsFaithfulness() {
        var speech=new DerivedSegment("The fictional observatory is on planet Aster. Its dome is silver.","transcript","Transcript",null,null,null,0.,6.,null,null,null,null);
        var observation=new DerivedSegment("Audio description (model inference, not spoken words): synthetic tone.","transcript","Acoustic description",null,null,null,0.,4.,null,null,null,null);
        assertThat(MediaQualityQualificationHarness.checks("audio",List.of(speech,observation))).containsEntry("wordErrorRate",0.);
        assertThat(MediaQualityQualificationHarness.wordErrorRate("one two three","one four three")).isEqualTo(1./3);
        assertThat(MediaQualityQualificationHarness.wordErrorRate("one two three","")).isEqualTo(1.);
        assertThat(MediaQualityQualificationHarness.checks("tones",List.of(observation))).containsEntry("qualifiedAcousticSegments",1L)
            .containsEntry("eventAndTransitionAccuracy","independent judgment required");
        assertThat(MediaQualityQualificationHarness.checks("mixed",List.of())).containsEntry("scannedPageAttribution",false);
    }
    private MediaQualityQualificationHarness open(MediaQualityQualificationHarness.Consent approved) throws Exception {
        return new MediaQualityQualificationHarness(temporary,approved,delegate,clock);
    }
    private byte[] fixture(String id)throws Exception {return MediaQualityQualificationHarness.frozenCorpus().stream().filter(f->f.id().equals(id)).findFirst().orElseThrow().bytes();}
    private AiProcessingGate.SourceAiPermit permit() {
        var permit=mock(AiProcessingGate.SourceAiPermit.class);
        when(permit.source()).thenReturn(mock(org.notesknowledge.knowledge.spi.PrivateDerivationSource.Metadata.class));
        when(permit.source().expected()).thenReturn(expected);return permit;
    }
    private MediaUnderstandingRequest imageRequest(){return new MediaUnderstandingRequest("image/png",null,null,null);}
    static final class SimulatedProcessDeath extends Error { }
    static final class MutableClock extends Clock {
        private Instant now=Instant.parse("2026-10-15T00:00:00Z");
        void advance(){now=now.plusSeconds(16);}
        public java.time.ZoneId getZone(){return java.time.ZoneOffset.UTC;}
        public Clock withZone(java.time.ZoneId zone){return this;}
        public Instant instant(){return now;}
    }
}
