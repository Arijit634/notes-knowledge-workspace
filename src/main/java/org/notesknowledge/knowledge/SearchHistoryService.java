package org.notesknowledge.knowledge;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.notesknowledge.DatabaseUuidV7Generator;
import org.notesknowledge.identity.AccountEligibilityApi;
import org.notesknowledge.knowledge.spi.PrivateQuerySource;
import org.notesknowledge.websupport.ApiFailureException;
import org.notesknowledge.websupport.CursorPage;
import org.notesknowledge.websupport.OpaqueCursorCodec;
import org.notesknowledge.websupport.ScopedCursorPage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Private product history; never a provider cache, authorization grant or operational queue. */
@Service
final class SearchHistoryService {
    record Question(String query,PrivateQuerySource.SavedDuring savedDuring){@Override public String toString(){return "HistoryQuestion[REDACTED]";}}
    record Snapshot(int schemaVersion,boolean allAi,PrivateQuerySource.Boundary boundary,KnowledgeQueryResult.Stored stored) {
        @Override public String toString(){return "SearchHistorySnapshot[REDACTED]";}
    }
    record View(UUID id,String query,String status,Instant submittedAt,Instant expiresAt,
            KnowledgeOperationService.View operation,KnowledgeQueryResult result,PrivateQuerySource.SavedDuring savedDuring) {
        @Override public String toString(){return "SearchHistoryView[REDACTED]";}
    }
    private final SearchHistoryRepository repository;
    private final KnowledgeOperationRepository work;
    private final KnowledgeOperationMaterialCipher cipher;
    private final KnowledgeOperationHandleCodec handles;
    private final ObjectProvider<AccountEligibilityApi> accounts;
    private final ObjectProvider<PlatformTransactionManager> managers;
    private final ObjectProvider<OpaqueCursorCodec> cursors;
    private final PrivateQuerySource sources;
    private final KnowledgeQueryGate gate;
    private final DatabaseUuidV7Generator ids;
    private final Clock clock;
    private final ObjectMapper json;
    SearchHistoryService(SearchHistoryRepository repository,KnowledgeOperationRepository work,KnowledgeOperationMaterialCipher cipher,
            KnowledgeOperationHandleCodec handles,ObjectProvider<AccountEligibilityApi> accounts,ObjectProvider<PlatformTransactionManager> managers,
            ObjectProvider<OpaqueCursorCodec> cursors,PrivateQuerySource sources,KnowledgeQueryGate gate,DatabaseUuidV7Generator ids,Clock clock,ObjectMapper json){
        this.repository=repository;this.work=work;this.cipher=cipher;this.handles=handles;this.accounts=accounts;this.managers=managers;
        this.cursors=cursors;this.sources=sources;this.gate=gate;this.ids=ids;this.clock=clock;this.json=json;
    }
    private <T>T tx(java.util.function.Supplier<T> task){var t=new TransactionTemplate(managers.getObject());t.setTimeout(3);return t.execute(s->task.get());}
    private void eligible(UUID owner){if(!accounts.getObject().isEligible(owner))throw ApiFailureException.of(ApiFailureException.Kind.INVALID_CREDENTIALS);}
    void lock(UUID owner){repository.lock(owner);}
    UUID begin(UUID owner,KnowledgeQueryRequest request,UUID operation){return tx(()->{
        lock(owner);eligible(owner);repository.prune(owner);
        if(repository.count(owner)>=50)throw ApiFailureException.rateLimited(60);
        var id=ids.generate();var created=clock.instant().truncatedTo(ChronoUnit.MILLIS);
        var question=cipher.seal(context(id,owner,KnowledgeOperationMaterialCipher.Kind.INPUT),("\u0000NKW_SEARCH_SCOPE_V1\n"+json.writeValueAsString(new Question(request.query(),request.savedDuring()))).getBytes(StandardCharsets.UTF_8));
        repository.insert(id,owner,operation,created,created.plus(30,ChronoUnit.DAYS),question);return id;
    });}
    boolean complete(UUID owner,UUID id,Snapshot snapshot){return tx(()->{
        lock(owner);eligible(owner);
        if(repository.owner(owner,id).filter(r->r.state().equals("pending")).isEmpty())return false;
        if(!exposable(owner,snapshot)){repository.unavailable(owner,id,"obsolete");return false;}
        return repository.complete(owner,id,cipher.seal(context(id,owner,KnowledgeOperationMaterialCipher.Kind.RESULT),json.writeValueAsBytes(snapshot)));
    });}
    void completeWork(UUID owner,UUID operation,boolean semantic,PrivateQuerySource.Boundary boundary,KnowledgeQueryResult.Stored result,boolean includeOccurrences){
        // Caller holds the history-owner lock before the work completion write.
        var projected=new KnowledgeQueryResult.Stored(result.result().projectOccurrences(includeOccurrences),result.provenance(),result.fingerprint(),result.lineages());
        repository.work(owner,operation).ifPresent(row->complete(owner,row.id(),new Snapshot(1,semantic,boundary,projected)));
    }
    UUID idForWork(UUID owner,UUID operation){return repository.work(owner,operation).map(SearchHistoryRepository.Row::id).orElse(null);}
    void fail(UUID owner,UUID id){tx(()->{lock(owner);repository.owner(owner,id).filter(r->r.state().equals("pending")).ifPresent(r->repository.unavailable(owner,id,"failed"));return null;});}
    CursorPage<View> list(UUID owner,String cursor,int limit){if(limit<1||limit>20)throw ApiFailureException.of(ApiFailureException.Kind.MALFORMED_REQUEST);return tx(()->{
        eligible(owner);var pager=new ScopedCursorPage(cursors.getObject(),"KNOWLEDGE_HISTORY",owner.toString(),"all","CREATED_DESC");var before=pager.position(cursor);
        var rows=repository.page(owner,before.before(),before.id(),limit+1);boolean more=rows.size()>limit;var visible=rows.stream().limit(limit).toList();
        var views=visible.stream().map(r->new View(r.id(),question(r),status(r),r.created(),r.expires(),null,null,questionScope(r))).toList();
        var last=visible.isEmpty()?null:visible.getLast();return new CursorPage<>(views,more?pager.next(last.created(),last.id()):null);
    });}
    View detail(UUID owner,UUID id){return tx(()->{
        eligible(owner);var row=repository.owner(owner,id).orElseThrow(SearchHistoryService::missing);KnowledgeQueryResult result=null;
        if(row.state().equals("completed")){
            var snapshot=json.readValue(cipher.open(context(id,owner,KnowledgeOperationMaterialCipher.Kind.RESULT),row.result()),Snapshot.class);
            if(!exposable(owner,snapshot)){repository.unavailable(owner,id,"obsolete");return new View(id,question(row),"obsolete",row.created(),row.expires(),null,null,questionScope(row));}
            result=snapshot.stored().result();
            // V020 histories written before projection was fixed may hold every
            // occurrence. The restrictive work FK retains the original request's
            // presentation metadata even after operational material is cleared.
            if(row.work()!=null){
                var original=work.historyMetadata(owner,row.work()).orElseThrow(SearchHistoryService::missing);
                var setting=json.readTree(original).get("includeOccurrences");
                if(setting==null||!setting.isBoolean())throw ApiFailureException.of(ApiFailureException.Kind.SERVICE_UNAVAILABLE);
                result=result.projectOccurrences(setting.asBoolean());
            }
        }
        KnowledgeOperationService.View operation=null;String status=status(row);
        if(row.work()!=null&&row.state().equals("pending")){
            var current=work.owner(owner,row.work());
            if(current.isPresent()){
                var w=current.get();var m=json.readTree(w.metadata());
                operation=new KnowledgeOperationService.View(handles.encode(new KnowledgeOperationHandleCodec.Location(w.id(),owner,w.purpose(),w.expires())),status,w.created(),null,m.path("inspected").asLong(),m.path("totalSources").isNumber()?m.path("totalSources").asLong():null,w.expires(),id);
            }else status="obsolete";
        }
        return new View(id,question(row),status,row.created(),row.expires(),operation,result,questionScope(row));
    });}
    void remove(UUID owner,UUID id){tx(()->{lock(owner);eligible(owner);var row=repository.owner(owner,id).orElseThrow(SearchHistoryService::missing);
        if(row.work()!=null)work.forget(owner,row.work());repository.remove(owner,id);return null;});}
    void clear(UUID owner){tx(()->{lock(owner);eligible(owner);erase(owner);return null;});}
    void erase(UUID owner){lock(owner);repository.workIds(owner).forEach(id->work.forget(owner,id));repository.clear(owner);}
    boolean databaseAvailable(){return managers.getIfAvailable()!=null;}
    void sweep(){tx(()->{repository.sweep();return null;});}
    private Question historyQuestion(SearchHistoryRepository.Row row){String input=new String(cipher.open(context(row.id(),row.owner(),KnowledgeOperationMaterialCipher.Kind.INPUT),row.question()),StandardCharsets.UTF_8);return input.startsWith("\u0000NKW_SEARCH_SCOPE_V1\n")?json.readValue(input.substring(21),Question.class):new Question(input,null);}
    private String question(SearchHistoryRepository.Row row){return historyQuestion(row).query();}
    private PrivateQuerySource.SavedDuring questionScope(SearchHistoryRepository.Row row){return historyQuestion(row).savedDuring();}
    private String status(SearchHistoryRepository.Row row){return switch(row.visibleState()){
        case "claimed"->"running";case "pending"->row.created().plusSeconds(120).isBefore(clock.instant())?"failed":"running";default->row.visibleState();};}
    private boolean exposable(UUID owner,Snapshot snapshot){
        if(snapshot==null||snapshot.schemaVersion()!=1||snapshot.stored()==null)return false;
        var stored=snapshot.stored();if(stored.provenance().stream().anyMatch(s->!owner.equals(s.expected().owner())))return false;
        var aiSources=new java.util.ArrayList<PrivateQuerySource.Source>();var deterministicSources=new java.util.ArrayList<PrivateQuerySource.Source>();
        for(var source:stored.provenance()){
            boolean ai=snapshot.allAi()||stored.result().aiAnswer()!=null&&stored.result().citations().stream().anyMatch(c->c.noteId().equals(source.expected().noteId())&&java.util.Objects.equals(c.attachmentId(),source.expected().attachmentId()));
            (ai?aiSources:deterministicSources).add(source);
        }
        for(int i=0;i<aiSources.size();i+=64)if(!sources.validate(aiSources.subList(i,Math.min(i+64,aiSources.size())),true))return false;
        for(int i=0;i<deterministicSources.size();i+=64)if(!sources.validate(deterministicSources.subList(i,Math.min(i+64,deterministicSources.size())),false))return false;
        if(!stored.lineages().isEmpty())try{var current=gate.query(owner);if(stored.lineages().entrySet().stream().anyMatch(e->!current.lineage(e.getKey()).id().equals(e.getValue())))return false;}catch(DerivationFailure unavailable){return false;}
        return snapshot.boundary()==null||sources.fingerprint(owner,snapshot.boundary(),snapshot.allAi()).equals(stored.fingerprint());
    }
    private static KnowledgeOperationMaterialCipher.Context context(UUID id,UUID owner,KnowledgeOperationMaterialCipher.Kind kind){return new KnowledgeOperationMaterialCipher.Context(id,owner,"search_history",kind,kind==KnowledgeOperationMaterialCipher.Kind.INPUT?0:1);}
    private static ApiFailureException missing(){return ApiFailureException.of(ApiFailureException.Kind.RESOURCE_NOT_FOUND);}
}
