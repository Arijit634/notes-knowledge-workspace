package org.notesknowledge.knowledge;

import java.util.List;
import java.util.Optional;
import org.notesknowledge.LeaseOwner;
import org.notesknowledge.knowledge.spi.PublicKnowledgeSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PublicKnowledgeTransactions {
    private final PublicKnowledgeRepository repository;
    private final PublicKnowledgeSource sources;
    PublicKnowledgeTransactions(PublicKnowledgeRepository repository,PublicKnowledgeSource sources){this.repository=repository;this.sources=sources;}
    @Transactional(timeout=3)
    List<PublicKnowledgeRepository.Claim> claim(LeaseOwner owner,int limit,boolean reclaim){return repository.claim(owner,limit,reclaim);}
    @Transactional(readOnly=true,timeout=3)
    Optional<PublicKnowledgeSource.Source> capture(PublicKnowledgeRepository.Claim c){return repository.current(c)?sources.resolve(c.expected(),false):Optional.empty();}
    @Transactional(timeout=3)
    boolean complete(PublicKnowledgeRepository.Claim c,List<DerivedSegment> segments) {
        if(!sources.current(c.expected(),true)){repository.transition(c,"obsolete",false);return false;}
        if(!repository.current(c))return false;
        repository.activate(c,segments);return true;
    }
    @Transactional(timeout=3)
    void obsolete(PublicKnowledgeRepository.Claim c){repository.transition(c,"obsolete",false);}
    @Transactional(timeout=3)
    void retry(PublicKnowledgeRepository.Claim c){repository.transition(c,"retry_wait",true);}
    @Transactional(timeout=3)
    java.util.UUID reconcile(java.util.UUID after) {
        var page=sources.inventory(after,25);
        for(var e:page.sources())if(!repository.readyOrTerminalFailure(e))repository.enqueue(e);
        return page.next();
    }
}
