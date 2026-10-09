package org.notesknowledge.knowledge;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.notesknowledge.DatabaseUuidV7Generator;
import org.notesknowledge.identity.AccountEligibilityApi;
import org.notesknowledge.identity.IdentitySessionPrincipal;
import org.notesknowledge.knowledge.spi.PrivateQuerySource;
import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Service
class KnowledgeOperationService {
    record Metadata(int schemaVersion,PrivateQuerySource.Boundary boundary,PrivateQuerySource.Position continuation,
            long inspected,String fingerprint,int restarts,boolean truncated,boolean includeOccurrences,int nextSegment) {
        Metadata(int schemaVersion,PrivateQuerySource.Boundary boundary,PrivateQuerySource.Position continuation,
                long inspected,String fingerprint,int restarts,boolean truncated,boolean includeOccurrences) {
            this(schemaVersion,boundary,continuation,inspected,fingerprint,restarts,truncated,includeOccurrences,0);
        }
    }
    record View(String operationId,String status,Instant submittedAt,KnowledgeQueryResult result) {
        @Override public String toString(){return "KnowledgeOperationView[REDACTED]";}
    }
    private final KnowledgeOperationRepository repository;
    private final KnowledgeOperationMaterialCipher cipher;
    private final KnowledgeOperationHandleCodec handles;
    private final PrivateQuerySource sources;
    private final KnowledgeQueryEngine engine;
    private final KnowledgeQueryGate gate;
    private final ObjectMapper json;
    private final Clock clock;
    private final DatabaseUuidV7Generator ids;
    private final ObjectProvider<AccountEligibilityApi> accounts;
    private final ObjectProvider<PlatformTransactionManager> managers;
    private final io.micrometer.core.instrument.MeterRegistry metrics;
    KnowledgeOperationService(KnowledgeOperationRepository repository,KnowledgeOperationMaterialCipher cipher,KnowledgeOperationHandleCodec handles,
        PrivateQuerySource sources,KnowledgeQueryEngine engine,KnowledgeQueryGate gate,ObjectMapper json,Clock clock,DatabaseUuidV7Generator ids,
        ObjectProvider<AccountEligibilityApi> accounts,ObjectProvider<PlatformTransactionManager> managers,io.micrometer.core.instrument.MeterRegistry metrics) {
        this.repository=repository;this.cipher=cipher;this.handles=handles;this.sources=sources;this.engine=engine;this.gate=gate;this.json=json;this.clock=clock;this.ids=ids;this.accounts=accounts;this.managers=managers;this.metrics=metrics;
    }
    private <T>T tx(java.util.function.Supplier<T> work){var t=new TransactionTemplate(managers.getObject());t.setTimeout(3);return t.execute(s->work.get());}
    UUID browserOwner(HttpServletRequest browser) {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth==null||!(auth.getPrincipal() instanceof IdentitySessionPrincipal p)||auth.getAuthorities().stream().noneMatch(a->"ROLE_USER".equals(a.getAuthority())))throw ApiFailureException.of(ApiFailureException.Kind.INVALID_CREDENTIALS);
        return tx(()->{accounts.getObject().requireCurrentOwner(p.userId(),browser);return p.userId();});
    }
    View accept(UUID owner,KnowledgeQueryRequest request,HttpServletRequest browser) {
        String purpose=switch(request.plan()){case SEMANTIC_CORPUS->"semantic_corpus";case DETERMINISTIC_CORPUS->"deterministic_corpus";default->throw new IllegalArgumentException("Non-corpus request");};
        var boundary=sources.capture(owner,request.lifecycles());
        String fingerprint=sources.fingerprint(owner,boundary,"semantic_corpus".equals(purpose));
        UUID id=ids.generate();Instant created=clock.instant(),expiry=created.plusSeconds(24*3600);
        String handle=handles.encode(new KnowledgeOperationHandleCodec.Location(id,owner,purpose,expiry));
        var envelope=cipher.seal(context(id,owner,purpose,KnowledgeOperationMaterialCipher.Kind.INPUT,0),json.writeValueAsBytes(request));
        var metadata=new Metadata(1,boundary,null,0,fingerprint,0,false,request.includeOccurrences());
        tx(()->{accounts.getObject().requireCurrentOwner(owner,browser);repository.insert(id,owner,purpose,created,expiry,json.writeValueAsString(metadata),envelope);return null;});
        return new View(handle,"queued",created,null);
    }
    View poll(HttpServletRequest browser,String handle) {
        UUID owner=browserOwner(browser);var location=handles.decode(handle,owner);
        return tx(()->{
            accounts.getObject().requireCurrentOwner(owner,browser);
            var row=repository.owner(owner,location.work()).filter(r->r.purpose().equals(location.purpose())).orElseThrow(KnowledgeOperationService::missing);
            KnowledgeQueryResult result=null;
            if("completed".equals(row.state())&&row.result()!=null) {
                var stored=openResult(row);var metadata=parseMetadata(row.metadata());
                if(!exposable(row,stored,metadata)) {repository.obsolete(owner,row.id());return new View(handle,"obsolete",row.created(),null);}
                result=stored.result();
                if(!metadata.includeOccurrences())result=new KnowledgeQueryResult(result.deterministicResults().stream()
                    .map(i->new KnowledgeQueryResult.DeterministicItem(i.kind(),i.value(),i.occurrences().isEmpty()?List.of():List.of(i.occurrences().getFirst()),i.aiEnabled())).toList(),
                    result.aiAnswer(),result.citations(),result.coverage(),result.degraded(),result.insufficientEvidence());
            }
            return new View(handle,"claimed".equals(row.state())?"running":row.state(),row.created(),result);
        });
    }
    void cancel(HttpServletRequest browser,String handle){UUID owner=browserOwner(browser);var location=handles.decode(handle,owner);tx(()->{
        accounts.getObject().requireCurrentOwner(owner,browser);repository.owner(owner,location.work()).filter(r->r.purpose().equals(location.purpose())).orElseThrow(KnowledgeOperationService::missing);repository.cancel(owner,location.work());return null;});}
    boolean databaseAvailable(){return managers.getIfAvailable()!=null;}
    List<KnowledgeOperationRepository.Row> claim(int limit){return tx(()->{repository.expire();return repository.claim(limit);});}
    void execute(KnowledgeOperationRepository.Row original) {
        var row=original;
        try {
            long deadline=System.nanoTime()+java.time.Duration.ofSeconds(60).toNanos();
            for(int batch=0;batch<64;batch++) {
                var active=row;
                if(!tx(()->repository.current(active)))return;
                var request=json.readValue(cipher.open(context(row,KnowledgeOperationMaterialCipher.Kind.INPUT,0),row.input()),KnowledgeQueryRequest.class);
                if(!purpose(request).equals(row.purpose()))throw new DerivationFailure(KnowledgeWork.Failure.INVALID_OUTPUT);
                var metadata=parseMetadata(row.metadata());boolean semantic="semantic_corpus".equals(row.purpose());
                var stored=row.result()==null?empty(metadata.fingerprint()):openResult(row);
                if(batch==0&&!provenanceCurrent(row,stored)) {
                    if(metadata.restarts()>=1){tx(()->repository.terminate(active,"obsolete"));return;}
                    metadata=new Metadata(1,metadata.boundary(),null,0,sources.fingerprint(row.owner(),metadata.boundary(),semantic),metadata.restarts()+1,false,metadata.includeOccurrences());
                    stored=empty(metadata.fingerprint());
                }
                var page=sources.page(row.owner(),metadata.boundary(),metadata.continuation(),semantic?1:12,semantic);
                var items=new ArrayList<>(stored.result().deterministicResults());var claims=new ArrayList<String>();
                if(stored.result().aiAnswer()!=null)claims.addAll(stored.result().aiAnswer().claims());
                var citations=new ArrayList<>(stored.result().citations());var provenance=new ArrayList<>(stored.provenance());var lineages=new java.util.LinkedHashMap<>(stored.lineages());
                boolean conflicting=stored.result().aiAnswer()!=null&&stored.result().aiAnswer().conflicting();
                String degraded=stored.result().degraded();boolean truncated=metadata.truncated();
                int nextSegment=0;
                for(var source:page.sources()) {
                    if(semantic) {
                        var segmentPage=engine.semanticPage(row.owner(),request.query(),source,metadata.nextSegment(),()->repository.current(active));
                        var generated=segmentPage.generated();nextSegment=segmentPage.nextOrdinal();
                        if(generated.result().degraded()!=null){degraded=generated.result().degraded();truncated=true;}
                        else {
                            if(generated.result().aiAnswer()!=null){claims.addAll(generated.result().aiAnswer().claims());conflicting|=generated.result().aiAnswer().conflicting();}
                            citations.addAll(generated.result().citations());lineages.putAll(generated.lineages());
                            if(!generated.result().citations().isEmpty())provenance.add(KnowledgeQueryEngine.referenceOnly(source));
                        }
                    } else {
                        int before=items.size();
                        var scanItems=items;
                        for(var text:sources.deterministicText(source)) {
                            boolean unsupported=new UrlRecognizer().scan(text.value(),match->{
                                if(scanItems.size()>=1024)return;
                                var citation=new KnowledgeQueryResult.Citation(source.expected().noteId(),source.expected().attachmentId(),new KnowledgeQueryResult.Location(text.page()==null?"note_text":"pdf_text",match.offset(),match.offset()+match.length(),text.page(),null,null,null,null,null,null));
                                scanItems.add(new KnowledgeQueryResult.DeterministicItem("url",match.display(),List.of(citation),source.aiEnabled()));
                            });truncated|=unsupported||items.size()>=1024;
                        }
                        // Boundary fingerprint covers negative inspections; retain detailed provenance only for returned material.
                        if(items.size()>before)provenance.add(KnowledgeQueryEngine.referenceOnly(source));
                    }
                }
                boolean sourceDone=nextSegment==0;
                boolean done=sourceDone&&page.continuation()==null;
                long inspected=metadata.inspected()+(sourceDone?page.sources().size():0);
                if(claims.size()>256||citations.size()>1024){truncated=true;done=true;}
                // Never retain a claim after dropping some of its supporting citations.
                if(claims.size()>256||citations.size()>1024){claims.clear();citations.clear();provenance.clear();lineages.clear();}
                String currentFingerprint=done?sources.fingerprint(row.owner(),metadata.boundary(),semantic):metadata.fingerprint();
                boolean changed=done&&!currentFingerprint.equals(metadata.fingerprint());
                if(changed&&metadata.restarts()<1) {
                    var restart=new Metadata(1,metadata.boundary(),null,0,currentFingerprint,metadata.restarts()+1,false,metadata.includeOccurrences());
                    var envelope=sealResult(row,empty(currentFingerprint));
                    if(!tx(()->repository.checkpoint(active,json.writeValueAsString(restart),envelope)))return;
                    row=tx(()->repository.owner(active.owner(),active.id()).orElseThrow());continue;
                }
                if(changed){items.clear();claims.clear();citations.clear();provenance.clear();}
                if(request.deduplicate())items=deduplicate(items);
                var result=new KnowledgeQueryResult(items,claims.isEmpty()?null:new KnowledgeQueryResult.Answer(claims.stream().distinct().toList(),conflicting),citations.stream().distinct().toList(),
                    new KnowledgeQueryResult.Coverage(semantic?"acceptedAiSources":"acceptedNoteAndPdfText",inspected,done&&!truncated&&!changed,changed,truncated,changed,semantic),degraded,semantic&&claims.isEmpty()&&degraded==null);
                var checkpoint=new KnowledgeQueryResult.Stored(result,provenance.stream().distinct().toList(),currentFingerprint,lineages);
                if(json.writeValueAsBytes(checkpoint).length>KnowledgeOperationMaterialCipher.RESULT_LIMIT){
                    // A bound is not permission to split claims from their evidence or invent another store.
                    truncated=true;done=true;
                    currentFingerprint=sources.fingerprint(row.owner(),metadata.boundary(),semantic);
                    checkpoint=new KnowledgeQueryResult.Stored(new KnowledgeQueryResult(List.of(),null,List.of(),
                        new KnowledgeQueryResult.Coverage(semantic?"acceptedAiSources":"acceptedNoteAndPdfText",inspected,false,changed,true,changed,semantic),
                        "budgetExceeded",false),List.of(),currentFingerprint,Map.of());
                }
                var next=new Metadata(1,metadata.boundary(),sourceDone?page.continuation():metadata.continuation(),inspected,currentFingerprint,metadata.restarts(),truncated,metadata.includeOccurrences(),nextSegment);
                var finalCheckpoint=checkpoint;var finalFingerprint=currentFingerprint;
                var envelope=sealResult(row,checkpoint);boolean finish=done;
                boolean success=tx(()->{
                    if(!repository.current(active)||!accounts.getObject().isEligible(active.owner())
                        ||finish&&!provenanceCurrent(active,finalCheckpoint))return repository.terminate(active,"obsolete");
                    if(finish&&!sources.fingerprint(active.owner(),next.boundary(),semantic).equals(finalFingerprint))return repository.terminate(active,"obsolete");
                    return finish?repository.complete(active,json.writeValueAsString(next),envelope):repository.checkpoint(active,json.writeValueAsString(next),envelope);
                });
                if(!success||done)return;
                row=tx(()->repository.owner(active.owner(),active.id()).orElseThrow());
                if(System.nanoTime()>deadline)break;
            }
            var active=row;tx(()->repository.yield(active));
        } catch(DerivationFailure|ApiFailureException invalid) {
            var active=row;tx(()->repository.terminate(active,invalid instanceof DerivationFailure d&&d.category==KnowledgeWork.Failure.INVALID_SOURCE?"obsolete":"failed"));
            metrics.counter("notes.workspace.knowledge.operation","outcome","unavailable").increment();
        } catch(tools.jackson.core.JacksonException invalid) {
            var active=row;tx(()->repository.terminate(active,"failed"));metrics.counter("notes.workspace.knowledge.operation","outcome","invalidMaterial").increment();
        }
    }
    private boolean exposable(KnowledgeOperationRepository.Row row,KnowledgeQueryResult.Stored stored,Metadata metadata){
        return provenanceCurrent(row,stored)&&sources.fingerprint(row.owner(),metadata.boundary(),"semantic_corpus".equals(row.purpose())).equals(stored.fingerprint());
    }
    private boolean provenanceCurrent(KnowledgeOperationRepository.Row row,KnowledgeQueryResult.Stored stored) {
        return tx(()->provenanceCurrentInTransaction(row,stored));
    }
    private boolean provenanceCurrentInTransaction(KnowledgeOperationRepository.Row row,KnowledgeQueryResult.Stored stored) {
        if(!accounts.getObject().isEligible(row.owner()))return false;
        boolean semantic="semantic_corpus".equals(row.purpose());
        if(stored.provenance().stream().anyMatch(s->!row.owner().equals(s.expected().owner())))return false;
        for(int i=0;i<stored.provenance().size();i+=64)
            if(!sources.validate(stored.provenance().subList(i,Math.min(i+64,stored.provenance().size())),semantic))return false;
        if(semantic&&!stored.lineages().isEmpty())try {var q=gate.query(row.owner());return stored.lineages().entrySet().stream().allMatch(e->q.lineage(e.getKey()).id().equals(e.getValue()));}catch(DerivationFailure blocked){return false;}
        return true;
    }
    private KnowledgeQueryResult.Stored openResult(KnowledgeOperationRepository.Row row){return json.readValue(cipher.open(context(row,KnowledgeOperationMaterialCipher.Kind.RESULT,row.version()),row.result()),KnowledgeQueryResult.Stored.class);}
    private KnowledgeOperationMaterialCipher.Envelope sealResult(KnowledgeOperationRepository.Row row,KnowledgeQueryResult.Stored result){return cipher.seal(context(row,KnowledgeOperationMaterialCipher.Kind.RESULT,row.version()+1),json.writeValueAsBytes(result));}
    private static KnowledgeQueryResult.Stored empty(String fingerprint){return new KnowledgeQueryResult.Stored(KnowledgeQueryEngine.empty(List.of(),null,false).result(),List.of(),fingerprint,Map.of());}
    private static ArrayList<KnowledgeQueryResult.DeterministicItem> deduplicate(List<KnowledgeQueryResult.DeterministicItem> items) {
        var values=new java.util.LinkedHashMap<String,KnowledgeQueryResult.DeterministicItem>();
        for(var item:items){final String[] key={item.value()};new UrlRecognizer().scan(item.value(),m->key[0]=m.comparisonKey());var previous=values.get(key[0]);
            if(previous==null)values.put(key[0],item);else{var occurrences=new ArrayList<>(previous.occurrences());occurrences.addAll(item.occurrences());values.put(key[0],new KnowledgeQueryResult.DeterministicItem(item.kind(),previous.value(),occurrences,previous.aiEnabled()&&item.aiEnabled()));}}
        return new ArrayList<>(values.values());
    }
    private static String purpose(KnowledgeQueryRequest request){return request.plan()==KnowledgeQueryRequest.Plan.DETERMINISTIC_CORPUS?"deterministic_corpus":"semantic_corpus";}
    private Metadata parseMetadata(String text){return json.readValue(text,Metadata.class);}
    private static KnowledgeOperationMaterialCipher.Context context(KnowledgeOperationRepository.Row r,KnowledgeOperationMaterialCipher.Kind kind,long version){return context(r.id(),r.owner(),r.purpose(),kind,version);}
    private static KnowledgeOperationMaterialCipher.Context context(UUID id,UUID owner,String purpose,KnowledgeOperationMaterialCipher.Kind kind,long version){return new KnowledgeOperationMaterialCipher.Context(id,owner,purpose,kind,version);}
    private static ApiFailureException missing(){return ApiFailureException.of(ApiFailureException.Kind.RESOURCE_NOT_FOUND);}
}
