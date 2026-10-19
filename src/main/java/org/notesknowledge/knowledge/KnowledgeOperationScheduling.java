package org.notesknowledge.knowledge;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Two bounded same-deployable workers. PostgreSQL, not this executor, owns accepted work. */
@Configuration(proxyBeanMethods=false)
@EnableScheduling
@ConditionalOnProperty(name="knowledge.operations.scheduling-enabled",havingValue="true",matchIfMissing=true)
class KnowledgeOperationScheduling implements AutoCloseable {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(KnowledgeOperationScheduling.class);
    private final KnowledgeOperationService operations;
    private final io.micrometer.core.instrument.MeterRegistry metrics;
    private final ThreadPoolTaskExecutor pool=new ThreadPoolTaskExecutor();
    private boolean stopping;
    @org.springframework.context.event.EventListener
    synchronized void stopClaiming(org.springframework.context.event.ContextClosedEvent event){stopping=true;}
    KnowledgeOperationScheduling(KnowledgeOperationService operations,io.micrometer.core.instrument.MeterRegistry metrics) {
        this.operations=operations;this.metrics=metrics;pool.setCorePoolSize(2);pool.setMaxPoolSize(2);pool.setQueueCapacity(0);
        pool.setThreadNamePrefix("knowledge-operation-");pool.setWaitForTasksToCompleteOnShutdown(true);pool.setAwaitTerminationSeconds(65);pool.initialize();
    }
    @Scheduled(fixedDelayString="${knowledge.operations.poll-delay-ms:1000}")
    synchronized void poll(){if(stopping)return;try {
        if(!operations.databaseAvailable())return;
        int capacity=2-pool.getActiveCount();if(capacity<=0)return;
        for(var row:operations.claim(capacity))try {pool.execute(()->{
            try{operations.execute(row);}
            catch(org.springframework.dao.DataAccessException|org.springframework.transaction.TransactionException unavailable){metric("databaseUnavailable");}
            catch(RuntimeException unexpected){
                // Durable claim recovery owns the unknown outcome. Never print payloads
                // through an uncaught handler or pretend the operation completed.
                LOG.error("knowledge_operation_worker_failed");metric("unavailable");
            }
        });}
            catch(org.springframework.core.task.TaskRejectedException bounded){metric("capacity");}
    }catch(org.springframework.dao.DataAccessException|org.springframework.transaction.TransactionException|org.notesknowledge.websupport.ApiFailureException unavailable){metric("unavailable");}}
    private void metric(String outcome) {
        try {metrics.counter("notes.workspace.knowledge.operation","outcome",outcome).increment();}
        catch(RuntimeException unavailable){LOG.warn("knowledge_operation_telemetry_deferred");}
    }
    public synchronized void close(){stopping=true;pool.shutdown();}
}
