package org.notesknowledge.knowledge;

import java.time.Duration;
import org.notesknowledge.DatabaseIdleParticipant;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Knowledge-owned durable work and retention projection. No source material is read. */
@Component
@ConditionalOnProperty(name="operations.database-idle.enabled",havingValue="true")
final class KnowledgeIdleMaintenance implements DatabaseIdleParticipant {
    private final JdbcClient jdbc;
    private final TransactionTemplate reads;
    private final ObjectProvider<DerivationSchedulingConfiguration> derivations;
    private final ObjectProvider<PublicKnowledgeScheduling> publicWork;
    KnowledgeIdleMaintenance(JdbcClient jdbc,PlatformTransactionManager manager,
            ObjectProvider<DerivationSchedulingConfiguration> derivations,ObjectProvider<PublicKnowledgeScheduling> publicWork){
        this.jdbc=jdbc;this.derivations=derivations;this.publicWork=publicWork;
        reads=new TransactionTemplate(manager);reads.setReadOnly(true);reads.setTimeout(3);
    }
    public String owner(){return "knowledge";}
    public Forecast inspect(long version){
        var privateScheduler=derivations.getIfAvailable();
        if(privateScheduler!=null&&!privateScheduler.reconciled(version))return Forecast.busy();
        var publicScheduler=publicWork.getIfAvailable();
        if(publicScheduler!=null&&!publicScheduler.reconciled(version))return Forecast.busy();
        return reads.execute(status->{
            if(jdbc.sql("select exists(select 1 from knowledge.knowledge_work_intent where state in ('queued','claimed','retry_wait'))")
                    .query(Boolean.class).single())return Forecast.busy();
            double seconds=jdbc.sql("""
                select coalesce(extract(epoch from (min(deadline)-clock_timestamp())),1800) from (
                    select min(expires_at) deadline from knowledge.search_history_entry
                    union all
                    select min(operation_expires_at) from knowledge.knowledge_work_intent
                    where work_class='private_knowledge_operation' and (input_ciphertext is not null or result_ciphertext is not null)
                ) deadlines
                """).query(Double.class).single();
            return seconds<=0?Forecast.busy():Forecast.quiet(Duration.ofMillis(Math.max(1,(long)(seconds*1000))));
        });
    }
}
