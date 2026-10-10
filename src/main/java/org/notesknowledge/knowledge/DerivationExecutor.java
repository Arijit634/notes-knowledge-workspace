package org.notesknowledge.knowledge;

import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import io.micrometer.core.instrument.MeterRegistry;

/** Orchestration deliberately has no transaction annotation. Every provider call is between short gates. */
@Service
class DerivationExecutor {
    private final AiProcessingGate gate;
    private final DerivationTransactions transactions;
    private final KnowledgeWorkService work;
    private final ObjectProvider<TextEmbeddingPort> embeddings;
    private final ObjectProvider<MediaUnderstandingPort> media;
    private final MeterRegistry metrics;
    private final org.notesknowledge.DispatchCoordinator coordination;
    private final AiDerivationProperties configuration;
    DerivationExecutor(AiProcessingGate gate,DerivationTransactions transactions,KnowledgeWorkService work,
            ObjectProvider<TextEmbeddingPort> embeddings,ObjectProvider<MediaUnderstandingPort> media,MeterRegistry metrics,
            org.notesknowledge.DispatchCoordinator coordination,AiDerivationProperties configuration) {
        this.gate=gate;this.transactions=transactions;this.work=work;this.embeddings=embeddings;this.media=media;this.metrics=metrics;
        this.coordination=coordination;
        this.configuration=configuration;
    }
    void execute(KnowledgeWork.Claim claim) {
        String outcome="deferred";
        try {
            var initial=gate.issue(claim).orElseThrow(()->new DerivationFailure(KnowledgeWork.Failure.INVALID_SOURCE));
            var embed=embeddings.getIfAvailable();
            if(embed==null||!embed.available())throw new DerivationFailure(KnowledgeWork.Failure.PROVIDER_UNAVAILABLE);
            var dispatches=new ArrayList<org.notesknowledge.DispatchCoordinator.Handle>();
            // Acquisition is also serialized; the later provider dispatch still obtains a NEW gate/lock.
            var material=coordinated(claim,dispatches,permit->transactions.acquire(claim));
            List<DerivedSegment> segments;
            if(initial.source().modality().equals("note"))segments=new MarkdownChunker().chunk(material.markdown());
            else {
                var understand=media.getIfAvailable();
                long size=initial.source().sizeBytes();
                if(size<=0||size>50L*1024*1024)throw new DerivationFailure(KnowledgeWork.Failure.BUDGET_EXCEEDED);
                byte[] bytes;
                bytes=coordinated(claim,dispatches,permit->{
                    try(var input=material.open()) {
                        var content=input.readNBytes((int)size+1);
                        if(content.length!=size)throw new DerivationFailure(KnowledgeWork.Failure.INVALID_SOURCE);
                        return content;
                    } catch(java.io.IOException unavailable) {throw new DerivationFailure(KnowledgeWork.Failure.TRANSIENT_DEPENDENCY);}
                });
                var pages=initial.source().modality().equals("pdf")?coordinated(claim,dispatches,permit->material.pdfPages(bytes)):List.<org.notesknowledge.knowledge.spi.PrivateDerivationSource.PdfPage>of();
                var extracted=new ArrayList<DerivedSegment>();
                for(var page:pages)for(var chunk:new MarkdownChunker().chunk(page.text())) {
                    if(extracted.size()>=512)throw new DerivationFailure(KnowledgeWork.Failure.BUDGET_EXCEEDED);
                    extracted.add(new DerivedSegment(chunk.text(),"pdf_text","",chunk.start(),chunk.end(),page.page(),null,null,null,null,null,null));
                }
                if(extracted.isEmpty()) {
                    if(understand==null||!understand.available())throw new DerivationFailure(KnowledgeWork.Failure.PROVIDER_UNAVAILABLE);
                    segments=coordinated(claim,dispatches,permit->List.copyOf(understand.describe(permit,bytes,initial.source().mediaType())));
                } else segments=List.copyOf(extracted);
                validateMedia(initial,segments);
            }
            if(segments.isEmpty()||segments.size()>512)throw new DerivationFailure(KnowledgeWork.Failure.BUDGET_EXCEEDED);
            var vectors=new ArrayList<float[]>();
            // Embedding 2 sends one SDK request per chunk. Do not carry a short-lived
            // permit across sequential network calls; each obtains fresh coordinated authority.
            int batchSize=GeminiTextEmbeddings.embedding2(configuration)?1:16;
            for(int start=0;start<segments.size();start+=batchSize) {
                if(!work.heartbeat(claim))throw new DerivationFailure(KnowledgeWork.Failure.INVALID_SOURCE);
                var batch=segments.subList(start,Math.min(start+batchSize,segments.size()));
                var output=coordinated(claim,dispatches,permit->embed.embed(permit,batch.stream().map(DerivedSegment::text).toList()));
                if(output.size()!=batch.size())throw new DerivationFailure(KnowledgeWork.Failure.INVALID_OUTPUT);
                for(float[] vector:output)vectors.add(initial.lineage().prepare(vector));
            }
            if(!transactions.activate(claim,initial.lineage(),segments,vectors,List.copyOf(dispatches))){work.obsolete(claim);outcome="obsolete";}
            else outcome="activated";
        } catch(DerivationFailure failure) {
            switch(failure.category) {
                case TRANSIENT_DEPENDENCY,QUOTA,PROVIDER_UNAVAILABLE -> {work.retry(claim);outcome="retry";}
                case INVALID_SOURCE,POLICY_BLOCKED,LINEAGE_OBSOLETE -> {work.obsolete(claim);outcome="obsolete";}
                default -> {work.fail(claim,failure.category);outcome="failed";}
            }
        } catch(org.notesknowledge.websupport.ApiFailureException boundary) {
            // Source/policy adapters expose only registered failures, never parser
            // or storage diagnostics. A missing source cannot be retried as eligible.
            if(boundary.kind()==org.notesknowledge.websupport.ApiFailureException.Kind.RESOURCE_NOT_FOUND
                    ||boundary.kind()==org.notesknowledge.websupport.ApiFailureException.Kind.INVALID_CREDENTIALS){work.obsolete(claim);outcome="obsolete";}
            else if(boundary.kind()==org.notesknowledge.websupport.ApiFailureException.Kind.SERVICE_UNAVAILABLE){work.retry(claim);outcome="retry";}
            else {work.fail(claim,KnowledgeWork.Failure.INVALID_OUTPUT);outcome="failed";}
        } catch(org.springframework.dao.DataAccessException|org.springframework.transaction.TransactionException unavailable) {
            // No success is reported. The existing expired-claim recovery path
            // owns this attempt if the database cannot perform a safe transition.
            // Do not log an exception which can contain source/SQL diagnostics.
            outcome="deferred";
        }
        finally {metrics.counter("knowledge.derivation.outcome","outcome",outcome).increment();}
    }
    private <T> T coordinated(KnowledgeWork.Claim claim,List<org.notesknowledge.DispatchCoordinator.Handle> outcomes,
            java.util.function.Function<AiProcessingGate.SourceAiPermit,T> effect) {
        var expected=claim.intent().expected();
        var handle=coordination.dispatch(expected.owner(),expected.noteId());
        outcomes.add(handle);
        T result;
        try(handle) {
            var permit=gate.issueForDispatch(claim,handle).orElseThrow(()->new DerivationFailure(KnowledgeWork.Failure.INVALID_SOURCE));
            permit.requireDispatch();
            result=effect.apply(permit);
        }
        if(!handle.safelyReleased())throw new DerivationFailure(KnowledgeWork.Failure.TRANSIENT_DEPENDENCY);
        return result;
    }
    static void validateMedia(AiProcessingGate.SourceAiPermit permit,List<DerivedSegment> segments) {
        if(segments.isEmpty()||segments.size()>512)throw new DerivationFailure(KnowledgeWork.Failure.INVALID_OUTPUT);
        for(var segment:segments) {
            boolean valid=switch(permit.source().modality()) {
                case "image" -> segment.kind().equals("whole_image")||segment.kind().equals("image_region");
                case "audio" -> segment.kind().equals("transcript");
                case "video" -> segment.kind().equals("transcript")||segment.kind().equals("video_scene");
                case "pdf" -> segment.kind().equals("pdf_text");
                default -> false;
            };
            if(!valid||segment.page()!=null&&(permit.source().pages()==null||segment.page()>permit.source().pages())
                ||segment.timeEnd()!=null&&(permit.source().durationSeconds()==null||segment.timeEnd()>permit.source().durationSeconds()))
                throw new DerivationFailure(KnowledgeWork.Failure.INVALID_OUTPUT);
        }
    }
}
