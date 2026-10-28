package org.notesknowledge.knowledge;

import java.util.UUID;
import org.notesknowledge.identity.spi.AccountDeletionKnowledgeConsequence;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class AccountDeletionHistoryAdapter implements AccountDeletionKnowledgeConsequence {
    private final SearchHistoryService history;
    AccountDeletionHistoryAdapter(SearchHistoryService history) { this.history=history; }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public void makeIneligible(UUID userId) { history.erase(userId); }
}
