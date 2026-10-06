package org.notesknowledge.knowledge;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.notesknowledge.LeaseOwner;
import org.notesknowledge.knowledge.spi.PrivateDerivationSource;

/** Opt-in same-deployable worker: two threads, no queue, at most two outstanding claims. */
@Configuration(proxyBeanMethods=false)
@EnableScheduling
@ConditionalOnProperty(name="knowledge.derivation.scheduling-enabled",havingValue="true")
class DerivationSchedulingConfiguration implements AutoCloseable {
    private final DerivationReconciler reconciler;
    private final KnowledgeWorkService work;
    private final DerivationExecutor executor;
    private final TextEmbeddingPort embeddings;
    private final io.micrometer.core.instrument.MeterRegistry metrics;
    private final ThreadPoolTaskExecutor pool=new ThreadPoolTaskExecutor();
    private PrivateDerivationSource.Cursor cursor;
    DerivationSchedulingConfiguration(DerivationReconciler reconciler,KnowledgeWorkService work,DerivationExecutor executor,
            org.springframework.beans.factory.ObjectProvider<TextEmbeddingPort> embeddings,io.micrometer.core.instrument.MeterRegistry metrics) {
        this.reconciler=reconciler;this.work=work;this.executor=executor;this.embeddings=embeddings.getIfAvailable();
        this.metrics=metrics;
        pool.setCorePoolSize(2);pool.setMaxPoolSize(2);pool.setQueueCapacity(0);pool.setThreadNamePrefix("knowledge-derivation-");
        pool.setWaitForTasksToCompleteOnShutdown(true);pool.setAwaitTerminationSeconds(30);pool.initialize();
    }
    @Scheduled(fixedDelayString="${knowledge.derivation.poll-delay-ms:5000}")
    void poll() {
        try {pollAvailable();}
        catch(org.springframework.dao.DataAccessException|org.springframework.transaction.TransactionException|org.notesknowledge.websupport.ApiFailureException unavailable) {
            metrics.counter("knowledge.derivation.poll","outcome","unavailable").increment();
        }
    }
    private void pollAvailable() {
        if(embeddings==null||!embeddings.available())return;
        cursor=reconciler.page(cursor);
        int capacity=2-pool.getActiveCount();if(capacity<=0)return;
        var owner=new LeaseOwner("knowledge-local");
        var claims=work.reclaim(owner,capacity);
        if(claims.isEmpty())claims=work.claim(owner,capacity);
        for(var claim:claims) {
            try {pool.execute(()->{
                try {executor.execute(claim);}
                catch(org.springframework.dao.DataAccessException|org.springframework.transaction.TransactionException unavailable) {
                    metrics.counter("knowledge.derivation.poll","outcome","unavailable").increment();
                }
            });}
            catch(org.springframework.core.task.TaskRejectedException bounded) {work.retry(claim);}
        }
    }
    public void close(){pool.shutdown();}
}
