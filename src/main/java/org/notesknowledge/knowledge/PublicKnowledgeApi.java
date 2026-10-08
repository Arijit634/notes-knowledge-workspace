package org.notesknowledge.knowledge;

import java.util.UUID;
import org.notesknowledge.knowledge.spi.PublicKnowledgeSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Public-only generation seam; every call joins Publishing's authoritative local transaction. */
@Service
@Transactional(propagation=Propagation.MANDATORY)
public class PublicKnowledgeApi {
    private final PublicKnowledgeRepository repository;
    PublicKnowledgeApi(PublicKnowledgeRepository repository){this.repository=repository;}
    public void advance(UUID publication,long generation,long snapshot) {
        repository.invalidate(publication);
        repository.enqueue(new PublicKnowledgeSource.Expected(publication,generation,snapshot));
    }
    public void invalidate(UUID publication){repository.invalidate(publication);}
}
