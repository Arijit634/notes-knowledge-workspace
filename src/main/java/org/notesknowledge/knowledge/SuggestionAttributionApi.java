package org.notesknowledge.knowledge;

import java.util.List;
import java.util.UUID;
import org.notesknowledge.knowledge.spi.PrivateQuerySource;
import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Notes owns the explicit tag command; this API records attribution in its same local transaction. */
@Service
public class SuggestionAttributionApi {
    private final ObjectProvider<JdbcClient> clients;
    private final PrivateQuerySource sources;
    private final ObjectMapper json;
    public SuggestionAttributionApi(ObjectProvider<JdbcClient> clients,PrivateQuerySource sources,ObjectMapper json){this.clients=clients;this.sources=sources;this.json=json;}
    @Transactional(propagation=Propagation.MANDATORY)
    public void accept(UUID owner,UUID note,long revision,UUID suggestion,List<String> tags) {
        var current=sources.note(owner,note);
        if(current.expected().revision()!=revision||!current.aiEnabled())throw conflict();
        var values=clients.getObject().sql("select proposal_values::text from knowledge.organization_suggestion where suggestion_id=:id and owner_user_id=:owner and source_note_id=:note and source_revision=:revision and processing_generation=:generation and state='pending' for update")
            .param("id",suggestion).param("owner",owner).param("note",note).param("revision",revision).param("generation",current.expected().aiGeneration()).query(String.class).optional().orElseThrow(SuggestionAttributionApi::conflict);
        List<String> proposed=List.of(json.readValue(values,String[].class));
        if(tags.stream().noneMatch(t->proposed.stream().anyMatch(p->p.equalsIgnoreCase(t))))throw conflict();
        clients.getObject().sql("update knowledge.organization_suggestion set state='accepted',resolved_at=clock_timestamp() where suggestion_id=:id and state='pending'").param("id",suggestion).update();
    }
    private static ApiFailureException conflict(){return ApiFailureException.of(ApiFailureException.Kind.INVALID_LIFECYCLE_TRANSITION);}
}
