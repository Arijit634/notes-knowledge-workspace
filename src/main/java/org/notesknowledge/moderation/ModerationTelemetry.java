package org.notesknowledge.moderation;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.*;

@Component
class ModerationTelemetry {
    private final MeterRegistry meters;
    ModerationTelemetry(MeterRegistry meters){this.meters=meters;}
    void committed(String operation) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
            @Override public void afterCommit(){
                try{meters.counter("notes.workspace.moderation.command","operation",operation,"outcome","committed").increment();}
                catch(RuntimeException unavailable){org.slf4j.LoggerFactory.getLogger(ModerationTelemetry.class).warn("moderation_telemetry_deferred");}
            }
        });
    }
}
