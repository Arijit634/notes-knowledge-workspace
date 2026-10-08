package org.notesknowledge.publishing;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Narrow consequences consumed only by the owner-module SPI adapters. */
@Service
@Transactional(propagation=Propagation.MANDATORY)
public class PublicDenialApi {
    private final PublicationTransactions transactions;
    PublicDenialApi(PublicationTransactions transactions){this.transactions=transactions;}
    public boolean hasActiveSource(UUID owner,UUID note){return transactions.hasActiveSource(owner,note);}
    public void retireSource(UUID owner,UUID note){transactions.retireSource(owner,note);}
    public void retireAccount(UUID owner){transactions.retireAccount(owner);}
}
