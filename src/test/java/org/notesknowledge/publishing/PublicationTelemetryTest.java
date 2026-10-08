package org.notesknowledge.publishing;

import static org.assertj.core.api.Assertions.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.notesknowledge.PublicStorageTelemetry;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Tag("FAST") @Tag("SECURITY")
class PublicationTelemetryTest {
    @Test void labelsAreFiniteAndDenialIsNotRecordedBeforeCommit(){
        var registry=new SimpleMeterRegistry();try{
            var telemetry=new PublicationTelemetry(registry);telemetry.command(PublicationTelemetry.Command.CREATE);
            telemetry.rejected(PublicationTelemetry.Command.UPDATE);
            TransactionSynchronizationManager.initSynchronization();
            try{telemetry.denialAfterCommit(PublicationTelemetry.Denial.SOURCE_RETIRED);
                assertThat(registry.find("notes.workspace.public.denial").counter()).isNull();
                TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
            }finally{TransactionSynchronizationManager.clearSynchronization();}
            assertThat(registry.get("notes.workspace.public.denial").tag("reasonClass","source_retired").counter().count()).isEqualTo(1);
            var storage=new PublicStorageTelemetry(registry);
            try(var sample=storage.start(PublicStorageTelemetry.ObjectClass.PUBLIC_AVATAR,PublicStorageTelemetry.Operation.COPY)){sample.success();}
            try(var sample=storage.start(PublicStorageTelemetry.ObjectClass.PUBLIC_MEDIA,PublicStorageTelemetry.Operation.READ)){ }
            assertThat(registry.get("notes.workspace.storage.operation").tag("outcome","unavailable").timer().count()).isEqualTo(1);
            assertThat(registry.getMeters()).allSatisfy(m->assertThat(m.getId().getTags()).allSatisfy(t->{
                assertThat(t.getKey()).isIn("operation","outcome","reasonClass","objectClass");
                assertThat(t.getValue()).isIn("create","update","committed","rejected","source_retired","denied","public_avatar","public_media","copy","read","success","unavailable");
            }));
        }finally{registry.close();}
    }
    @Test void rollbackNeverEmitsSuccessfulDenial(){
        var registry=new SimpleMeterRegistry();try{
            TransactionSynchronizationManager.initSynchronization();
            try{new PublicationTelemetry(registry).denialAfterCommit(PublicationTelemetry.Denial.ACCOUNT_DELETED);
                TransactionSynchronizationManager.getSynchronizations().forEach(s->s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
            }finally{TransactionSynchronizationManager.clearSynchronization();}
            assertThat(registry.getMeters()).isEmpty();
        }finally{registry.close();}
    }
}
