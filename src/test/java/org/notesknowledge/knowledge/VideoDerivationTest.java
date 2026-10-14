package org.notesknowledge.knowledge;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.notesknowledge.knowledge.spi.PrivateDerivationSource;

@Tag("FAST") @Tag("SECURITY") @Tag("EVALUATION")
class VideoDerivationTest {
    @Test void rationalVfrRegionsCoverEveryFrameExactlyOnce() {
        var plan=new PrivateDerivationSource.VideoPlan(30000,List.of(1001L,2002L,3003L,600000L,1001L));
        int sample=0;long tick=0;int count=0;
        while(sample<plan.durations().size()){
            assertThat(plan.tick(sample)).isEqualTo(tick);int last=plan.next(sample);
            assertThat(last).isGreaterThan(sample);tick=plan.tick(last);count+=last-sample;sample=last;
        }
        assertThat(count).isEqualTo(5);assertThat(tick).isEqualTo(607007);
    }
    @Test void invalidTimelinesCannotReachTransformation() {
        for(var deltas:List.of(List.<Long>of(),List.of(0L),List.of(-1L),List.of(30001L),java.util.Collections.nCopies(18001,1L)))
            assertThatThrownBy(()->new PrivateDerivationSource.VideoPlan(1000,deltas)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new PrivateDerivationSource.VideoPlan(0,List.of(1L))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void childBuffersAreDefensiveAndClearedAndMetadataIsRedacted() {
        var plan=new PrivateDerivationSource.VideoPlan(1000,List.of(1000L));byte[] source=new byte[8];source[0]=1;
        var child=new PrivateDerivationSource.VideoChild(plan,0,1,source,"video-avc-baseline-preserve-ticks-v1","v1","a".repeat(64),"b".repeat(64));
        source[0]=2;assertThat(child.bytes()[0]).isEqualTo((byte)1);var copy=child.bytes();copy[0]=3;
        assertThat(child.bytes()[0]).isEqualTo((byte)1);assertThat(child.toString()).doesNotContain("aaaa","bbbb");
        child.close();assertThat(child.bytes()).containsOnly((byte)0);
    }
    @Test void videoChildScopeMustMatchClipRelativeRequestNotPdfPageScope() {
        var source=new org.notesknowledge.knowledge.spi.PrivateAiSourceCurrentness.Expected(new java.util.UUID(0,1),new java.util.UUID(0,2),new java.util.UUID(0,3),1,1,1L);
        var child=new TransformedMediaIdentity(source,"a".repeat(64),8,"video/mp4","video-v1","v1","a".repeat(64),"a".repeat(64),"a".repeat(64),8,"video/mp4",
            new TransformedMediaIdentity.Scope("video_samples",500,1000,1000,20000,40000),"a".repeat(64),"gemini","model","media-understanding","a".repeat(64));
        GoogleDerivationAdapters.requireChildScope(child,new MediaUnderstandingRequest("video/mp4",null,null,20d));
        assertThatThrownBy(()->GoogleDerivationAdapters.requireChildScope(child,new MediaUnderstandingRequest("video/mp4",null,null,21d))).isInstanceOf(DerivationFailure.class);
        assertThatThrownBy(()->GoogleDerivationAdapters.requireChildScope(child,new MediaUnderstandingRequest("image/png",1,1,null))).isInstanceOf(DerivationFailure.class);
    }
    @Test void videoTransformChangesOnlyVideoLineageAndDefaultsOff() {
        var c=new AiDerivationProperties(true,false,"synthetic","embedding","media","v1","synthetic","synthetic","v1","v1",8,"cosine","none","a".repeat(64));
        var enabled=new AiDerivationProperties(c.enabled(),false,c.provider(),c.embeddingModel(),c.mediaModel(),c.modelRevision(),c.region(),c.tier(),c.configurationId(),
            c.adapterVersion(),c.dimension(),c.operator(),c.normalization(),c.approvedPolicyFingerprint(),false,null,true,"b".repeat(64));
        var policy=new ProcessingPolicyService.AcknowledgedProcessingPolicy(new java.util.UUID(0,1),1,"a".repeat(64));
        assertThat(c.videoChildren()).isFalse();assertThat(enabled.configured()).isTrue();
        assertThat(EmbeddingLineage.create(c,policy,"video")).isNotEqualTo(EmbeddingLineage.create(enabled,policy,"video"));
        assertThat(EmbeddingLineage.create(c,policy,"note")).isEqualTo(EmbeddingLineage.create(enabled,policy,"note"));
    }
    @Test void allRegionsPreserveExactIdentityAndMapRelativeEvidenceBackToOriginalTime() {
        var context=new Context();var scopes=new java.util.ArrayList<TransformedMediaIdentity.Scope>();
        var payloads=new java.util.ArrayList<byte[]>();
        var result=VideoDerivation.derive(context.permit,new byte[8],context.plan,context.config,context::child,(identity,bytes,request)->{
            scopes.add(identity.scope());payloads.add(bytes);
            assertThat(identity.originalSha256()).isEqualTo(TransformedMediaIdentity.digest(new byte[8]));
            assertThat(identity.childSha256()).isEqualTo(TransformedMediaIdentity.digest(bytes));
            assertThat(request.durationSeconds()).isEqualTo(20d);
            return List.of(scene(1,2));
        },new DerivationAttemptBudget());
        assertThat(scopes).containsExactly(new TransformedMediaIdentity.Scope("video_samples",0,10,1000,0,20000),
            new TransformedMediaIdentity.Scope("video_samples",10,20,1000,20000,40000));
        assertThat(result).extracting(DerivedSegment::timeStart).containsExactly(1d,21d);
        assertThat(result).extracting(DerivedSegment::timeEnd).containsExactly(2d,22d);
        payloads.forEach(bytes->assertThat(bytes).containsOnly((byte)0));
    }
    @Test void laterRegionFailureDoesNotReturnPartialEvidence() {
        var context=new Context();var calls=new java.util.concurrent.atomic.AtomicInteger();
        assertThatThrownBy(()->VideoDerivation.derive(context.permit,new byte[8],context.plan,context.config,context::child,(identity,bytes,request)->{
            if(calls.incrementAndGet()==2)throw new DerivationFailure(KnowledgeWork.Failure.INVALID_OUTPUT);
            return List.of(scene(1,2));
        },new DerivationAttemptBudget())).isInstanceOf(DerivationFailure.class);
        assertThat(calls.get()).isEqualTo(2);
    }
    @Test void clipScopeMismatchIsRejectedBeforeDispatch() {
        var context=new Context();var dispatch=mock(VideoDerivation.Dispatch.class);
        assertThatThrownBy(()->VideoDerivation.derive(context.permit,new byte[8],context.plan,context.config,
            (plan,first,last)->context.child(plan,first,last-1),dispatch,new DerivationAttemptBudget())).isInstanceOf(DerivationFailure.class);
        verifyNoInteractions(dispatch);
    }
    @Test void emptyOrOutOfScopeOrExcessiveClipOutputNeverCompletes() {
        for(var output:List.of(List.<DerivedSegment>of(),List.of(scene(0,21)),java.util.Collections.nCopies(513,scene(0,1)))) {
            var context=new Context();
            assertThatThrownBy(()->VideoDerivation.derive(context.permit,new byte[8],context.plan,context.config,context::child,
                (identity,bytes,request)->output,new DerivationAttemptBudget())).isInstanceOf(DerivationFailure.class);
        }
    }
    @Test void wholeAttemptTimeoutAfterTransformationPreventsProviderDispatch() {
        var context=new Context();var time=new java.util.concurrent.atomic.AtomicLong();var dispatch=mock(VideoDerivation.Dispatch.class);
        assertThatThrownBy(()->VideoDerivation.derive(context.permit,new byte[8],context.plan,context.config,
            (plan,first,last)->{var child=context.child(plan,first,last);time.set(DerivationAttemptBudget.MAX_NANOS);return child;},dispatch,
            new DerivationAttemptBudget(time::get))).isInstanceOf(DerivationFailure.class);
        verifyNoInteractions(dispatch);
    }
    private static DerivedSegment scene(double first,double last){return new DerivedSegment("Synthetic scene","video_scene","",null,null,null,first,last,null,null,null,null);}
    private static final class Context {
        final PrivateDerivationSource.VideoPlan plan=new PrivateDerivationSource.VideoPlan(1000,java.util.Collections.nCopies(20,2000L));
        final AiProcessingGate.SourceAiPermit permit=mock(AiProcessingGate.SourceAiPermit.class);
        final AiDerivationProperties config;
        Context() {
            var source=new org.notesknowledge.knowledge.spi.PrivateAiSourceCurrentness.Expected(new java.util.UUID(0,1),new java.util.UUID(0,2),new java.util.UUID(0,3),1,1,1L);
            String fingerprint;try(var child=child(plan,0,10)){fingerprint=child.transformFingerprint();}
            config=new AiDerivationProperties(true,false,"synthetic","embedding","media","v1","synthetic","synthetic","v1","v1",8,"cosine","none","a".repeat(64),false,null,true,fingerprint);
            when(permit.source()).thenReturn(new PrivateDerivationSource.Metadata(source,"video",true,"active","video/mp4",8,40d,null));
            when(permit.lineage()).thenReturn(EmbeddingLineage.create(config,new ProcessingPolicyService.AcknowledgedProcessingPolicy(new java.util.UUID(0,1),1,"a".repeat(64)),"video"));
        }
        PrivateDerivationSource.VideoChild child(PrivateDerivationSource.VideoPlan plan,int first,int last) {
            return new PrivateDerivationSource.VideoChild(plan,first,last,new byte[8],"video-avc-baseline-preserve-ticks-v1","v1","a".repeat(64),"b".repeat(64));
        }
    }
}
