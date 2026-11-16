package org.notesknowledge.knowledge;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Same-deployable, serial bounded poll; opt-in, no in-memory durable queue. */
@Configuration(proxyBeanMethods=false)
@EnableScheduling
@ConditionalOnProperty(name="knowledge.public.scheduling-enabled",havingValue="true")
class PublicKnowledgeScheduling {
    private org.notesknowledge.DatabaseIdleCoordinator idleCoordinator;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    void idleCoordinator(org.notesknowledge.DatabaseIdleCoordinator value){this.idleCoordinator=value;}
    private final PublicKnowledgeWorker worker;
    private final io.micrometer.core.instrument.MeterRegistry meters;
    private boolean stopping;
    private long cycleVersion=-1,completedVersion=-1;
    synchronized boolean reconciled(long version){return completedVersion==version;}
    @org.springframework.context.event.EventListener
    synchronized void stopClaiming(org.springframework.context.event.ContextClosedEvent event){stopping=true;}
    PublicKnowledgeScheduling(PublicKnowledgeWorker worker,io.micrometer.core.instrument.MeterRegistry meters){this.worker=worker;this.meters=meters;}
    @Scheduled(fixedDelayString="${knowledge.public.poll-delay-ms:5000}")
    synchronized void poll(){
        try(var idle=org.notesknowledge.DatabaseIdleCoordinator.background(idleCoordinator)){
            if(!idle.allowed())return;

            if(stopping)return;try{
                if(worker.reconciliationComplete())cycleVersion=idleCoordinator==null?0:idleCoordinator.activityVersion();
                worker.poll();
                if(worker.reconciliationComplete())completedVersion=cycleVersion;
            }catch(org.springframework.dao.DataAccessException|org.springframework.transaction.TransactionException|org.notesknowledge.websupport.ApiFailureException unavailable){
            try {meters.counter("knowledge.public.poll","outcome","unavailable").increment();}
            catch(RuntimeException telemetryUnavailable){org.slf4j.LoggerFactory.getLogger(PublicKnowledgeScheduling.class).warn("public_poll_telemetry_deferred");}
        }
        }
    }
}
