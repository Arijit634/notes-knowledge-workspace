package org.notesknowledge.notes;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Bounded, same-deployable sweeps. Database cleanup state and object inventory
 * remain authoritative; these cursors merely avoid starvation and reset on restart. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix="notes.private-storage", name={"enabled","reconciliation-enabled"}, havingValue="true")
class AttachmentStorageReconciler {
    private static final Logger LOG = LoggerFactory.getLogger(AttachmentStorageReconciler.class);
    private final AttachmentDeleteService deletion;
    private final AttachmentUploadService upload;
    private final java.time.Clock clock;
    private final ThreadPoolTaskScheduler scheduler=new ThreadPoolTaskScheduler();
    private UUID pendingAfter;
    private String orphanAfter;

    AttachmentStorageReconciler(AttachmentDeleteService deletion, AttachmentUploadService upload,java.time.Clock clock) {
        this.deletion=deletion;this.upload=upload;this.clock=clock;
    }

    @jakarta.annotation.PostConstruct
    void start() {
        // Not a TaskScheduler bean: selecting storage must not replace Boot's
        // scheduler used by Identity/Knowledge or serialize them behind object I/O.
        scheduler.setPoolSize(1);scheduler.setClock(clock);
        scheduler.setThreadNamePrefix("attachment-cleanup-");scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.initialize();scheduler.scheduleWithFixedDelay(this::sweep,clock.instant().plusSeconds(60),java.time.Duration.ofSeconds(60));
    }
    @jakarta.annotation.PreDestroy void stop(){scheduler.shutdown();}

    void sweep() {
        try { pendingAfter=deletion.reconcilePendingPage(pendingAfter,5); }
        catch(RuntimeException failed){LOG.warn("attachment_cleanup_sweep_deferred");}
        try { orphanAfter=upload.reconcile(orphanAfter,5); }
        catch(RuntimeException failed){LOG.warn("attachment_orphan_sweep_deferred");}
    }
}
