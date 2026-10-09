package org.notesknowledge.publishing;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Public-only moderation port. Responsible subject is trusted provenance, never browser authority. */
@Service
public class PublicationModerationApi {
    public record Evidence(UUID publicationId,long generation,String title,String markdown) {
        @Override public String toString(){return "PublicModerationEvidence[REDACTED]";}
    }
    private final PublicationRepository repository;
    private final PublicationTransactions transactions;
    PublicationModerationApi(PublicationRepository repository,PublicationTransactions transactions){this.repository=repository;this.transactions=transactions;}
    @Transactional(readOnly=true)
    public UUID responsibleAccount(UUID id) {
        return repository.responsible(id).orElseThrow(PublicationTransactions::missing);
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public Evidence current(UUID id,boolean lock) {return transactions.moderationEvidence(id,lock);}
    @Transactional(propagation=Propagation.MANDATORY)
    public void remove(UUID id,long generation,UUID actor) {transactions.moderationRemove(id,generation,actor);}
    @Transactional(propagation=Propagation.MANDATORY)
    public boolean isRemovedResponsibility(UUID subject,UUID publication){return repository.owner(subject,publication,true)
        .filter(p->p.state().equals("removed")).isPresent();}
}
