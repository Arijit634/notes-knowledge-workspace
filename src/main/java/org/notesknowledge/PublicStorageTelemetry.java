package org.notesknowledge;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/** Technical timing only: identifiers, names, bytes and storage references are never accepted. */
@Component
public class PublicStorageTelemetry {
    public enum ObjectClass { PUBLIC_AVATAR,PUBLIC_MEDIA }
    public enum Operation { COPY,READ,DELETE,INVENTORY }
    private final MeterRegistry registry;
    public PublicStorageTelemetry(MeterRegistry registry){this.registry=registry;}
    public Sample start(ObjectClass kind,Operation operation){return new Sample(kind,operation);}
    public final class Sample implements AutoCloseable {
        private final ObjectClass kind;private final Operation operation;
        private final long started=System.nanoTime();private boolean success,closed;
        private Sample(ObjectClass kind,Operation operation){this.kind=kind;this.operation=operation;}
        public void success(){success=true;}
        @Override public void close(){
            if(closed)return;closed=true;
            try{registry.timer("notes.workspace.storage.operation","objectClass",kind.name().toLowerCase(Locale.ROOT),
                "operation",operation.name().toLowerCase(Locale.ROOT),"outcome",success?"success":"unavailable")
                .record(System.nanoTime()-started,TimeUnit.NANOSECONDS);}
            catch(RuntimeException telemetryFailure){org.slf4j.LoggerFactory.getLogger(PublicStorageTelemetry.class).warn("storage_telemetry_deferred");}
        }
    }
}
