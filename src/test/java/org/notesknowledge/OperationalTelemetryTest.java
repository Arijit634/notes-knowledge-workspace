package org.notesknowledge;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("FAST")
class OperationalTelemetryTest {
    @Test void everyApplicationDashboardMetricIsAcceptedByTheBoundedExportPolicy()throws Exception{
        try(var input=getClass().getResourceAsStream("/operations/observability.json")){
            var contract=new tools.jackson.databind.ObjectMapper().readTree(input);
            var filter=new OperationalMeterConfiguration().boundedOperationalDimensions();
            for(var signal:contract.get("applicationSignals"))
                assertThat(filter.accept(new io.micrometer.core.instrument.Meter.Id(signal.get("meter").asString(),
                    io.micrometer.core.instrument.Tags.empty(),null,null,io.micrometer.core.instrument.Meter.Type.OTHER)))
                    .isNotEqualTo(io.micrometer.core.instrument.config.MeterFilterReply.DENY);
            assertThat(contract.get("events").size()).isEqualTo(17);assertThat(contract.get("alerts").size()).isEqualTo(27);
            assertThat(contract.get("dashboards").size()).isEqualTo(7);
        }
    }
    @Test void approvedBootManagedExporterPublishesActualBoundedMeters(){
        var registry=new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try{
            registry.config().meterFilter(new OperationalMeterConfiguration().boundedOperationalDimensions());
            BackendTelemetry.record(registry,BackendTelemetry.Event.PROVIDER,BackendTelemetry.Operation.GENERATION,BackendTelemetry.Outcome.SUCCESS,2_000_000);
            BackendTelemetry.record(registry,BackendTelemetry.Event.WORK,BackendTelemetry.Operation.DERIVATION,BackendTelemetry.Outcome.OBSOLETE,0);
            BackendTelemetry.record(registry,BackendTelemetry.Event.RETRIEVAL,BackendTelemetry.Operation.ORDINARY,BackendTelemetry.Outcome.SUCCESS,1);
            assertThat(registry.get("notes.workspace.search.request").tags("queryClass","ranked","strategy","lexical").timer().count()).isEqualTo(1);
            String scrape=registry.scrape();
            assertThat(scrape).contains("notes_workspace_ai_provider_seconds_count", "capability=\"generation\"", "notes_workspace_knowledge_work_outcome_total");
            assertThat(scrape).doesNotContain("owner", "noteId", "attachmentId", "traceId", "Synthetic-Private-Canary");
            registry.counter("notes.workspace.forbidden","noteId","Synthetic-Private-Canary").increment();
            registry.counter("notes.workspace.forbidden","outcome","Synthetic-Private-Canary").increment();
            assertThat(registry.scrape()).doesNotContain("Synthetic-Private-Canary","notes_workspace_forbidden");
        }finally{registry.close();}
    }
    @Test void failedTelemetryCannotChangeAnOperationOutcome(){
        var registry=mock(MeterRegistry.class);
        when(registry.counter("notes.workspace.identity.auth","flow","password_login","outcome","success"))
            .thenThrow(new IllegalStateException("Synthetic-Private-Canary"));
        assertThatCode(()->BackendTelemetry.record(registry,BackendTelemetry.Event.AUTH,BackendTelemetry.Operation.PASSWORD_LOGIN,BackendTelemetry.Outcome.SUCCESS,1)).doesNotThrowAnyException();
    }
    @Test void workQueueMetricsAreExactSchedulingAggregatesAndUnavailableIsNotZero(){
        var registry=new SimpleMeterRegistry();
        try{
            var now=java.time.Instant.parse("2026-10-18T00:00:00Z");
            var queue=new WorkQueueTelemetry(registry,java.time.Clock.fixed(now,java.time.ZoneOffset.UTC),true);
            assertThat(registry.get("notes.workspace.security_email.work.count").tags("jobType","capability_link","state","queued").gauge().value()).isNaN();
            queue.publish(java.util.List.of(new WorkQueueTelemetry.Row(WorkQueueTelemetry.Kind.CAPABILITY_LINK,WorkQueueTelemetry.State.QUEUED,3,now.minusSeconds(90))));
            assertThat(registry.get("notes.workspace.security_email.work.count").tags("jobType","capability_link","state","queued").gauge().value()).isEqualTo(3);
            assertThat(registry.get("notes.workspace.security_email.oldest_ready_age").tag("jobType","capability_link").gauge().value()).isEqualTo(90);
            assertThat(registry.find("notes.workspace.security_email.work.count").tag("state","expired").gauge()).isNull();
            queue.unavailable();assertThat(registry.get("notes.workspace.security_email.oldest_ready_age").tag("jobType","capability_link").gauge().value()).isNaN();
        }finally{registry.close();}
    }
    @Test void rollbackNeverEmitsACommittedWorkerOutcome(){
        var registry=new SimpleMeterRegistry();
        try{
            org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
            try{
                BackendTelemetry.afterCommit(registry,BackendTelemetry.Event.EMAIL,BackendTelemetry.Operation.CAPABILITY_LINK,BackendTelemetry.Outcome.SUCCESS);
                assertThat(registry.getMeters()).isEmpty();
                var callbacks=org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations();
                callbacks.forEach(c->c.afterCompletion(org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK));
                assertThat(registry.getMeters()).isEmpty();
            }finally{org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();}
        }finally{registry.close();}
    }
}
