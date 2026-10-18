package org.notesknowledge;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Bounded technical query, not a cross-module repository or readiness authority. */
@Component
@ConditionalOnProperty(name="operations.telemetry.enabled",havingValue="true")
class OperationalDatabaseObservation {
    private final JdbcClient jdbc;private final PlatformTransactionManager transactions;private final MeterRegistry meters;
    OperationalDatabaseObservation(JdbcClient jdbc,PlatformTransactionManager transactions,MeterRegistry meters){this.jdbc=jdbc;this.transactions=transactions;this.meters=meters;}
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=60000,initialDelay=1000)
    void sample(){
        long started=System.nanoTime();String outcome="unavailable";
        try{var tx=new TransactionTemplate(transactions);tx.setTimeout(3);tx.setReadOnly(true);
            if(Boolean.TRUE.equals(tx.execute(s->jdbc.sql("select 1").query(Integer.class).single()==1)))outcome="success";
        }catch(RuntimeException unavailable){/* No SQL text, diagnostics or bind values are retained. */}
        finally{try{meters.timer("notes.workspace.db.operation","queryClass","health","outcome",outcome).record(System.nanoTime()-started,TimeUnit.NANOSECONDS);}
            catch(RuntimeException unavailable){/* diagnostic only */}}
    }
}
