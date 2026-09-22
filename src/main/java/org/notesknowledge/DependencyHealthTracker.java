package org.notesknowledge;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Bounded operator telemetry, never authorization or readiness authority. */
@Component
public final class DependencyHealthTracker {

    private static final Logger LOGGER = LoggerFactory.getLogger(DependencyHealthTracker.class);

    public enum Dependency {
        POSTGRESQL, REDIS, OBJECT_STORAGE, SECURITY_EMAIL, GOOGLE_OIDC,
        GEMINI_CHAT, GEMINI_EMBEDDING_MULTIMODAL
    }

    public enum State { UP, DEGRADED, DOWN, UNKNOWN }

    public enum FeatureImpact { CORE, OPTIONAL_FEATURE, SECURITY_CONTROL }

    public enum SafeReasonClass { INITIAL, RECOVERED, DEGRADED, UNAVAILABLE, CONFIGURATION, UNKNOWN }

    public record Status(Dependency dependency, State state, FeatureImpact featureImpact,
            SafeReasonClass safeReasonClass, Instant changedAt) {
    }

    private final Clock clock;
    private final Map<Dependency, Status> statuses = new EnumMap<>(Dependency.class);

    public DependencyHealthTracker(Clock clock) {
        this.clock = clock;
        Instant initializedAt = clock.instant();
        for (Dependency dependency : Dependency.values()) {
            statuses.put(dependency, new Status(dependency, State.UNKNOWN,
                    dependency == Dependency.POSTGRESQL ? FeatureImpact.CORE
                            : FeatureImpact.OPTIONAL_FEATURE,
                    SafeReasonClass.INITIAL, initializedAt));
        }
    }

    public synchronized Status status(Dependency dependency) {
        return statuses.get(Objects.requireNonNull(dependency));
    }

    public synchronized Status transition(Dependency dependency, State state,
            FeatureImpact featureImpact, SafeReasonClass safeReasonClass) {
        Objects.requireNonNull(dependency);
        Objects.requireNonNull(state);
        Objects.requireNonNull(featureImpact);
        Objects.requireNonNull(safeReasonClass);
        if ((dependency == Dependency.POSTGRESQL && featureImpact != FeatureImpact.CORE)
                || (dependency != Dependency.POSTGRESQL && featureImpact == FeatureImpact.CORE)) {
            throw new IllegalArgumentException("Invalid bounded dependency impact");
        }
        Status prior = statuses.get(dependency);
        if (prior.state() == state) {
            if (prior.featureImpact() == featureImpact
                    && prior.safeReasonClass() == safeReasonClass) {
                return prior;
            }
            Status updated = new Status(dependency, state, featureImpact, safeReasonClass,
                    clock.instant());
            statuses.put(dependency, updated);
            return updated;
        }
        Status current = new Status(dependency, state, featureImpact, safeReasonClass,
                clock.instant());
        statuses.put(dependency, current);
        LOGGER.atInfo()
                .addKeyValue("event.name", "dependency.state_change")
                .addKeyValue("dependency", dependency.name())
                .addKeyValue("priorState", prior.state().name())
                .addKeyValue("currentState", state.name())
                .addKeyValue("featureImpact", featureImpact.name())
                .addKeyValue("safeReasonClass", safeReasonClass.name())
                .log("dependency.state_change");
        return current;
    }
}
