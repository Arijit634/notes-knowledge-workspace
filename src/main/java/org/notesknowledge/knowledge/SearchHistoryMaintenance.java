package org.notesknowledge.knowledge;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Retention is independent of provider availability and operational execution. */
@Configuration(proxyBeanMethods=false)
@EnableScheduling
@ConditionalOnProperty(name="knowledge.history.maintenance-enabled",havingValue="true",matchIfMissing=true)
class SearchHistoryMaintenance {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(SearchHistoryMaintenance.class);
    private final SearchHistoryService history;
    SearchHistoryMaintenance(SearchHistoryService history){this.history=history;}
    @Scheduled(fixedDelayString="${knowledge.history.cleanup-delay-ms:60000}",initialDelayString="${knowledge.history.cleanup-delay-ms:60000}")
    void expire(){
        if(!history.databaseAvailable())return;
        try{history.sweep();}
        catch(RuntimeException unavailable){
            // Never let the scheduler's default handler expose SQL or retained material.
            LOG.warn("knowledge_history_cleanup_deferred");
        }
    }
}
