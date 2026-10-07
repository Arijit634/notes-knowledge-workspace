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
    record Generated(KnowledgeQueryResult result,List<PrivateQuerySource.Source> provenance,java.util.Map<String,String> lineages) { }
    private final PrivateLexicalSearch lexical;
    private final PrivateQuerySource sources;
    private final ExactPrivateVectorSearch vectors;
    private final QueryEvidenceRepository evidence;
    private final KnowledgeQueryGate gate;
    private final ObjectProvider<StructuredKnowledgePort> providers;
    private final DispatchCoordinator coordination;
    private final ObjectProvider<PlatformTransactionManager> managers;
    KnowledgeQueryEngine(PrivateLexicalSearch lexical,PrivateQuerySource sources,ExactPrivateVectorSearch vectors,QueryEvidenceRepository evidence,
        KnowledgeQueryGate gate,ObjectProvider<StructuredKnowledgePort> providers,DispatchCoordinator coordination,ObjectProvider<PlatformTransactionManager> managers) {
        this.lexical=lexical;this.sources=sources;this.vectors=vectors;this.evidence=evidence;this.gate=gate;this.providers=providers;this.coordination=coordination;this.managers=managers;
    }
    private <T>T tx(java.util.function.Supplier<T> work){var t=new TransactionTemplate(managers.getObject());t.setTimeout(3);return t.execute(s->work.get());}
    Generated ranked(UUID owner,KnowledgeQueryRequest request) {
        var signals=new ArrayList<List<UUID>>();var sourceMap=new LinkedHashMap<UUID,PrivateQuerySource.Source>();
        for(String lifecycle:request.lifecycles()) {
            var rows=tx(()->lexical.search(new PrivateLexicalSearch.Query(request.query(),PrivateKnowledgeSource.Lifecycle.valueOf(lifecycle.toUpperCase(java.util.Locale.ROOT)),List.of())));
            var ids=new ArrayList<UUID>();
            for(var row:rows) {var current=sources.note(owner,row.noteId());if(current.expected().revision()==row.revision()){sourceMap.put(row.noteId(),current);ids.add(row.noteId());}}
            signals.add(ids);
        }
        var selected=new ArrayList<QueryEvidenceRepository.Evidence>();String degraded=null;KnowledgeQueryGate.QueryPermit permit=null;
        var provider=providers.getIfAvailable();
        try {
            if(provider==null||!provider.available())throw new DerivationFailure(KnowledgeWork.Failure.PROVIDER_UNAVAILABLE);
            permit=gate.query(owner);permit.requireDispatch();float[] query=provider.embedQuery(permit,request.query());
            // Same embedding configuration, separately compatible modality lineage, never an incompatible fallback.
            for(String modality:List.of("note","image","audio","video","pdf")) {
                var candidates=vectors.search(owner,modality,query,50);var ids=new ArrayList<UUID>();
                for(var candidate:candidates) {
                    var source=sources.source(owner,candidate.expected().noteId(),candidate.expected().attachmentId());
                    if(!request.lifecycles().contains(source.lifecycle())||!source.expected().equals(candidate.expected()))continue;
                    var note=sources.note(owner,source.expected().noteId());sourceMap.put(note.expected().noteId(),note);ids.add(note.expected().noteId());
                }
                signals.add(ids.stream().distinct().toList());
            }
        } catch(DerivationFailure failure){degraded=safeReason(failure);}
        var order=ReciprocalRankFusion.fuse(signals,50);
        if(permit!=null&&degraded==null) {
            var qp=permit;
            for(UUID id:order) {
                var note=sourceMap.get(id);if(!note.aiEnabled())continue;
                // Source-owned Note plus current derived media, bounded independently of retrieval rank.
                var noteEvidence=tx(()->evidence.source(note.expected(),qp.lineage("note")));
                for(var e:noteEvidence){if(selected.size()==12)break;selected.add(e);}
                for(var candidate:sourcesForNote(owner,id,request.lifecycles())) {
                    if(candidate.expected().attachmentId()==null)continue;
                    for(var e:tx(()->evidence.source(candidate.expected(),qp.lineage(candidate.modality())))){if(selected.size()==12)break;selected.add(e);}
                }
                if(selected.size()==12)break;
            }
        }
        var deterministic=order.stream().map(sourceMap::get).filter(s->sources.matches(s,false))
            .map(s->new KnowledgeQueryResult.DeterministicItem("note",s.title(),List.of(citation(s,null)),s.aiEnabled())).toList();
        Generated answer=selected.isEmpty()?empty(List.of(),degraded,degraded==null&&request.plan()==KnowledgeQueryRequest.Plan.FOCUSED):generate(owner,request.query(),"answer",selected,()->true);
        var r=answer.result();return new Generated(new KnowledgeQueryResult(deterministic,r.aiAnswer(),r.citations(),new KnowledgeQueryResult.Coverage("bounded",sourceMap.size(),false,false,false,false,false),degraded!=null?degraded:r.degraded(),r.insufficientEvidence()),answer.provenance(),answer.lineages());
    }
    private List<PrivateQuerySource.Source> sourcesForNote(UUID owner,UUID note,List<String> states) {
        // Notes owns the bounded source enumeration; no Knowledge SQL over Notes tables.
        var result=new ArrayList<PrivateQuerySource.Source>();result.add(sources.note(owner,note));
        var page=sources.page(owner,new PrivateQuerySource.Boundary(java.time.Instant.now(),states),new PrivateQuerySource.Position(note,new UUID(0,0)),12,true);
        page.sources().stream().filter(s->s.expected().noteId().equals(note)).forEach(result::add);return result;
    }
    Generated semanticBatch(UUID owner,String query,List<PrivateQuerySource.Source> batch,java.util.function.BooleanSupplier currentWork) {
        var permit=gate.query(owner);var selected=new ArrayList<QueryEvidenceRepository.Evidence>();
        for(var source:batch) {
            var rows=tx(()->evidence.source(source.expected(),permit.lineage(source.modality())));
            if(rows.isEmpty()||rows.size()>12)throw new DerivationFailure(rows.isEmpty()?KnowledgeWork.Failure.LINEAGE_OBSOLETE:KnowledgeWork.Failure.BUDGET_EXCEEDED);
            // The caller processes one source per checkpoint; all segments must fit the explicit context budget.
            if(selected.size()+rows.size()>12)throw new DerivationFailure(KnowledgeWork.Failure.BUDGET_EXCEEDED);
            selected.addAll(rows);
        }
        return generate(owner,query,"extract",selected,currentWork);
    }
    Generated generate(UUID owner,String query,String task,List<QueryEvidenceRepository.Evidence> selected,java.util.function.BooleanSupplier currentWork) {
        var provider=providers.getIfAvailable();if(provider==null||!provider.available())return empty(List.of(),"providerUnavailable",false);
        var provenance=selected.stream().map(e->sources.source(owner,e.expected().noteId(),e.expected().attachmentId())).distinct().toList();
        var handle=coordination.dispatchMany(owner,provenance.stream().map(s->s.expected().noteId()).distinct().toList());
        StructuredKnowledgePort.Output output;var payload=new ArrayList<StructuredKnowledgePort.Evidence>();var lineages=new LinkedHashMap<String,String>();
        try(handle) {
            var permit=tx(()->{
                if(!currentWork.getAsBoolean())throw new DerivationFailure(KnowledgeWork.Failure.INVALID_SOURCE);
                var qp=gate.query(owner);
                for(var e:selected){if(!evidence.current(e,qp.lineage(e.modality())))throw new DerivationFailure(KnowledgeWork.Failure.INVALID_SOURCE);lineages.put(e.modality(),qp.lineage(e.modality()).id());}
                return gate.evidence(owner,provenance,handle);
            });
            int total=0;
            for(int i=0;i<selected.size();i++){var e=selected.get(i);total+=e.segment().text().length();if(total>48000)throw new DerivationFailure(KnowledgeWork.Failure.BUDGET_EXCEEDED);payload.add(new StructuredKnowledgePort.Evidence("e"+i,e.segment().text(),e.modality()));}
            permit.requireDispatch();output=provider.generate(permit,query,task,payload);
            validate(output,payload.size());
        }catch(DerivationFailure failure){return empty(List.of(),safeReason(failure),false);}
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
    static KnowledgeQueryResult.Citation citation(PrivateQuerySource.Source s,DerivedSegment d){var e=s.expected();return new KnowledgeQueryResult.Citation(e.noteId(),e.attachmentId(),d==null?null:new KnowledgeQueryResult.Location(d.kind(),d.start(),d.end(),d.page(),d.timeStart(),d.timeEnd(),d.x(),d.y(),d.width(),d.height()));}
    static Generated empty(List<KnowledgeQueryResult.DeterministicItem> deterministic,String degraded,boolean insufficient){return new Generated(new KnowledgeQueryResult(deterministic,null,List.of(),new KnowledgeQueryResult.Coverage("bounded",0,false,false,false,false,false),degraded,insufficient),List.of(),java.util.Map.of());}
    static String safeReason(DerivationFailure failure){return switch(failure.category){case POLICY_BLOCKED->"processingPolicyRequired";case INVALID_SOURCE->"sourceChanged";case INVALID_OUTPUT->"invalidProviderOutput";case LINEAGE_OBSOLETE->"representationUnavailable";case BUDGET_EXCEEDED->"budgetExceeded";default->"providerUnavailable";};}
}
