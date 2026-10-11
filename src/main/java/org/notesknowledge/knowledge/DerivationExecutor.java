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
        var budget=new DerivationAttemptBudget();
        try {
            var initial=gate.issue(claim).orElseThrow(()->new DerivationFailure(KnowledgeWork.Failure.INVALID_SOURCE));
            var embed=embeddings.getIfAvailable();
            if(embed==null||!embed.available())throw new DerivationFailure(KnowledgeWork.Failure.PROVIDER_UNAVAILABLE);
            var dispatches=new ArrayList<org.notesknowledge.DispatchCoordinator.Handle>();
            // Acquisition is also serialized; the later provider dispatch still obtains a NEW gate/lock.
            var material=coordinated(claim,dispatches,permit->transactions.acquire(claim));
            budget.check();
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
                if(understand==null||!understand.available())throw new DerivationFailure(KnowledgeWork.Failure.PROVIDER_UNAVAILABLE);
                if(initial.source().modality().equals("pdf")) {
                    if(!work.heartbeat(claim))throw new DerivationFailure(KnowledgeWork.Failure.INVALID_SOURCE);
                    var pages=coordinated(claim,dispatches,permit->material.pdfPages(bytes));
                    budget.check();
                    Integer count=initial.source().pages();
                    if(count==null||pages.size()!=count)throw new DerivationFailure(KnowledgeWork.Failure.INVALID_OUTPUT);
                    var extracted=new ArrayList<DerivedSegment>();
                    for(int i=0;i<count;i++) {
                        var page=pages.get(i);
                        if(page.page()!=i+1)throw new DerivationFailure(KnowledgeWork.Failure.INVALID_OUTPUT);
                        for(var chunk:new MarkdownChunker().chunk(page.text())) {
                            if(extracted.size()>=512)throw new DerivationFailure(KnowledgeWork.Failure.BUDGET_EXCEEDED);
                            extracted.add(new DerivedSegment(chunk.text(),"pdf_text","",chunk.start(),chunk.end(),page.page(),null,null,null,null,null,null));
                        }
                    }
                    // Text-readable pages can still contain meaningful diagrams or scanned
                    // regions. Inspect every page in bounded scopes, never just a top-K subset.
                    for(int first=1;first<=count;first+=MediaUnderstandingRequest.PDF_BATCH_PAGES) {
                        int last=Math.min(count,first+MediaUnderstandingRequest.PDF_BATCH_PAGES-1);
                        if(pages.subList(first-1,last).stream().allMatch(org.notesknowledge.knowledge.spi.PrivateDerivationSource.PdfPage::provenEmpty))continue;
                        var request=new MediaUnderstandingRequest("application/pdf",first,last,null);
                        for(var segment:understand(claim,dispatches,understand,bytes,request,budget)) {
                            if(pages.get(segment.page()-1).provenEmpty())throw new DerivationFailure(KnowledgeWork.Failure.INVALID_OUTPUT);
                            String nativeText=pages.get(segment.page()-1).text().strip().replaceAll("\\s+"," ");
                            if(!nativeText.contains(segment.text().strip().replaceAll("\\s+"," ")))extracted.add(segment);
                        }
                        if(extracted.size()>512)throw new DerivationFailure(KnowledgeWork.Failure.BUDGET_EXCEEDED);
                    }
                    // No silent mixed/scan-only completion if a textless page was omitted.
                    for(var page:pages)if(!page.provenEmpty()&&page.text().isBlank()&&extracted.stream().noneMatch(s->s.page()==page.page()))
                        throw new DerivationFailure(KnowledgeWork.Failure.INVALID_OUTPUT);
                    segments=List.copyOf(extracted);
                } else {
                    // The full accepted WAV (<=10 MiB) fits the encoded budget. Keep its
                    // original bytes and time base; no transformed-source approval is inferred.
                    var request=new MediaUnderstandingRequest(initial.source().mediaType(),null,null,initial.source().durationSeconds());
                    segments=understand(claim,dispatches,understand,bytes,request,budget);
                }
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
                // JSON escaping can expand control characters sixfold. Account conservatively
                // for the request envelope without retaining content in budget/telemetry state.
                long encodedEstimate=MediaUnderstandingRequest.ENVELOPE_RESERVE_BYTES;
                for(var segment:batch)encodedEstimate+=6L*segment.text().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                budget.providerRequest(false,encodedEstimate);
                var output=coordinated(claim,dispatches,permit->{
                    budget.check();
                    return embed.embed(permit,batch.stream().map(DerivedSegment::text).toList());
                });
                budget.check();
                if(output.size()!=batch.size())throw new DerivationFailure(KnowledgeWork.Failure.INVALID_OUTPUT);
                for(float[] vector:output)vectors.add(initial.lineage().prepare(vector));
            }
            budget.check();
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
    private List<DerivedSegment> understand(KnowledgeWork.Claim claim,List<org.notesknowledge.DispatchCoordinator.Handle> dispatches,
            MediaUnderstandingPort provider,byte[] bytes,MediaUnderstandingRequest request,DerivationAttemptBudget budget) {
        // Whole-file inline budget also applies to controlled adapters, not only Google.
        request.requireInlineBudget(bytes.length,GoogleDerivationAdapters.mediaInstruction(request),GoogleDerivationAdapters.mediaSchema());
        long encodedEstimate=4L*((bytes.length+2L)/3)+MediaUnderstandingRequest.ENVELOPE_RESERVE_BYTES
            +GoogleDerivationAdapters.mediaInstruction(request).getBytes(java.nio.charset.StandardCharsets.UTF_8).length
            +GoogleDerivationAdapters.mediaSchema().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        budget.providerRequest(true,encodedEstimate);
        if(!work.heartbeat(claim))throw new DerivationFailure(KnowledgeWork.Failure.INVALID_SOURCE);
        var segments=coordinated(claim,dispatches,permit->{
            budget.check();
            return provider.describe(permit,bytes,request);
        });
        budget.check();
        GoogleDerivationAdapters.validateScope(request,segments);
        if(segments.isEmpty()&&!request.mediaType().equals("application/pdf"))throw new DerivationFailure(KnowledgeWork.Failure.INVALID_OUTPUT);
        return List.copyOf(segments);
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
                case "video" -> segment.kind().equals("video_scene");
                case "pdf" -> segment.kind().equals("pdf_text");
                default -> false;
            };
            if(!valid||segment.page()!=null&&(permit.source().pages()==null||segment.page()>permit.source().pages())
                ||segment.timeEnd()!=null&&(permit.source().durationSeconds()==null||segment.timeEnd()>permit.source().durationSeconds()))
                throw new DerivationFailure(KnowledgeWork.Failure.INVALID_OUTPUT);
        }
    }
}
