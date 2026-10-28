package org.notesknowledge.knowledge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.notesknowledge.DispatchCoordinator;
import org.notesknowledge.knowledge.spi.PrivateLexicalSearch;
import org.notesknowledge.knowledge.spi.PrivateKnowledgeSource;
import org.notesknowledge.knowledge.spi.PrivateQuerySource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class KnowledgeQueryEngine {
    private int extractionBatchSize=12;
    @org.springframework.beans.factory.annotation.Value("${knowledge.operations.extraction-batch-size:12}")
    void extractionBatchSize(int value){if(value!=12&&value!=KnowledgeQueryLimits.EXTRACTION)throw new IllegalArgumentException("Invalid extraction batch size");extractionBatchSize=value;}
    int extractionBatchSize(){return extractionBatchSize;}
    record Generated(KnowledgeQueryResult result,List<PrivateQuerySource.Source> provenance,java.util.Map<String,String> lineages) { }
    private record RankedEvidence(QueryEvidenceRepository.Evidence item,int sourceRank,double withinSourceRank) { }
    private final PrivateLexicalSearch lexical;
    private final PrivateQuerySource sources;
    private final ExactPrivateVectorSearch vectors;
    private final QueryEvidenceRepository evidence;
    private final KnowledgeQueryGate gate;
    private final ObjectProvider<StructuredKnowledgePort> providers;
    private final DispatchCoordinator coordination;
    private final ObjectProvider<PlatformTransactionManager> managers;
    private RetrievalTelemetry telemetry;
    @org.springframework.beans.factory.annotation.Autowired
    void telemetry(RetrievalTelemetry telemetry){this.telemetry=telemetry;}
    KnowledgeQueryEngine(PrivateLexicalSearch lexical,PrivateQuerySource sources,ExactPrivateVectorSearch vectors,QueryEvidenceRepository evidence,
        KnowledgeQueryGate gate,ObjectProvider<StructuredKnowledgePort> providers,DispatchCoordinator coordination,ObjectProvider<PlatformTransactionManager> managers) {
        this.lexical=lexical;this.sources=sources;this.vectors=vectors;this.evidence=evidence;this.gate=gate;this.providers=providers;this.coordination=coordination;this.managers=managers;
    }
    private <T>T tx(java.util.function.Supplier<T> work){var t=new TransactionTemplate(managers.getObject());t.setTimeout(3);return t.execute(s->work.get());}
    Generated ranked(UUID owner,KnowledgeQueryRequest request) {
        long started=System.nanoTime();
        var signals=new ArrayList<List<UUID>>();var sourceMap=new LinkedHashMap<UUID,PrivateQuerySource.Source>();
        var lexicalMatches=new LinkedHashMap<UUID,PrivateQuerySource.Source>();
        for(String lifecycle:request.lifecycles()) {
            var rows=tx(()->lexical.search(new PrivateLexicalSearch.Query(request.query(),PrivateKnowledgeSource.Lifecycle.valueOf(lifecycle.toUpperCase(java.util.Locale.ROOT)),List.of(),request.savedDuring())));
            var ids=new ArrayList<UUID>();
            for(var row:rows) {var current=sources.note(owner,row.noteId());if(current.expected().revision()==row.revision()){sourceMap.put(row.noteId(),current);lexicalMatches.put(row.noteId(),current);ids.add(row.noteId());}}
            signals.add(ids);
        }
        var ordinaryOrder=ReciprocalRankFusion.fuse(signals,50);
        var selected=new ArrayList<QueryEvidenceRepository.Evidence>();String degraded=null;KnowledgeQueryGate.QueryPermit permit=null;
        if(telemetry!=null)telemetry.candidates(request.plan(),RetrievalTelemetry.Stage.LEXICAL,sourceMap.size());
        var relevant=new LinkedHashMap<UUID,ExactPrivateVectorSearch.Candidate>();
        var lexicalNotes=sourceMap.values().stream().filter(PrivateQuerySource.Source::aiEnabled).map(s->s.expected().noteId()).toList();
        var provider=providers.getIfAvailable();
        try {
            if(provider==null||!provider.available())throw new DerivationFailure(KnowledgeWork.Failure.PROVIDER_UNAVAILABLE);
            permit=gate.query(owner,request.query());permit.requireDispatch();float[] query=provider.embedQuery(permit,request.query());
            // Same embedding configuration, separately compatible modality lineage, never an incompatible fallback.
            for(String modality:List.of("note","image","audio","video","pdf")) {
                var candidates=vectors.search(owner,modality,query,50,request.savedDuring());var ids=new ArrayList<UUID>();
                for(var candidate:candidates) {
                    var source=sources.source(owner,candidate.expected().noteId(),candidate.expected().attachmentId());
                    if(!request.lifecycles().contains(source.lifecycle())||!source.expected().equals(candidate.expected()))continue;
                    var note=sources.note(owner,source.expected().noteId());sourceMap.put(note.expected().noteId(),note);ids.add(note.expected().noteId());
                    relevant.putIfAbsent(candidate.segmentId(),candidate);
                }
                signals.add(ids.stream().distinct().toList());
            }
            // A lexical Note does not authorize its media. Only current Note-text nearest
            // segments are additional candidates; Attachments require independent retrieval.
            for(var candidate:vectors.searchNotes(owner,lexicalNotes,query,50,request.savedDuring()))
                if(candidate.expected().equals(sourceMap.get(candidate.expected().noteId()).expected()))relevant.putIfAbsent(candidate.segmentId(),candidate);
        } catch(DerivationFailure failure){degraded=safeReason(failure);}
        var order=ReciprocalRankFusion.fuse(signals,50);
        if(telemetry!=null)telemetry.candidates(request.plan(),RetrievalTelemetry.Stage.EXACT,relevant.size());
        if(permit!=null&&degraded==null) {
            var qp=permit;
            int characters=0;
            // Bounded positive-affinity candidates, not an epsilon-equal nearest cohort.
            // Distance is a rank signal, never a calibrated correctness probability.
            var ranks=new java.util.HashMap<UUID,Integer>();for(int i=0;i<order.size();i++)ranks.put(order.get(i),i);
            var pool=new ArrayList<RankedEvidence>();
            for(var candidate:relevant.values().stream().filter(c->Double.isFinite(c.distance())
                &&(!"cosine".equals(qp.lineage("note").operator())||c.distance()<1))
                .sorted(java.util.Comparator.comparingInt((ExactPrivateVectorSearch.Candidate c)->ranks.getOrDefault(c.expected().noteId(),Integer.MAX_VALUE))
                    .thenComparingDouble(ExactPrivateVectorSearch.Candidate::distance)
                    .thenComparing(ExactPrivateVectorSearch.Candidate::segmentId)).toList()) {
                String modality=candidate.expected().attachmentId()==null?"note":sources.source(owner,candidate.expected().noteId(),candidate.expected().attachmentId()).modality();
                var item=tx(()->evidence.candidate(candidate,qp.lineage(modality)));
                if(item!=null)pool.add(new RankedEvidence(item,ranks.getOrDefault(candidate.expected().noteId(),Integer.MAX_VALUE),candidate.distance()));
                if(pool.size()==50)break;
            }
            // A focused fact can use its owner-authorized lexical match before a
            // vector exists. An existing representation with poor affinity is NOT
            // overridden by this path, and ranked/vector queries keep their plan.
            if(request.plan()==KnowledgeQueryRequest.Plan.FOCUSED)try {
                for(var id:lexicalNotes.stream().sorted(java.util.Comparator.comparingInt(n->ranks.getOrDefault(n,Integer.MAX_VALUE))).limit(12).toList()) {
                    var source=sourceMap.get(id);var lineage=qp.lineage("note");
                    if(!tx(()->evidence.page(source.expected(),lineage,0)).isEmpty())continue;
                    for(var item:currentNoteEvidence(owner,request.query(),source,lineage,0,()->true).stream().limit(2).toList())
                        pool.add(new RankedEvidence(item,ranks.getOrDefault(id,Integer.MAX_VALUE),-Double.MAX_VALUE));
                }
            }catch(DerivationFailure blocked){degraded=safeReason(blocked);}
            catch(org.notesknowledge.websupport.ApiFailureException unavailable){degraded="coordinationUnavailable";}
            if(degraded==null)for(var row:pool.stream().sorted(java.util.Comparator.comparingInt(RankedEvidence::sourceRank)
                    .thenComparingDouble(RankedEvidence::withinSourceRank)
                    .thenComparing(e->e.item().segmentId(),java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder()))).toList()) {
                var item=row.item();
                if(characters+item.segment().text().length()<=48000&&selected.stream().noneMatch(e->overlaps(e,item))) {
                    selected.add(item);characters+=item.segment().text().length();
                }
                if(selected.size()==12)break;
            }
        }
        // Fusion orders model candidates; it does not establish an ordinary-search
        // match. Keep AI-OFF lexical hits, but never promote vector-only neighbors.
        var deterministic=ordinaryOrder.stream().map(lexicalMatches::get).filter(s->(request.savedDuring()==null||request.savedDuring().contains(s.createdAt()))&&sources.matches(s,false))
            .map(s->new KnowledgeQueryResult.DeterministicItem("note",s.title(),List.of(citation(s,null)),s.aiEnabled())).toList();
        if(telemetry!=null){telemetry.candidates(request.plan(),RetrievalTelemetry.Stage.CONTEXT,selected.size());telemetry.preGeneration(request.plan(),System.nanoTime()-started);}
        Generated answer=selected.isEmpty()?empty(List.of(),degraded,degraded==null&&request.plan()==KnowledgeQueryRequest.Plan.FOCUSED):generate(owner,request.query(),"answer",selected,()->true);
        var retained=new ArrayList<>(answer.provenance());
        for(var item:deterministic)for(var occurrence:item.occurrences()){var source=sourceMap.get(occurrence.noteId());if(source!=null)retained.add(referenceOnly(source));}
        var r=answer.result();return new Generated(new KnowledgeQueryResult(deterministic,r.aiAnswer(),r.citations(),new KnowledgeQueryResult.Coverage("bounded",sourceMap.size(),false,false,false,false,false),degraded!=null?degraded:r.degraded(),r.insufficientEvidence()),retained.stream().distinct().toList(),answer.lineages());
    }
    static boolean overlaps(QueryEvidenceRepository.Evidence a,QueryEvidenceRepository.Evidence b) {
        if(a.segmentId()!=null&&a.segmentId().equals(b.segmentId()))return true;
        if(a.representationId()==null||b.representationId()==null)return a.representationId()==null&&b.representationId()==null
            &&a.expected().equals(b.expected())&&java.util.Objects.equals(a.segment().start(),b.segment().start())
            &&java.util.Objects.equals(a.segment().end(),b.segment().end());
        if(!a.representationId().equals(b.representationId()))return false;
        var x=a.segment();var y=b.segment();
        if(!x.kind().equals(y.kind())||!java.util.Objects.equals(x.page(),y.page()))return false;
        // Equal words at different pages/times/regions are separate occurrences,
        // not duplicate context. Preserve their independently navigable evidence.
        if(x.text().equals(y.text())&&java.util.Objects.equals(x.timeStart(),y.timeStart())
                &&java.util.Objects.equals(x.timeEnd(),y.timeEnd())&&java.util.Objects.equals(x.x(),y.x())
                &&java.util.Objects.equals(x.y(),y.y())&&java.util.Objects.equals(x.width(),y.width())
                &&java.util.Objects.equals(x.height(),y.height())&&java.util.Objects.equals(x.start(),y.start())
                &&java.util.Objects.equals(x.end(),y.end()))return true;
        if(x.start()!=null&&x.end()!=null&&y.start()!=null&&y.end()!=null) {
            int overlap=Math.min(x.end(),y.end())-Math.max(x.start(),y.start());
            return overlap>0&&overlap>=0.8*Math.min(x.end()-x.start(),y.end()-y.start());
        }
        return false;
    }
    record SemanticBatch(Generated generated,int completedSources,int nextOrdinal) { }
    SemanticBatch semanticBatch(UUID owner,String query,List<PrivateQuerySource.Source> page,int nextOrdinal,
            java.util.function.BooleanSupplier currentWork) {
        if(page.isEmpty())return new SemanticBatch(empty(List.of(),null,true),0,0);
        var permit=gate.query(owner,query);
        var selected=new ArrayList<QueryEvidenceRepository.Evidence>();
        int completed=0,characters=0,pendingOrdinal=0;
        for(var source:page) {
            if(selected.size()==extractionBatchSize)break;
            int start=completed==0?nextOrdinal:0;
            var lineage=permit.lineage(source.modality());
            var rows=tx(()->evidence.page(source.expected(),lineage,start,extractionBatchSize));
            if(rows.isEmpty()&&source.modality().equals("note"))rows=currentNoteEvidence(owner,query,source,lineage,start,currentWork);
            if(rows.isEmpty())throw new DerivationFailure(KnowledgeWork.Failure.LINEAGE_OBSOLETE);
            int added=0;
            while(added<rows.size()&&selected.size()<extractionBatchSize&&characters+rows.get(added).segment().text().length()<=48000) {
                var item=rows.get(added++);selected.add(item);characters+=item.segment().text().length();
            }
            if(added<rows.size()){pendingOrdinal=rows.get(added).ordinal();break;}
            completed++;
        }
        if(selected.isEmpty()) {
            if(!page.isEmpty())throw new DerivationFailure(KnowledgeWork.Failure.BUDGET_EXCEEDED);
            return new SemanticBatch(empty(List.of(),null,true),0,0);
        }
        return new SemanticBatch(generate(owner,query,"extract",selected,currentWork),completed,pendingOrdinal);
    }
    private List<QueryEvidenceRepository.Evidence> currentNoteEvidence(UUID owner,String query,PrivateQuerySource.Source source,
            EmbeddingLineage lineage,int start,java.util.function.BooleanSupplier currentWork) {
        if(start<0||start>512)throw new DerivationFailure(KnowledgeWork.Failure.INVALID_SOURCE);
        var handle=coordination.dispatch(owner,source.expected().noteId());
        List<DerivedSegment> chunks;
        try(handle) {
            var permit=tx(()->{
                if(!currentWork.getAsBoolean())throw new DerivationFailure(KnowledgeWork.Failure.INVALID_SOURCE);
                return gate.evidence(owner,query,List.of(source),handle);
            });
            permit.requireDispatch();
            chunks=new MarkdownChunker().chunk(sources.currentMarkdown(source));
            if(chunks.isEmpty()&&!source.title().isBlank())chunks=List.of(DerivedSegment.note("Note title: "+source.title(),"Note title",0,0));
        }
        if(!handle.safelyReleased())throw new DerivationFailure(KnowledgeWork.Failure.INVALID_SOURCE);
        var result=new ArrayList<QueryEvidenceRepository.Evidence>();
        for(int i=start;i<chunks.size()&&result.size()<KnowledgeQueryLimits.EXTRACTION+1;i++)result.add(QueryEvidenceRepository.Evidence.noteSnapshot(source.expected(),lineage.id(),i,chunks.get(i)));
        return List.copyOf(result);
    }
    Generated generate(UUID owner,String query,String task,List<QueryEvidenceRepository.Evidence> selected,java.util.function.BooleanSupplier currentWork) {
        if(selected.size()>KnowledgeQueryLimits.evidence(task))throw new DerivationFailure(KnowledgeWork.Failure.BUDGET_EXCEEDED);
        var provider=providers.getIfAvailable();if(provider==null||!provider.available())return empty(List.of(),"providerUnavailable",false);
        var provenance=selected.stream().map(e->sources.source(owner,e.expected().noteId(),e.expected().attachmentId())).distinct().toList();
        var handle=coordination.dispatchMany(owner,provenance.stream().map(s->s.expected().noteId()).distinct().toList());
        StructuredKnowledgePort.Output output;var payload=new ArrayList<StructuredKnowledgePort.Evidence>();var lineages=new LinkedHashMap<String,String>();
        try(handle) {
            var permit=tx(()->{
                if(!currentWork.getAsBoolean())throw new DerivationFailure(KnowledgeWork.Failure.INVALID_SOURCE);
                var qp=gate.query(owner,query);
                for(var e:selected){if(!evidence.current(e,qp.lineage(e.modality())))throw new DerivationFailure(KnowledgeWork.Failure.INVALID_SOURCE);lineages.put(e.modality(),qp.lineage(e.modality()).id());}
                return gate.evidence(owner,query,provenance,handle,KnowledgeQueryLimits.evidence(task));
            });
            int total=0;
            for(int i=0;i<selected.size();i++){var e=selected.get(i);total+=e.segment().text().length();if(total>48000)throw new DerivationFailure(KnowledgeWork.Failure.BUDGET_EXCEEDED);payload.add(new StructuredKnowledgePort.Evidence("e"+i,e.segment().text(),e.modality(),location(e.segment())));}
            permit.requireDispatch();output=provider.generate(permit,query,task,payload);
            validate(output,payload.size());
        }catch(DerivationFailure failure){
            // A durable extraction can retry this uncommitted batch; focused
            // requests still return their truthful synchronous degraded state.
            if("extract".equals(task)&&failure.retryableProvider())throw failure;
            return empty(List.of(),safeReason(failure),false);
        }
        catch(org.notesknowledge.websupport.ApiFailureException unavailable){return empty(List.of(),"coordinationUnavailable",false);}
        // Session loss is an independent veto, not merely another source predicate.
        if(!handle.safelyReleased())return empty(List.of(),"coordinationUnavailable",false);
        boolean valid;
        try{valid=tx(()->{if(!currentWork.getAsBoolean())return false;var qp=gate.query(owner);return selected.stream().allMatch(e->evidence.current(e,qp.lineage(e.modality())));});}
        catch(DerivationFailure blocked){return empty(List.of(),safeReason(blocked),false);}
        if(!valid)return empty(List.of(),"sourceChanged",false);
        var citations=new ArrayList<KnowledgeQueryResult.Citation>();
        for(var claim:output.claims())for(String id:claim.evidenceIds()){var e=selected.get(Integer.parseInt(id.substring(1)));var s=provenance.stream().filter(p->p.expected().equals(e.expected())).findFirst().orElseThrow();citations.add(citation(s,e.segment()));}
        var answer=output.claims().isEmpty()?null:new KnowledgeQueryResult.Answer(output.claims().stream().map(StructuredKnowledgePort.Claim::text).toList(),output.conflicting());
        return new Generated(new KnowledgeQueryResult(List.of(),answer,citations.stream().distinct().toList(),new KnowledgeQueryResult.Coverage("bounded",provenance.size(),false,false,false,false,false),null,answer==null),
            provenance.stream().map(KnowledgeQueryEngine::referenceOnly).toList(),lineages);
    }
    static void validate(StructuredKnowledgePort.Output output,int evidenceCount) {
        if(output==null||output.claims().size()>32)throw new DerivationFailure(KnowledgeWork.Failure.INVALID_OUTPUT);
        for(var claim:output.claims())if(claim.text()==null||claim.text().isBlank()||claim.text().length()>2000||claim.text().codePoints().anyMatch(c->Character.isISOControl(c)&&c!='\n'&&c!='\t')
            ||claim.evidenceIds().isEmpty()||claim.evidenceIds().size()>12||claim.evidenceIds().stream().anyMatch(id->id==null||!id.matches("e[0-9]{1,2}")||Integer.parseInt(id.substring(1))>=evidenceCount
                ||!id.equals("e"+Integer.parseInt(id.substring(1)))))throw new DerivationFailure(KnowledgeWork.Failure.INVALID_OUTPUT);
    }
    static PrivateQuerySource.Source referenceOnly(PrivateQuerySource.Source s){return new PrivateQuerySource.Source(s.expected(),s.lifecycle(),s.aiEnabled(),s.modality(),s.createdAt(),"");}
    static KnowledgeQueryResult.Location location(DerivedSegment d){return new KnowledgeQueryResult.Location(d.kind(),d.start(),d.end(),d.page(),d.timeStart(),d.timeEnd(),d.x(),d.y(),d.width(),d.height());}
    static KnowledgeQueryResult.Citation citation(PrivateQuerySource.Source s,DerivedSegment d){var e=s.expected();return new KnowledgeQueryResult.Citation(e.noteId(),e.attachmentId(),d==null?null:location(d));}
    static Generated empty(List<KnowledgeQueryResult.DeterministicItem> deterministic,String degraded,boolean insufficient){return new Generated(new KnowledgeQueryResult(deterministic,null,List.of(),new KnowledgeQueryResult.Coverage("bounded",0,false,false,false,false,false),degraded,insufficient),List.of(),java.util.Map.of());}
    static String safeReason(DerivationFailure failure){return switch(failure.category){case POLICY_BLOCKED->"processingPolicyRequired";case INVALID_SOURCE->"sourceChanged";case INVALID_OUTPUT->"invalidProviderOutput";case LINEAGE_OBSOLETE->"representationUnavailable";case BUDGET_EXCEEDED->"budgetExceeded";default->"providerUnavailable";};}
}
