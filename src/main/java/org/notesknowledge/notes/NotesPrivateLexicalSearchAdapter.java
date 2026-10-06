package org.notesknowledge.notes;

import java.util.List;
import java.util.Map;
import org.notesknowledge.knowledge.spi.PrivateLexicalSearch;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class NotesPrivateLexicalSearchAdapter implements PrivateLexicalSearch {
    private final NotesSearchRepository repository;
    private final NotesPrivateKnowledgeSourceAdapter source;
    NotesPrivateLexicalSearchAdapter(NotesSearchRepository repository,NotesPrivateKnowledgeSourceAdapter source) { this.repository=repository;this.source=source; }
    @Transactional(propagation=Propagation.MANDATORY)
    public List<Candidate> search(Query query) {
        var owner=source.owner();
        var request=PrivateSearchRequest.decode(Map.of("query",query.text(),"lifecycle",query.lifecycle().name().toLowerCase(java.util.Locale.ROOT),"tags",query.tags()));
        return repository.search(owner,request,null,null,50).stream().map(row->new Candidate(
                row.result().id(),row.revision(),query.lifecycle(),row.rank(),row.result().matchLabels())).toList();
    }
}
