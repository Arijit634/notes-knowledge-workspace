package org.notesknowledge.knowledge;

import org.notesknowledge.LeaseOwner;
import org.springframework.stereotype.Service;

/** Bounded deterministic processing outside transactions. No provider/media/private-source fallback. */
@Service
class PublicKnowledgeWorker {
    private final PublicKnowledgeTransactions transactions;
    private final io.micrometer.core.instrument.MeterRegistry meters;
    private java.util.UUID after;
    PublicKnowledgeWorker(PublicKnowledgeTransactions transactions,io.micrometer.core.instrument.MeterRegistry meters){this.transactions=transactions;this.meters=meters;}
    void poll() {
        after=transactions.reconcile(after);
        var owner=new LeaseOwner("public-knowledge-local");
        var claims=transactions.claim(owner,2,true);
        if(claims.isEmpty())claims=transactions.claim(owner,2,false);
        for(var claim:claims) {
            try {
                var source=transactions.capture(claim);
                if(source.isEmpty()){transactions.obsolete(claim);continue;}
                var segments=new MarkdownChunker().chunk(source.get().markdown());
                boolean completed=transactions.complete(claim,segments);
                metric(completed?"completed":"obsolete");
            } catch(DerivationFailure bounded) {
                transactions.obsolete(claim);metric("excluded");
            } catch(org.springframework.dao.DataAccessException|org.springframework.transaction.TransactionException|org.notesknowledge.websupport.ApiFailureException unavailable) {
                transactions.retry(claim);metric("retry");
            }
        }
    }
    void metric(String outcome) {
        try {meters.counter("knowledge.public.work","outcome",outcome).increment();}
        catch(RuntimeException unavailable){org.slf4j.LoggerFactory.getLogger(PublicKnowledgeWorker.class).warn("public_work_telemetry_deferred");}
    }
}
