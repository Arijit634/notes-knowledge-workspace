package org.notesknowledge.knowledge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.notesknowledge.DispatchCoordinator;
import org.notesknowledge.knowledge.spi.PrivateDerivationSource;
import org.notesknowledge.knowledge.spi.PrivateQuerySource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Bounded request-local acoustic reranking/verification. Existing persistent
 * representations remain text surrogates; native vectors never enter that index. */
@Service
class DirectAudioSearch {
    static final int MAX_SOURCES=3,MAX_CALLS=6;
    private static final java.util.regex.Pattern AUDIO_QUERY=java.util.regex.Pattern.compile(
        "(?iu)\\b(audio|sound|sounds|soundtrack|music|song|singing|beat|beats|noise|noises|recording|voice|voices|melody|vocal|vocals)\\b|গান|সঙ্গীত|শব্দ");
    private static final java.util.regex.Pattern VIDEO_QUERY=java.util.regex.Pattern.compile("(?iu)\\b(video|videos)\\b");
    private final AiDerivationProperties configuration;
    private final ObjectProvider<AudioSignalPort> ports;
    private final KnowledgeQueryGate gate;
    private final PrivateQuerySource sources;
    private final PrivateDerivationSource media;
    private final QueryEvidenceRepository evidence;
    private final DispatchCoordinator coordination;
    private final ObjectProvider<PlatformTransactionManager> managers;
    DirectAudioSearch(AiDerivationProperties configuration,ObjectProvider<AudioSignalPort> ports,KnowledgeQueryGate gate,
            PrivateQuerySource sources,PrivateDerivationSource media,QueryEvidenceRepository evidence,
            DispatchCoordinator coordination,ObjectProvider<PlatformTransactionManager> managers) {
        this.configuration=configuration;this.ports=ports;this.gate=gate;this.sources=sources;this.media=media;this.evidence=evidence;this.coordination=coordination;
        this.managers=managers;
    }
    boolean applies(String query){return (configuration.directAudioSearch()||configuration.directVideoSoundtrackSearch())&&AUDIO_QUERY.matcher(query).find();}
    private boolean processes(String modality,String query){
        if(VIDEO_QUERY.matcher(query).find())return configuration.directVideoSoundtrackSearch()&&modality.equals("video");
        return configuration.directAudioSearch()&&modality.equals("audio")||configuration.directVideoSoundtrackSearch()&&modality.equals("video");
    }
    boolean excludesOnFailure(String modality,String query){return processes(modality,query)||configuration.directVideoSoundtrackSearch()&&VIDEO_QUERY.matcher(query).find()&&modality.equals("audio");}
    private record Scored(QueryEvidenceRepository.Evidence evidence,double affinity) { }
    record Refined(List<QueryEvidenceRepository.Evidence> evidence,boolean videoVerified){Refined{evidence=List.copyOf(evidence);}}
    private static final class Budget {
        private final long started=System.nanoTime();private int calls;
        void next(){if(calls>=MAX_CALLS||System.nanoTime()-started>=90_000_000_000L)throw new DerivationFailure(KnowledgeWork.Failure.BUDGET_EXCEEDED);calls++;}
        void check(){if(System.nanoTime()-started>=90_000_000_000L)throw new DerivationFailure(KnowledgeWork.Failure.BUDGET_EXCEEDED);}
    }
    Refined refine(UUID owner,String query,float[] queryVector,List<QueryEvidenceRepository.Evidence> selected) {
        return refine(owner,query,queryVector,selected,selected);
    }
    Refined refine(UUID owner,String query,float[] queryVector,List<QueryEvidenceRepository.Evidence> selected,
            List<QueryEvidenceRepository.Evidence> retrieved) {
        if(!applies(query))return new Refined(selected,false);
        // Independently retrieved video sources must be heard before the answer's
        // small segment window can exclude them. WAV inspection retains its existing
        // selected-context scope; neither path discovers sibling Attachments.
        var inspection=new ArrayList<QueryEvidenceRepository.Evidence>();
        for(var row:retrieved)if(row.modality().equals("video")&&processes("video",query))inspection.add(row);
        for(var row:selected)if(!row.modality().equals("video")&&processes(row.modality(),query))inspection.add(row);
        if(inspection.isEmpty())return new Refined(selected.stream().filter(e->!excludesOnFailure(e.modality(),query)).toList(),false);
        var port=ports.getIfAvailable();if(port==null||!port.available())throw new DerivationFailure(KnowledgeWork.Failure.PROVIDER_UNAVAILABLE);
        var unique=new LinkedHashMap<Object,QueryEvidenceRepository.Evidence>();
        // Preserve the selected modality order for mixed questions while allowing
        // additional independently retrieved videos to fill unused source slots.
        selected.stream().filter(e->processes(e.modality(),query)).forEach(e->unique.putIfAbsent(e.expected(),e));
        inspection.forEach(e->unique.putIfAbsent(e.expected(),e));
        var budget=new Budget();var ranked=new ArrayList<Scored>();
        var candidates=unique.values().stream().limit(MAX_SOURCES).toList();
        for(var row:candidates) {
            if(!row.modality().equals("audio"))continue; // Video embeddings omit sound; never use them as acoustic evidence.
            float[] vector=sourceEffect(owner,query,row,(bytes,seconds,permit)->{budget.next();return port.embed(permit,query,bytes);});
            try{budget.check();ranked.add(new Scored(row,cosine(queryVector,vector)));}finally{Arrays.fill(vector,0);}
        }
        ranked.sort(Comparator.comparingDouble(Scored::affinity).reversed());
        var audioOrder=ranked.iterator();
        // Only reorder native-audio slots; MP4 sources keep their authorized RRF
        // positions. A cosine score and a missing video audio embedding are not comparable.
        var ordered=candidates.stream().map(e->e.modality().equals("audio")?audioOrder.next().evidence():e).toList();
        var verifiedRows=new ArrayList<QueryEvidenceRepository.Evidence>();
        var supportedVideos=new java.util.HashSet<Object>();
        // Similarity only determines inspection order. Every claimed audio match
        // requires a supported, current raw-audio observation, including negatives.
        for(var row:ordered) {
            boolean video=row.modality().equals("video");
            var verified=sourceEffect(owner,query,row,(bytes,seconds,permit)->{budget.next();return video?port.verifyVideo(permit,query,bytes,seconds):port.verify(permit,query,bytes,seconds);});
            budget.check();if(!verified.supported())continue;
            var observation=new DerivedSegment("Audio description (question-verified model inference, not spoken words): "+verified.description(),video?"video_scene":"transcript","Acoustic description",null,null,null,verified.startMs()/1000d,verified.endMs()/1000d,null,null,null,null);
            verifiedRows.add(new QueryEvidenceRepository.Evidence(row.segmentId(),row.representationId(),row.expected(),row.lineage(),row.modality(),row.ordinal(),observation));
            if(video)supportedVideos.add(row.expected());
        }
        // Sound verification must not erase already selected visual evidence for
        // a combined visual+audio question. Never retain an unverified sound claim.
        var result=new ArrayList<>(verifiedRows);int characters=result.stream().mapToInt(e->e.segment().text().length()).sum();
        for(var row:selected) {
            boolean keep=!excludesOnFailure(row.modality(),query)||supportedVideos.contains(row.expected())
                &&!java.util.Set.of("Acoustic description","Transcript").contains(row.segment().heading());
            if(keep&&result.size()<KnowledgeQueryLimits.FOCUSED&&characters+row.segment().text().length()<=KnowledgeQueryLimits.CHARACTERS){result.add(row);characters+=row.segment().text().length();}
        }
        return new Refined(result,!supportedVideos.isEmpty());
    }
    @FunctionalInterface private interface Effect<T>{T apply(byte[] bytes,double seconds,KnowledgeQueryGate.EvidencePermit permit);}
    private <T>T sourceEffect(UUID owner,String query,QueryEvidenceRepository.Evidence row,Effect<T> effect) {
        var handle=coordination.dispatch(owner,row.expected().noteId());T result;
        try(handle) {
            var source=tx(()->sources.source(owner,row.expected().noteId(),row.expected().attachmentId()));
            var permit=tx(()->{
                var qp=gate.query(owner,query);
                if(!source.expected().equals(row.expected())||!evidence.current(row,qp.lineage(row.modality())))throw new DerivationFailure(KnowledgeWork.Failure.INVALID_SOURCE);
                return gate.evidence(owner,query,List.of(source),handle);
            });
            var metadata=tx(()->media.noteSources(owner,row.expected().noteId())).stream().filter(m->m.expected().equals(row.expected())).findFirst().orElseThrow(()->new DerivationFailure(KnowledgeWork.Failure.INVALID_SOURCE));
            boolean video=row.modality().equals("video");
            if(!metadata.modality().equals(row.modality())||!metadata.mediaType().equals(video?"video/mp4":"audio/wav")||metadata.durationSeconds()==null
                    ||!Double.isFinite(metadata.durationSeconds())||metadata.durationSeconds()<=0||metadata.durationSeconds()>(video?30:180)
                    ||metadata.sizeBytes()<(video?16:44)||metadata.sizeBytes()>(video?20_000_000:10_485_760))throw new DerivationFailure(KnowledgeWork.Failure.BUDGET_EXCEEDED);
            if(video)new MediaUnderstandingRequest("video/mp4",null,null,metadata.durationSeconds(),true,true).requireInlineBudget((int)metadata.sizeBytes(),"","");
            permit.requireGoogle(query);var material=tx(()->media.acquire(row.expected()));byte[] bytes;
            try(var input=material.open()){bytes=input.readNBytes((int)metadata.sizeBytes()+1);}catch(java.io.IOException unavailable){throw new DerivationFailure(KnowledgeWork.Failure.TRANSIENT_DEPENDENCY);}
            try {
                if(bytes.length!=metadata.sizeBytes())throw new DerivationFailure(KnowledgeWork.Failure.INVALID_SOURCE);
                permit.requireGoogle(query);result=effect.apply(bytes,metadata.durationSeconds(),permit);
            }finally{Arrays.fill(bytes,(byte)0);}
        }
        if(!handle.safelyReleased()){if(result instanceof float[] vector)Arrays.fill(vector,0);throw new DerivationFailure(KnowledgeWork.Failure.INVALID_SOURCE);}
        try {
            boolean current=tx(()->{var qp=gate.query(owner,query);return evidence.current(row,qp.lineage(row.modality()));});
            if(!current)throw new DerivationFailure(KnowledgeWork.Failure.INVALID_SOURCE);
        }catch(RuntimeException changed){if(result instanceof float[] vector)Arrays.fill(vector,0);throw changed;}
        return result;
    }
    private <T>T tx(java.util.function.Supplier<T> body){var transactions=new TransactionTemplate(managers.getObject());transactions.setTimeout(3);return transactions.execute(s->body.get());}
    static double cosine(float[] a,float[] b) {
        if(a==null||b==null||a.length!=b.length)throw new DerivationFailure(KnowledgeWork.Failure.INVALID_OUTPUT);
        double dot=0,aa=0,bb=0;
        for(int i=0;i<a.length;i++){if(!Float.isFinite(a[i])||!Float.isFinite(b[i]))throw new DerivationFailure(KnowledgeWork.Failure.INVALID_OUTPUT);dot+=(double)a[i]*b[i];aa+=(double)a[i]*a[i];bb+=(double)b[i]*b[i];}
        if(aa<=0||bb<=0)throw new DerivationFailure(KnowledgeWork.Failure.INVALID_OUTPUT);return dot/Math.sqrt(aa*bb);
    }
}
