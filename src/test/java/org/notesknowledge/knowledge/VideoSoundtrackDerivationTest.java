package org.notesknowledge.knowledge;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.UUID;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.notesknowledge.knowledge.spi.PrivateDerivationSource.*;

@Tag("FAST") @Tag("SECURITY") @Tag("RETRIEVAL") @Tag("EVALUATION")
class VideoSoundtrackDerivationTest {
    private final VideoAudioPlan plan=new VideoAudioPlan(1000,1000,true);
    private final byte[] original=new byte[8];
    private final AiProcessingGate.SourceAiPermit permit=mock(AiProcessingGate.SourceAiPermit.class);
    private VideoAudioChild child(){return new VideoAudioChild(plan,wave(plan.samples()),VideoAudioChild.PROFILE,"v2","b".repeat(64),"c".repeat(64));}
    private AiDerivationProperties config() {
        var source=new org.notesknowledge.knowledge.spi.PrivateAiSourceCurrentness.Expected(new UUID(0,1),new UUID(0,2),new UUID(0,3),1,1,1L);
        String fingerprint;try(var c=child()){fingerprint=c.transformFingerprint();}
        var c=new AiDerivationProperties(true,false,"synthetic","embedding","media","v1","synthetic","synthetic","v1","v1",8,"cosine","none","a".repeat(64),false,null,true,fingerprint);
        when(permit.source()).thenReturn(new Metadata(source,"video",true,"active","video/mp4",original.length,1.,null));
        when(permit.lineage()).thenReturn(EmbeddingLineage.create(c,new ProcessingPolicyService.AcknowledgedProcessingPolicy(new UUID(0,4),1,"a".repeat(64)),"video"));
        return c;
    }
    @Test void originalVideoIdentityAndPcmTimingSurviveAcousticAndSpeechEvidence() {
        var c=config();var owned=child();
        var result=VideoSoundtrackDerivation.derive(permit,original,plan,c,p->owned,(identity,bytes,request)->{
            assertThat(identity.original()).isEqualTo(permit.source().expected());
            assertThat(identity.scope().kind()).isEqualTo("video_audio");assertThat(identity.childType()).isEqualTo("audio/wav");
            identity.requireOriginalBytes(original);identity.requireBytes(bytes);GoogleDerivationAdapters.requireChildScope(identity,request);
            assertThat(request.durationSeconds()).isEqualTo(1.);
            return List.of(new DerivedSegment("Possibly chattering calls over a bass rhythm","transcript","Acoustic description",null,null,null,.1,.7,null,null,null,null),
                new DerivedSegment("A fictional greeting","transcript","Transcript",null,null,null,.2,.8,null,null,null,null));
        },new DerivationAttemptBudget());
        assertThat(result).allSatisfy(s->assertThat(s.kind()).isEqualTo("video_scene"));
        assertThat(result.getFirst().text()).startsWith("Audio description (model inference, not spoken words): ");
        assertThat(result.getLast().text()).isEqualTo("Spoken transcript: A fictional greeting");
        assertThat(result.getFirst().timeStart()).isEqualTo(.1);assertThat(result.getFirst().timeEnd()).isEqualTo(.7);
        assertThat(owned.bytes()).containsOnly((byte)0);
    }
    @Test void provenSilentTrackDoesNotManufactureAnAudioChildOrProviderCall() {
        var c=config();var transform=mock(VideoSoundtrackDerivation.Transform.class);var dispatch=mock(VideoDerivation.Dispatch.class);
        assertThat(VideoSoundtrackDerivation.derive(permit,original,new VideoAudioPlan(1000,1000,false),c,transform,dispatch,new DerivationAttemptBudget())).isEmpty();
        verifyNoInteractions(transform,dispatch);
    }
    @Test void nativeSoundtrackIdentityBindsExplicitModelAndRejectsDifferentModelPolicy() {
        var base=config();
        var scoped=new AiDerivationProperties(true,false,"gemini","gemini-embedding-2","gemini-3.5-flash-lite","v1","global","unpaid",
            "v1","v1",8,"cosine","none","a".repeat(64),false,null,true,base.videoTransformFingerprint(),"gemini-3.6-flash");
        when(permit.lineage()).thenReturn(EmbeddingLineage.create(scoped,new ProcessingPolicyService.AcknowledgedProcessingPolicy(new UUID(0,4),1,"a".repeat(64)),"video"));
        var policy=new ProviderDispatchPolicy(new ProviderDispatchProperties("unpaid-synthetic-demo",List.of(),List.of(),List.of(),new UUID(0,1)),true);
        VideoSoundtrackDerivation.derive(permit,original,plan,scoped,p->child(),(identity,bytes,request)->{
            assertThat(identity.model()).isEqualTo("gemini-3.6-flash");
            assertThat(policy.permitsUnpaidGeminiChild(scoped,identity)).isTrue();
            var other=new AiDerivationProperties(true,false,"gemini","gemini-embedding-2","gemini-3.5-flash-lite","v1","global","unpaid",
                "v1","v1",8,"cosine","none","a".repeat(64),false,null,true,base.videoTransformFingerprint());
            assertThat(policy.permitsUnpaidGeminiChild(other,identity)).isFalse();
            return List.of(new DerivedSegment("Synthetic tones","transcript","Acoustic description",null,null,null,0.,1.,null,null,null,null));
        },new DerivationAttemptBudget());
    }
    @Test void changedMovieDurationAndWrongPcmHeaderFailClosed() {
        var c=config();var transform=mock(VideoSoundtrackDerivation.Transform.class);var dispatch=mock(VideoDerivation.Dispatch.class);
        assertThatThrownBy(()->VideoSoundtrackDerivation.derive(permit,original,new VideoAudioPlan(1000,2000,true),c,transform,dispatch,new DerivationAttemptBudget())).isInstanceOf(DerivationFailure.class);
        verifyNoInteractions(transform,dispatch);
        var malformed=wave(plan.samples());malformed[24]^=1;
        assertThatThrownBy(()->new VideoAudioChild(plan,malformed,VideoAudioChild.PROFILE,"v2","b".repeat(64),"c".repeat(64))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new VideoAudioChild(plan,new byte[malformed.length+2],VideoAudioChild.PROFILE,"v2","b".repeat(64),"c".repeat(64))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void unsupportedTransformAndTimeoutCannotReachDispatchAndAlwaysClearChildBytes() {
        var c=config();var dispatch=mock(VideoDerivation.Dispatch.class);var owned=child();var ticks=new java.util.concurrent.atomic.AtomicLong();
        assertThatThrownBy(()->VideoSoundtrackDerivation.derive(permit,original,plan,c,p->{ticks.set(DerivationAttemptBudget.MAX_NANOS);return owned;},dispatch,new DerivationAttemptBudget(ticks::get)))
            .isInstanceOf(DerivationFailure.class);verifyNoInteractions(dispatch);assertThat(owned.bytes()).containsOnly((byte)0);
        var wrong=new VideoAudioChild(plan,wave(plan.samples()),VideoAudioChild.PROFILE,"other-version","b".repeat(64),"c".repeat(64));
        assertThatThrownBy(()->VideoSoundtrackDerivation.derive(permit,original,plan,c,p->wrong,dispatch,new DerivationAttemptBudget())).isInstanceOf(DerivationFailure.class);
        assertThat(wrong.bytes()).containsOnly((byte)0);verifyNoInteractions(dispatch);
    }
    @Test void partialEmptyAndOutOfIntervalAudioCannotBecomeACompleteVideoGeneration() {
        var c=config();
        for(var evidence:List.of(List.<DerivedSegment>of(),List.of(new DerivedSegment("Outside original interval","transcript","Transcript",null,null,null,0.,2.,null,null,null,null))))
            assertThatThrownBy(()->VideoSoundtrackDerivation.derive(permit,original,plan,c,p->child(),(id,b,r)->evidence,new DerivationAttemptBudget())).isInstanceOf(DerivationFailure.class);
    }
    @Test void sampleClockBoundsAreDeterministicAndCannotExceedOriginalDuration() {
        var fractional=new VideoAudioPlan(30000,30001,true);
        assertThat(fractional.samples()).isEqualTo(16000);assertThat((double)fractional.samples()/16000).isLessThanOrEqualTo(fractional.durationSeconds());
        assertThatThrownBy(()->new VideoAudioPlan(1000,300001,true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new TransformedMediaIdentity.Scope("video_audio",0,4_800_001,16000,0,4_800_001)).isInstanceOf(DerivationFailure.class);
        assertThatThrownBy(()->new TransformedMediaIdentity.Scope("video_audio",1,16000,16000,0,16000)).isInstanceOf(DerivationFailure.class);
    }
    static byte[] wave(long samples) {
        int count=Math.toIntExact(samples*2);var b=ByteBuffer.allocate(44+count).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0x46464952).putInt(36+count).putInt(0x45564157).putInt(0x20746d66).putInt(16).putShort((short)1).putShort((short)1)
            .putInt(16000).putInt(32000).putShort((short)2).putShort((short)16).putInt(0x61746164).putInt(count);
        for(int i=0;i<samples;i++)b.putShort((short)(Math.sin(i*2*Math.PI*523/16000)*5000));return b.array();
    }
}
