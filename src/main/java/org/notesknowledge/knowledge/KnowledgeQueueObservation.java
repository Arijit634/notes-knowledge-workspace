package org.notesknowledge.knowledge;

import java.time.Clock;
import org.notesknowledge.WorkQueueTelemetry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Own-schema aggregate query. It never loads source content or changes work. */
@Component
@ConditionalOnProperty(name="operations.telemetry.enabled",havingValue="true")
class KnowledgeQueueObservation {
    private final ObjectProvider<JdbcClient> clients;
    private final ObjectProvider<PlatformTransactionManager> managers;
    private final WorkQueueTelemetry metrics;
    KnowledgeQueueObservation(ObjectProvider<JdbcClient> clients,ObjectProvider<PlatformTransactionManager> managers,
            io.micrometer.core.instrument.MeterRegistry meters,Clock clock){this.clients=clients;this.managers=managers;metrics=new WorkQueueTelemetry(meters,clock,false);}
    @Scheduled(fixedDelay=60000,initialDelay=1000)
    void sample(){try{
        var tx=new TransactionTemplate(managers.getObject());tx.setReadOnly(true);tx.setTimeout(3);
        var rows=tx.execute(s->clients.getObject().sql("""
            select work_class,state,count(*) as amount,
                min(created_at) filter(where ((state in ('queued','retry_wait') and next_attempt_at<=clock_timestamp())
                    or (state='claimed' and lease_until<=clock_timestamp()))
                    and (attempt_count<max_attempts or (work_class='private_knowledge_operation' and state='queued' and checkpoint_version>0))) as oldest
            from knowledge.knowledge_work_intent group by work_class,state
            """).query((r,i)->new WorkQueueTelemetry.Row(switch(r.getString(1)){
                case "private_note_derivation"->WorkQueueTelemetry.Kind.NOTE_DERIVATION;
                case "private_attachment_derivation"->WorkQueueTelemetry.Kind.ATTACHMENT_DERIVATION;
                case "private_knowledge_operation"->WorkQueueTelemetry.Kind.QUERY;
                case "public_publication_derivation"->WorkQueueTelemetry.Kind.PUBLIC_INDEX;
                default->throw new IllegalStateException("Unknown bounded work class");
            },WorkQueueTelemetry.State.valueOf(r.getString(2).toUpperCase(java.util.Locale.ROOT)),r.getLong(3),r.getTimestamp(4)==null?null:r.getTimestamp(4).toInstant())).list());
        metrics.publish(rows);
    }catch(RuntimeException unavailable){metrics.unavailable();}}
}
