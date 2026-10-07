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
    private final KnowledgeOperationService operations;
    private final io.micrometer.core.instrument.MeterRegistry metrics;
    private final ThreadPoolTaskExecutor pool=new ThreadPoolTaskExecutor();
    KnowledgeOperationScheduling(KnowledgeOperationService operations,io.micrometer.core.instrument.MeterRegistry metrics) {
        this.operations=operations;this.metrics=metrics;pool.setCorePoolSize(2);pool.setMaxPoolSize(2);pool.setQueueCapacity(0);
        pool.setThreadNamePrefix("knowledge-operation-");pool.setWaitForTasksToCompleteOnShutdown(true);pool.setAwaitTerminationSeconds(65);pool.initialize();
    }
    @Scheduled(fixedDelayString="${knowledge.operations.poll-delay-ms:1000}")
    void poll(){try {
        if(!operations.databaseAvailable())return;
        int capacity=2-pool.getActiveCount();if(capacity<=0)return;
        for(var row:operations.claim(capacity))try {pool.execute(()->{try{operations.execute(row);}catch(org.springframework.dao.DataAccessException|org.springframework.transaction.TransactionException unavailable){metrics.counter("notes.workspace.knowledge.operation","outcome","databaseUnavailable").increment();}});}
            catch(org.springframework.core.task.TaskRejectedException bounded){metrics.counter("notes.workspace.knowledge.operation","outcome","capacity").increment();}
    }catch(org.springframework.dao.DataAccessException|org.springframework.transaction.TransactionException|org.notesknowledge.websupport.ApiFailureException unavailable){metrics.counter("notes.workspace.knowledge.operation","outcome","unavailable").increment();}}
    public void close(){pool.shutdown();}
}
