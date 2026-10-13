package org.notesknowledge.profile;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Bounded owner-module maintenance, not a work queue. DB references and object
 * inventory are durable truth; cursors are restart-disposable scan optimizations. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="profile.avatar-storage",name={"enabled","reconciliation-enabled"},havingValue="true")
class PrivateAvatarStorageReconciler {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(PrivateAvatarStorageReconciler.class);
    private final AvatarService service;
    private final java.time.Clock clock;
    private final ThreadPoolTaskScheduler scheduler=new ThreadPoolTaskScheduler();
    private String after;
    private java.util.UUID pendingAfter;

    PrivateAvatarStorageReconciler(AvatarService service,java.time.Clock clock){this.service=service;this.clock=clock;}
    @jakarta.annotation.PostConstruct void start(){
        // Private scheduler instance must not replace Boot's business-work scheduler.
        scheduler.setPoolSize(1);scheduler.setClock(clock);
        scheduler.setThreadNamePrefix("profile-object-cleanup-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);scheduler.initialize();
        scheduler.scheduleWithFixedDelay(this::sweep,clock.instant().plusSeconds(60),java.time.Duration.ofSeconds(60));
    }
    @jakarta.annotation.PreDestroy void stop(){scheduler.shutdown();}
    void sweep(){
        try{pendingAfter=service.reconcilePendingPage(pendingAfter,5);}catch(RuntimeException failed){LOG.warn("avatar_cleanup_sweep_deferred");}
        try{after=service.reconcile(after,5);}catch(RuntimeException failed){LOG.warn("profile_orphan_sweep_deferred");}
    }
}
