package org.notesknowledge.knowledge;

import java.util.ArrayList;
import java.util.List;
import org.notesknowledge.knowledge.spi.PrivateDerivationSource;

/** Whole-video coverage and clip-relative -> original rational-time provenance.
 * The transform and EVERY dispatch are separately current-authorized by the caller.
 */
final class VideoDerivation {
    @FunctionalInterface interface Transform {PrivateDerivationSource.VideoChild render(PrivateDerivationSource.VideoPlan plan,int first,int last);}
    @FunctionalInterface interface Dispatch {List<DerivedSegment> describe(TransformedMediaIdentity identity,byte[] bytes,MediaUnderstandingRequest request);}
    static List<DerivedSegment> derive(AiProcessingGate.SourceAiPermit initial,byte[] original,
            PrivateDerivationSource.VideoPlan plan,AiDerivationProperties config,Transform transform,Dispatch dispatch,
            DerivationAttemptBudget budget) {
        return derive(initial,original,plan,null,config,transform,dispatch,budget);
    }
    static List<DerivedSegment> derive(AiProcessingGate.SourceAiPermit initial,byte[] original,
            PrivateDerivationSource.VideoPlan plan,PrivateDerivationSource.VideoAudioPlan soundtrack,
            AiDerivationProperties config,Transform transform,Dispatch dispatch,DerivationAttemptBudget budget) {
        if(!config.videoChildren()||plan==null||initial.source().durationSeconds()==null
                ||!compatibleDuration(initial,plan,soundtrack))
            throw new DerivationFailure(KnowledgeWork.Failure.INVALID_SOURCE);
        var result=new ArrayList<DerivedSegment>();int regions=0;
        for(int first=0;first<plan.durations().size();) {
            budget.check();if(++regions>100)throw new DerivationFailure(KnowledgeWork.Failure.BUDGET_EXCEEDED);
            int last=plan.next(first);
            try(var child=transform.render(plan,first,last)) {
                budget.check();
                if(child==null||!child.plan().equals(plan)||child.first()!=first||child.last()!=last
                        ||!child.transformFingerprint().equals(config.videoTransformFingerprint()))
                    throw new DerivationFailure(KnowledgeWork.Failure.INVALID_OUTPUT);
                byte[] payload=child.bytes();
                try {
                    long start=plan.tick(first),end=plan.tick(last);
                    var identity=new TransformedMediaIdentity(initial.source().expected(),TransformedMediaIdentity.digest(original),
                        original.length,"video/mp4",child.profile(),child.implementationVersion(),child.artifactSha256(),
                        child.configurationSha256(),TransformedMediaIdentity.digest(payload),payload.length,"video/mp4",
                        new TransformedMediaIdentity.Scope("video_samples",first,last,plan.timescale(),start,end),
                        initial.lineage().id(),config.provider(),config.mediaModel(),"media-understanding",config.approvedPolicyFingerprint());
                    // This canonical visual child deliberately has no audio. Soundtrack
                    // evidence must come from its separately validated original scope.
                    var request=new MediaUnderstandingRequest("video/mp4",null,null,(double)(end-start)/plan.timescale(),false);
                    var described=dispatch.describe(identity,payload,request);
                    GoogleDerivationAdapters.validateScope(request,described);
                    if(described.isEmpty())throw new DerivationFailure(KnowledgeWork.Failure.INVALID_OUTPUT);
                    for(var segment:described) {
                        if(result.size()>=512)throw new DerivationFailure(KnowledgeWork.Failure.BUDGET_EXCEEDED);
                        double offset=(double)start/plan.timescale();
                        result.add(new DerivedSegment(segment.text(),"video_scene",segment.heading(),null,null,null,
                            offset+segment.timeStart(),Math.min((double)end/plan.timescale(),offset+segment.timeEnd()),null,null,null,null));
                    }
                }finally{java.util.Arrays.fill(payload,(byte)0);}
            }
            first=last;
        }
        budget.check();return List.copyOf(result);
    }
    private static boolean compatibleDuration(AiProcessingGate.SourceAiPermit initial,PrivateDerivationSource.VideoPlan plan,
            PrivateDerivationSource.VideoAudioPlan soundtrack) {
        double video=(double)plan.tick(plan.durations().size())/plan.timescale(),source=initial.source().durationSeconds();
        if(soundtrack==null||!soundtrack.hasAudio())return Double.compare(video,source)==0;
        // The independently validated movie duration can include a short audio tail.
        // Do not extend visual evidence to that tail; the whole soundtrack covers it.
        return Double.compare(soundtrack.durationSeconds(),source)==0&&video<=source&&source-video<=1;
    }
    private VideoDerivation(){ }
}
