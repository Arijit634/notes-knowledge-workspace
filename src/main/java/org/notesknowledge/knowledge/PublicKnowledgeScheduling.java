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
    private final PublicKnowledgeWorker worker;
    private final io.micrometer.core.instrument.MeterRegistry meters;
    PublicKnowledgeScheduling(PublicKnowledgeWorker worker,io.micrometer.core.instrument.MeterRegistry meters){this.worker=worker;this.meters=meters;}
    @Scheduled(fixedDelayString="${knowledge.public.poll-delay-ms:5000}")
    void poll(){try{worker.poll();}catch(org.springframework.dao.DataAccessException|org.springframework.transaction.TransactionException|org.notesknowledge.websupport.ApiFailureException unavailable){
        try {meters.counter("knowledge.public.poll","outcome","unavailable").increment();}
        catch(RuntimeException telemetryUnavailable){org.slf4j.LoggerFactory.getLogger(PublicKnowledgeScheduling.class).warn("public_poll_telemetry_deferred");}
    }}
}
