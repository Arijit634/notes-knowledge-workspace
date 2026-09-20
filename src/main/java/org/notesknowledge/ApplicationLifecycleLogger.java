package org.notesknowledge;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
final class ApplicationLifecycleLogger {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApplicationLifecycleLogger.class);

    @EventListener
    void onApplicationStarted(ApplicationStartedEvent event) {
        logApplicationStart(toMillis(event.getTimeTaken()));
    }

    @EventListener
    void onApplicationReady(ApplicationReadyEvent event) {
        logApplicationReady(toMillis(event.getTimeTaken()));
    }

    @EventListener
    void onApplicationShutdown(ContextClosedEvent event) {
        logApplicationShutdown();
    }

    void logApplicationStart(long startupDurationMs) {
        LOGGER.atInfo()
                .addKeyValue("event.name", "application.start")
                .addKeyValue("module", "application")
                .addKeyValue("operation", "lifecycle")
                .addKeyValue("outcome", "started")
                .addKeyValue("startupDurationMs", startupDurationMs)
                .log("application.start");
    }

    void logApplicationReady(long startupDurationMs) {
        LOGGER.atInfo()
                .addKeyValue("event.name", "application.ready")
                .addKeyValue("module", "application")
                .addKeyValue("operation", "lifecycle")
                .addKeyValue("outcome", "ready")
                .addKeyValue("startupDurationMs", startupDurationMs)
                .log("application.ready");
    }

    void logApplicationShutdown() {
        LOGGER.atInfo()
                .addKeyValue("event.name", "application.shutdown")
                .addKeyValue("module", "application")
                .addKeyValue("operation", "lifecycle")
                .addKeyValue("outcome", "stopped")
                .addKeyValue("reasonClass", "context_closed")
                .log("application.shutdown");
    }

    private static long toMillis(Duration duration) {
        return duration == null ? 0 : Math.max(0, duration.toMillis());
    }
}
