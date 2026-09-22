package org.notesknowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.TimeZone;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Tag("FAST")
@ExtendWith(OutputCaptureExtension.class)
class RuntimeFoundationTest {

    private static final Instant FIXED = Instant.parse("2031-02-03T04:05:06Z");

    @Test
    void defaultClockIsUtcAndConstructorInjectable() {
        try (var context = new AnnotationConfigApplicationContext(
                RuntimeFoundationConfiguration.class, ClockConsumer.class)) {
            assertThat(context.getBean(Clock.class).getZone()).isEqualTo(ZoneOffset.UTC);
            assertThat(context.getBean(ClockConsumer.class).clock)
                    .isSameAs(context.getBean(Clock.class));
        }
    }

    @Test
    void fixedClockOverridesDefaultWithoutHostTimezoneDependence() {
        TimeZone previous = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"));
            try (var context = new AnnotationConfigApplicationContext(
                    RuntimeFoundationConfiguration.class, FixedClockConfiguration.class,
                    ClockConsumer.class)) {
                assertThat(context.getBean(ClockConsumer.class).instant()).isEqualTo(FIXED);
                assertThat(context.getBean(Clock.class).getZone()).isEqualTo(ZoneOffset.UTC);
            }
        }
        finally {
            TimeZone.setDefault(previous);
        }
    }

    @Test
    void dependencyStateIsBoundedDeduplicatedAndEmitsSafeTransition(CapturedOutput output) {
        var tracker = new DependencyHealthTracker(Clock.fixed(FIXED, ZoneOffset.UTC));
        var dependency = DependencyHealthTracker.Dependency.GEMINI_CHAT;
        var impact = DependencyHealthTracker.FeatureImpact.OPTIONAL_FEATURE;
        var reason = DependencyHealthTracker.SafeReasonClass.UNAVAILABLE;

        assertThat(tracker.status(dependency).state())
                .isEqualTo(DependencyHealthTracker.State.UNKNOWN);
        tracker.transition(dependency, DependencyHealthTracker.State.UP, impact,
                DependencyHealthTracker.SafeReasonClass.RECOVERED);
        var down = tracker.transition(dependency, DependencyHealthTracker.State.DOWN, impact, reason);
        var repeated = tracker.transition(dependency, DependencyHealthTracker.State.DOWN, impact, reason);

        assertThat(down).isSameAs(repeated);
        assertThat(down.changedAt()).isEqualTo(FIXED);
        assertThat(down.featureImpact()).isEqualTo(impact);
        assertThat(down.safeReasonClass()).isEqualTo(reason);
        assertThat(output.getAll()).contains("dependency.state_change")
                .contains("GEMINI_CHAT", "UNKNOWN", "UP", "DOWN", "OPTIONAL_FEATURE", "UNAVAILABLE")
                .doesNotContain("synthetic-secret", "https://", "Bearer ");
        assertThat(output.getAll().split("dependency.state_change", -1)).hasSize(5);
    }

    @Test
    void conditionalSecurityImpactIsDistinctFromOptionalFeatureImpact(CapturedOutput output) {
        var tracker = new DependencyHealthTracker(Clock.fixed(FIXED, ZoneOffset.UTC));
        var dependency = DependencyHealthTracker.Dependency.REDIS;
        tracker.transition(dependency, DependencyHealthTracker.State.DOWN,
                DependencyHealthTracker.FeatureImpact.OPTIONAL_FEATURE,
                DependencyHealthTracker.SafeReasonClass.UNAVAILABLE);
        tracker.transition(dependency, DependencyHealthTracker.State.DOWN,
                DependencyHealthTracker.FeatureImpact.SECURITY_CONTROL,
                DependencyHealthTracker.SafeReasonClass.UNAVAILABLE);

        assertThat(tracker.status(dependency).featureImpact())
                .isEqualTo(DependencyHealthTracker.FeatureImpact.SECURITY_CONTROL);
        assertThat(output.getAll().split("dependency.state_change", -1)).hasSize(3);
    }

    @Test
    void productionJavaSourceCannotUseRandomUuid() throws IOException {
        try (var paths = Files.walk(Path.of("src", "main", "java"))) {
            for (Path source : paths.filter(path -> path.toString().endsWith(".java")).toList()) {
                assertThat(Files.readString(source))
                        .as("production UUID policy: %s", source)
                        .doesNotMatch("(?s).*\\bUUID\\s*\\.\\s*randomUUID\\s*\\(.*");
            }
        }
    }

    static final class ClockConsumer {
        private final Clock clock;

        ClockConsumer(Clock clock) {
            this.clock = clock;
        }

        Instant instant() {
            return clock.instant();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class FixedClockConfiguration {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(FIXED, ZoneOffset.UTC);
        }
    }
}
