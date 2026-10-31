package org.notesknowledge.knowledge;

import java.util.ArrayList;
import java.util.List;
import org.notesknowledge.knowledge.spi.PrivateDerivationSource;

/** Whole-source soundtrack. Its PCM sample clock is relative to original video time zero. */
final class VideoSoundtrackDerivation {
    @FunctionalInterface interface Transform {PrivateDerivationSource.VideoAudioChild render(PrivateDerivationSource.VideoAudioPlan plan);}
    static List<DerivedSegment> derive(AiProcessingGate.SourceAiPermit initial,byte[] original,
            PrivateDerivationSource.VideoAudioPlan plan,AiDerivationProperties config,Transform transform,
            VideoDerivation.Dispatch dispatch,DerivationAttemptBudget budget) {
        if(plan==null||!config.videoChildren()||initial.source().durationSeconds()==null
                ||Double.compare(plan.durationSeconds(),initial.source().durationSeconds())!=0)
            throw new DerivationFailure(KnowledgeWork.Failure.INVALID_SOURCE);
        if(!plan.hasAudio())return List.of();
        budget.check();
        try(var child=transform.render(plan)) {
            budget.check();
            if(child==null||!child.plan().equals(plan)||!child.transformFingerprint().equals(config.videoTransformFingerprint()))
                throw new DerivationFailure(KnowledgeWork.Failure.INVALID_SOURCE);
            var bytes=child.bytes();
            try {
                var identity=new TransformedMediaIdentity(initial.source().expected(),TransformedMediaIdentity.digest(original),original.length,
                    "video/mp4",child.profile(),child.implementationVersion(),child.artifactSha256(),child.configurationSha256(),
                    TransformedMediaIdentity.digest(bytes),bytes.length,"audio/wav",
                    new TransformedMediaIdentity.Scope("video_audio",0,plan.samples(),16000,0,plan.samples()),
                    initial.lineage().id(),config.provider(),config.soundtrackModel(),"media-understanding",config.approvedPolicyFingerprint());
                var request=new MediaUnderstandingRequest("audio/wav",null,null,(double)plan.samples()/16000);
                var described=dispatch.describe(identity,bytes,request);budget.check();GoogleDerivationAdapters.validateScope(request,described);
                if(described.isEmpty())throw new DerivationFailure(KnowledgeWork.Failure.INVALID_OUTPUT);
                var result=new ArrayList<DerivedSegment>();
                for(var segment:described) {
                    String text=segment.text();
                    if(segment.heading().equals("Acoustic description")) {
                        if(!text.startsWith("Audio description (model inference, not spoken words): "))text="Audio description (model inference, not spoken words): "+text;
                    }else if(java.util.Set.of("","Transcript").contains(segment.heading())) {
                        if(!text.equals("No intelligible speech was identified in this processed audio interval.")&&!text.startsWith("Spoken transcript: "))text="Spoken transcript: "+text;
                    }else throw new DerivationFailure(KnowledgeWork.Failure.INVALID_OUTPUT);
                    result.add(new DerivedSegment(text,"video_scene",segment.heading(),null,null,null,segment.timeStart(),segment.timeEnd(),null,null,null,null));
                }
                GoogleDerivationAdapters.validateScope(new MediaUnderstandingRequest("video/mp4",null,null,request.durationSeconds()),result);
                return List.copyOf(result);
            }finally{java.util.Arrays.fill(bytes,(byte)0);}
        }
    }
    private VideoSoundtrackDerivation(){ }
}
