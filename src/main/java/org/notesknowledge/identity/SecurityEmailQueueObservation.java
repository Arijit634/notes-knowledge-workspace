package org.notesknowledge.identity;

import java.time.Clock;
import org.notesknowledge.WorkQueueTelemetry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@IdentityCoreEnabled
@ConditionalOnProperty(name="operations.telemetry.enabled",havingValue="true")
class SecurityEmailQueueObservation {
    private final JdbcClient jdbc;private final ObjectProvider<PlatformTransactionManager> managers;
    private final WorkQueueTelemetry metrics;
    private final SecurityEmailDeliveryProperties properties;
    SecurityEmailQueueObservation(JdbcClient jdbc,ObjectProvider<PlatformTransactionManager> managers,io.micrometer.core.instrument.MeterRegistry meters,Clock clock,SecurityEmailDeliveryProperties properties){
        this.jdbc=jdbc;this.managers=managers;this.properties=properties;metrics=new WorkQueueTelemetry(meters,clock,true);
    }
    @Scheduled(fixedDelay=60000,initialDelay=1000)
    void sample(){try{
        var tx=new TransactionTemplate(managers.getObject());tx.setReadOnly(true);tx.setTimeout(3);
        var rows=tx.execute(s->jdbc.sql("""
            select delivery_kind,state,count(*) as amount,
                min(created_at) filter(where ((state in ('queued','retry_wait') and next_attempt_at<=clock_timestamp())
                    or (state='claimed' and lease_until<=clock_timestamp())) and attempt_count<:attempts) as oldest
            from identity.security_email_delivery group by delivery_kind,state
            """).param("attempts",properties.maxAttempts()).query((r,i)->new WorkQueueTelemetry.Row(r.getString(1).equals("capability_link")?WorkQueueTelemetry.Kind.CAPABILITY_LINK:WorkQueueTelemetry.Kind.SECURITY_NOTICE,
                WorkQueueTelemetry.State.valueOf(r.getString(2).toUpperCase(java.util.Locale.ROOT)),r.getLong(3),r.getTimestamp(4)==null?null:r.getTimestamp(4).toInstant())).list());
        metrics.publish(rows);
    }catch(RuntimeException unavailable){metrics.unavailable();}}
}
