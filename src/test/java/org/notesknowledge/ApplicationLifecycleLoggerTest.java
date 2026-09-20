package org.notesknowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

@Tag("FAST")
class ApplicationLifecycleLoggerTest {

    @Test
    void emitsOnlyBoundedSafeLifecycleEvents() {
        Logger logger = (Logger) LoggerFactory.getLogger(ApplicationLifecycleLogger.class);
        Level originalLevel = logger.getLevel();
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);

        try {
            var lifecycleLogger = new ApplicationLifecycleLogger();
            lifecycleLogger.logApplicationStart(120);
            lifecycleLogger.logApplicationReady(240);
            lifecycleLogger.logApplicationShutdown();
        }
        finally {
            logger.detachAppender(appender);
            logger.setLevel(originalLevel);
            appender.stop();
        }

        List<ILoggingEvent> events = appender.list;
        assertThat(events)
                .extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly("application.start", "application.ready", "application.shutdown");
        assertThat(events)
                .extracting(ApplicationLifecycleLoggerTest::keyValues)
                .extracting(values -> values.get("event.name"))
                .containsExactly("application.start", "application.ready", "application.shutdown");
        assertThat(events)
                .allSatisfy(event -> assertThat(keyValues(event).keySet())
                        .doesNotContain("userId", "email", "note", "query", "credential", "secret"));
    }

    private static Map<String, Object> keyValues(ILoggingEvent event) {
        return event.getKeyValuePairs().stream()
                .collect(Collectors.toUnmodifiableMap(pair -> pair.key, pair -> pair.value));
    }
}
