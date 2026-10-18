package org.notesknowledge;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.slf4j.LoggerFactory;

/** Diagnostic only. The API accepts enums and durations, never resource or content values. */
public final class BackendTelemetry {
    public enum Event {
        AUTH("auth.outcome","identity","notes.workspace.identity.auth","flow"),
        NOTE("note.command.outcome","notes","notes.workspace.note.command","operation"),
        VALIDATION("attachment.validation.outcome","notes","notes.workspace.attachment.validation","modality"),
        STORAGE("storage.operation.outcome","application","notes.workspace.storage.operation","operation"),
        WORK("knowledge.work.outcome","knowledge","notes.workspace.knowledge.work.outcome","jobType"),
        RETRIEVAL("retrieval.outcome","knowledge","notes.workspace.search.request","queryClass"),
        PROVIDER("ai.provider.outcome","knowledge","notes.workspace.ai.provider","capability"),
        EMAIL("security_email.work.outcome","identity","notes.workspace.security_email.outcome","jobType"),
        PUBLICATION("publication.command.outcome","publishing","notes.workspace.publication.command","operation"),
        DENIAL("public_denial.outcome","publishing","notes.workspace.public.denial","reasonClass"),
        MODERATION("moderation.outcome","moderation","notes.workspace.moderation.command","operation");
        final String name,module,meter,dimension;
        Event(String name,String module,String meter,String dimension){this.name=name;this.module=module;this.meter=meter;this.dimension=dimension;}
    }
    public enum Operation {
        PASSWORD_LOGIN,OIDC,MFA,RECOVERY,REGISTRATION,VERIFICATION,LOGOUT,RECENT_AUTH,SECURITY_SETTINGS,
        CREATE,SAVE,TAGS,PIN,LIFECYCLE,VERSIONS,AI_ACCESS,
        IMAGE,PDF,AUDIO,VIDEO,UNKNOWN,
        READ,WRITE,DELETE,INVENTORY,
        DERIVATION,QUERY,PUBLIC_INDEX,PUBLIC_RECONCILE,CAPABILITY_LINK,SECURITY_NOTICE,
        RANKED,FOCUSED,SEMANTIC_CORPUS,DETERMINISTIC_CORPUS,ORDINARY,
        EMBEDDING,MEDIA_UNDERSTANDING,GENERATION,
        UPDATE,UNPUBLISH,REPUBLISH,LOGICAL_DENIAL,BEGIN_REVIEW,DECISION,CONSEQUENCE
    }
    public enum Outcome { SUCCESS,REJECTED,UNAVAILABLE,DEGRADED,RETRY,FAILED,OBSOLETE,RATE_LIMITED }

    private BackendTelemetry() { }

    public static void record(MeterRegistry registry,Event event,Operation operation,Outcome outcome,long nanos) {
        // Export failure must not change a committed operation, provider fence or API result.
        String op=operation.name().toLowerCase(Locale.ROOT),result=outcome.name().toLowerCase(Locale.ROOT);
        try {
            if(registry!=null&&(event!=Event.VALIDATION||operation!=Operation.UNKNOWN)) {
                if(event==Event.RETRIEVAL){
                    String queryClass=operation==Operation.ORDINARY?"ranked":op;
                    String strategy=operation==Operation.ORDINARY?"lexical":operation==Operation.SEMANTIC_CORPUS?"semantic":
                        operation==Operation.DETERMINISTIC_CORPUS?"deterministic":"hybrid";
                    registry.timer(event.meter,event.dimension,queryClass,"strategy",strategy,"outcome",result)
                        .record(Math.max(0,nanos),TimeUnit.NANOSECONDS);
                }else if(event==Event.PROVIDER||event==Event.VALIDATION||event==Event.STORAGE)
                    registry.timer(event.meter,event.dimension,op,"outcome",result)
                        .record(Math.max(0,nanos),TimeUnit.NANOSECONDS);
                else registry.counter(event.meter,event.dimension,op,"outcome",result).increment();
            }
        }catch(RuntimeException unavailable){/* metric export is never business authority */}
        try{
            var logger=LoggerFactory.getLogger(BackendTelemetry.class);
            var log=outcome==Outcome.SUCCESS&&event!=Event.DENIAL?logger.atInfo():logger.atWarn();
            log.addKeyValue("event.name",event.name).addKeyValue("module",event.module)
                .addKeyValue("operation",op).addKeyValue("outcome",result)
                .addKeyValue("durationMs",TimeUnit.NANOSECONDS.toMillis(Math.max(0,nanos)))
                .log(event.name);
        }catch(RuntimeException unavailable){/* telemetry is never business authority */}
    }

    public static Outcome httpOutcome(int status) {
        return status>=500?Outcome.UNAVAILABLE:status>=400?Outcome.REJECTED:Outcome.SUCCESS;
    }

    public static void afterCommit(MeterRegistry registry,Event event,Operation operation,Outcome outcome){
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive())
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization(){
                    @Override public void afterCommit(){record(registry,event,operation,outcome,0);}
                });
        else record(registry,event,operation,outcome,0);
    }
}
