package org.notesknowledge;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/** Technical timing only: identifiers, names, bytes and storage references are never accepted. */
@Component
public class PublicStorageTelemetry {
    public enum ObjectClass { PUBLIC_AVATAR,PUBLIC_MEDIA,PRIVATE_ATTACHMENT,PRIVATE_AVATAR }
    public enum Operation { COPY,READ,WRITE,DELETE,INVENTORY }
    private final MeterRegistry registry;
    public PublicStorageTelemetry(MeterRegistry registry){this.registry=registry;}
    public Sample start(ObjectClass kind,Operation operation){return new Sample(kind,operation);}
    public java.io.InputStream read(ObjectClass kind,java.util.function.Supplier<java.io.InputStream> open){
        var sample=start(kind,Operation.READ);
        try{
            var stream=open.get();
            return new java.io.FilterInputStream(stream){
                private boolean failed,closed;
                @Override public int read()throws java.io.IOException{try{return super.read();}catch(java.io.IOException|RuntimeException e){failed=true;throw e;}}
                @Override public int read(byte[] b,int off,int len)throws java.io.IOException{try{return in.read(b,off,len);}catch(java.io.IOException|RuntimeException e){failed=true;throw e;}}
                @Override public long skip(long count)throws java.io.IOException{try{return in.skip(count);}catch(java.io.IOException|RuntimeException e){failed=true;throw e;}}
                @Override public void close()throws java.io.IOException{
                    if(closed)return;closed=true;
                    try{super.close();if(!failed)sample.success();}finally{sample.close();}
                }
            };
        }catch(RuntimeException e){sample.close();throw e;}
    }
    public <T>T observe(ObjectClass kind,Operation op,java.util.function.Supplier<T> work){
        try(var sample=start(kind,op)){T result=work.get();sample.success();return result;}
    }
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
            BackendTelemetry.record(null,BackendTelemetry.Event.STORAGE,
                operation==Operation.COPY?BackendTelemetry.Operation.WRITE:BackendTelemetry.Operation.valueOf(operation.name()),
                success?BackendTelemetry.Outcome.SUCCESS:BackendTelemetry.Outcome.UNAVAILABLE,System.nanoTime()-started);
        }
    }
}
