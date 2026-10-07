package org.notesknowledge.knowledge;

import java.util.List;
import java.util.UUID;
import org.notesknowledge.knowledge.spi.PrivateQuerySource;
import org.notesknowledge.websupport.ApiFailureException;
import org.notesknowledge.websupport.IfMatchPrecondition;
import org.notesknowledge.websupport.StrongCoreEtagCodec;
import org.notesknowledge.websupport.NoteCoreVersion;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Service
class NoteKnowledgeService {
    record Related(UUID noteId,String title) { }
    record Proposal(UUID suggestionId,List<String> tags,String explanation) { }
    private final PrivateQuerySource sources;
    private final KnowledgeQueryGate gate;
    private final QueryEvidenceRepository evidence;
    private final ExactPrivateVectorSearch vectors;
    private final KnowledgeQueryEngine engine;
    private final StrongCoreEtagCodec etags;
    private final IfMatchPrecondition preconditions;
    private final ObjectProvider<PlatformTransactionManager> managers;
    private final ObjectProvider<JdbcClient> clients;
    private final ObjectMapper json;
    NoteKnowledgeService(PrivateQuerySource sources,KnowledgeQueryGate gate,QueryEvidenceRepository evidence,ExactPrivateVectorSearch vectors,KnowledgeQueryEngine engine,
        StrongCoreEtagCodec etags,IfMatchPrecondition preconditions,ObjectProvider<PlatformTransactionManager> managers,ObjectProvider<JdbcClient> clients,ObjectMapper json) {
        this.sources=sources;this.gate=gate;this.evidence=evidence;this.vectors=vectors;this.engine=engine;this.etags=etags;this.preconditions=preconditions;this.managers=managers;this.clients=clients;this.json=json;
    }
    private <T>T tx(java.util.function.Supplier<T> work){var t=new TransactionTemplate(managers.getObject());t.setTimeout(3);return t.execute(s->work.get());}
    private PrivateQuerySource.Source source(UUID owner,UUID note,String ifMatch){var s=sources.note(owner,note);preconditions.requireCurrent(ifMatch,etags.encode(new NoteCoreVersion(note,s.expected().revision())));
        if(!s.aiEnabled())throw ApiFailureException.of(ApiFailureException.Kind.INVALID_LIFECYCLE_TRANSITION);return s;}
    List<Related> related(UUID owner,UUID note,String ifMatch) {
        var source=source(owner,note,ifMatch);var permit=gate.query(owner);
        var roots=tx(()->evidence.source(source.expected(),permit.lineage("note")));if(roots.isEmpty())throw ApiFailureException.of(ApiFailureException.Kind.INVALID_LIFECYCLE_TRANSITION);
        float[] vector=tx(()->evidence.vector(roots.getFirst()));
        var candidates=vectors.search(owner,"note",vector,50);
        var selected=new java.util.LinkedHashMap<UUID,PrivateQuerySource.Source>();
        for(var c:candidates){if(c.expected().noteId().equals(note)||selected.size()>=12)continue;var s=sources.note(owner,c.expected().noteId());
            if(s.expected().equals(c.expected())&&s.aiEnabled())selected.putIfAbsent(s.expected().noteId(),s);}
        return tx(()->{
            var all=new java.util.ArrayList<>(selected.values());all.add(source);
            if(!sources.validate(all,true))throw ApiFailureException.of(ApiFailureException.Kind.STALE_WRITE);
            var fresh=gate.query(owner);if(!fresh.lineage("note").id().equals(permit.lineage("note").id()))throw ApiFailureException.of(ApiFailureException.Kind.PROCESSING_POLICY_CHANGED);
            if(roots.stream().anyMatch(e->!evidence.current(e,fresh.lineage("note")))||selected.values().stream().anyMatch(s->evidence.source(s.expected(),fresh.lineage("note")).isEmpty()))throw ApiFailureException.of(ApiFailureException.Kind.STALE_WRITE);
            return selected.values().stream().map(s->new Related(s.expected().noteId(),s.title())).toList();
        });
    }
    Proposal suggest(UUID owner,UUID note,String ifMatch) {
        var source=source(owner,note,ifMatch);var permit=gate.query(owner);
        var rows=tx(()->evidence.source(source.expected(),permit.lineage("note")));
        if(rows.isEmpty()||rows.size()>12)throw ApiFailureException.of(ApiFailureException.Kind.INVALID_LIFECYCLE_TRANSITION);
        var generated=engine.generate(owner,"Suggest concise tags for this note. Each claim must be one tag, not an instruction.","tags",rows,()->sources.matches(source,true));
        if(generated.result().degraded()!=null||generated.result().aiAnswer()==null)throw ApiFailureException.of(ApiFailureException.Kind.SERVICE_UNAVAILABLE);
        List<String> tags=generated.result().aiAnswer().claims().stream().map(String::strip).distinct().toList();
        if(tags.isEmpty()||tags.size()>20||tags.stream().anyMatch(t->t.isBlank()||t.length()>64||t.codePoints().anyMatch(Character::isISOControl)||t.contains("<")||t.contains(">")))throw ApiFailureException.of(ApiFailureException.Kind.SERVICE_UNAVAILABLE);
        UUID id=tx(()->{
            if(!sources.matches(source,true))throw ApiFailureException.of(ApiFailureException.Kind.STALE_WRITE);
            var fresh=gate.query(owner);if(!fresh.lineage("note").id().equals(permit.lineage("note").id()))throw ApiFailureException.of(ApiFailureException.Kind.PROCESSING_POLICY_CHANGED);
            return clients.getObject().sql("insert into knowledge.organization_suggestion(owner_user_id,source_note_id,source_revision,processing_generation,proposal_kind,proposal_values) values(:owner,:note,:revision,:generation,'tags',:tags::jsonb) returning suggestion_id")
                .param("owner",owner).param("note",note).param("revision",source.expected().revision()).param("generation",source.expected().aiGeneration()).param("tags",json.writeValueAsString(tags)).query(UUID.class).single();
        });return new Proposal(id,tags,"Proposal only; select tags and explicitly update the note.");
    }
}
