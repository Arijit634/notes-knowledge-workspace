package org.notesknowledge.publishing;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Bounded owner-module maintenance, not a work queue. DB references and object
 * inventory are durable truth; cursors are restart-disposable scan optimizations. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="publishing.media-storage",name={"enabled","reconciliation-enabled"},havingValue="true")
class PublicMediaStorageReconciler {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(PublicMediaStorageReconciler.class);
    private final PublicationService service;
    private final java.time.Clock clock;
    private final ThreadPoolTaskScheduler scheduler=new ThreadPoolTaskScheduler();
    private String after;

    PublicMediaStorageReconciler(PublicationService service,java.time.Clock clock){this.service=service;this.clock=clock;}
    @jakarta.annotation.PostConstruct void start(){
        // Private scheduler instance must not replace Boot's business-work scheduler.
        scheduler.setPoolSize(1);scheduler.setClock(clock);
        scheduler.setThreadNamePrefix("publishing-object-cleanup-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);scheduler.initialize();
        scheduler.scheduleWithFixedDelay(this::sweep,clock.instant().plusSeconds(60),java.time.Duration.ofSeconds(60));
    }
    @jakarta.annotation.PreDestroy void stop(){scheduler.shutdown();}
    void sweep(){
        try{after=service.reconcile(after,5);}catch(RuntimeException failed){LOG.warn("publishing_orphan_sweep_deferred");}
    }
}
