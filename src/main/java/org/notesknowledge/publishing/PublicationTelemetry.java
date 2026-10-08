package org.notesknowledge.publishing;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Fixed, bounded operational labels; lifecycle audit provenance stays in its owner relation. */
@Component
class PublicationTelemetry {
    enum Command { CREATE,UPDATE,UNPUBLISH,REPUBLISH }
    enum Denial { OWNER_UNPUBLISH,SOURCE_RETIRED,ACCOUNT_DELETED,SUPERSEDED }
    private final MeterRegistry registry;
    PublicationTelemetry(MeterRegistry registry){this.registry=registry;}
    void command(Command command){counter(command,"committed");}
    void rejected(Command command){counter(command,"rejected");}
    private void counter(Command command,String outcome){
        try{registry.counter("notes.workspace.publication.command","operation",command.name().toLowerCase(java.util.Locale.ROOT),"outcome",outcome).increment();}
        catch(RuntimeException failure){org.slf4j.LoggerFactory.getLogger(PublicationTelemetry.class).warn("publication_telemetry_deferred");}
    }
    void denialAfterCommit(Denial reason) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
            @Override public void afterCommit(){
                try{registry.counter("notes.workspace.public.denial","reasonClass",reason.name().toLowerCase(java.util.Locale.ROOT),"outcome","denied").increment();}
                catch(RuntimeException failure){org.slf4j.LoggerFactory.getLogger(PublicationTelemetry.class).warn("public_denial_telemetry_deferred");}
            }
        });
    }
}
