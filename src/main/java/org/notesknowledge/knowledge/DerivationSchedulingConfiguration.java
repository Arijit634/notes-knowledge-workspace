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
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(DerivationSchedulingConfiguration.class);
    private final DerivationReconciler reconciler;
    private final KnowledgeWorkService work;
    private final DerivationExecutor executor;
    private final TextEmbeddingPort embeddings;
    private final io.micrometer.core.instrument.MeterRegistry metrics;
    private final ThreadPoolTaskExecutor pool=new ThreadPoolTaskExecutor();
    private PrivateDerivationSource.Cursor cursor;
    private boolean stopping;
    @org.springframework.context.event.EventListener
    synchronized void stopClaiming(org.springframework.context.event.ContextClosedEvent event){stopping=true;}
    DerivationSchedulingConfiguration(DerivationReconciler reconciler,KnowledgeWorkService work,DerivationExecutor executor,
            org.springframework.beans.factory.ObjectProvider<TextEmbeddingPort> embeddings,io.micrometer.core.instrument.MeterRegistry metrics) {
        this.reconciler=reconciler;this.work=work;this.executor=executor;this.embeddings=embeddings.getIfAvailable();
        this.metrics=metrics;
        pool.setCorePoolSize(2);pool.setMaxPoolSize(2);pool.setQueueCapacity(0);pool.setThreadNamePrefix("knowledge-derivation-");
        pool.setWaitForTasksToCompleteOnShutdown(true);pool.setAwaitTerminationSeconds(30);pool.initialize();
    }
    @Scheduled(fixedDelayString="${knowledge.derivation.poll-delay-ms:5000}")
    synchronized void poll() {
        if(stopping)return;
        try {pollAvailable();}
        catch(org.springframework.dao.DataAccessException|org.springframework.transaction.TransactionException|org.notesknowledge.websupport.ApiFailureException unavailable) {
            unavailableMetric();
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
                    unavailableMetric();
                }
                catch(RuntimeException unexpected) {
                    // Do not let Thread's uncaught handler print private payload/SQL.
                    // Unknown effects stay leased for fenced recovery, never fake success.
                    LOG.error("knowledge_derivation_worker_failed");unavailableMetric();
                }
            });}
            catch(org.springframework.core.task.TaskRejectedException bounded) {work.retry(claim);}
        }
    }
    private void unavailableMetric() {
        try {metrics.counter("knowledge.derivation.poll","outcome","unavailable").increment();}
        catch(RuntimeException unavailable){LOG.warn("knowledge_derivation_telemetry_deferred");}
    }
    public synchronized void close(){stopping=true;pool.shutdown();}
}
