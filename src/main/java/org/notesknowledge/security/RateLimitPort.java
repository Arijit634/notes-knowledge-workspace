package org.notesknowledge.security;

import java.util.Objects;

/** A backing-neutral abuse-control decision; implementations must not expose backend errors. */
public interface RateLimitPort {

    Decision evaluate(Request request);

    /** A server-owned, low-cardinality control class, never a request path or input value. */
    record ControlClass(String value) {
        public ControlClass {
            if (value == null || !value.matches("[A-Z][A-Z0-9_]{0,47}")) {
                throw new IllegalArgumentException("Invalid rate-control class");
            }
        }
    }

    /** The key is already safely derived by its caller; this type does not derive identity. */
    final class OpaqueKey {
        private final String value;

        public OpaqueKey(String value) {
            if (value == null || !value.matches("[A-Za-z0-9_-]{16,128}")) {
                throw new IllegalArgumentException("Invalid opaque rate-control key");
            }
            this.value = value;
        }

        public String value() {
            return value;
        }

        @Override
        public String toString() {
            return "OpaqueKey[REDACTED]";
        }
    }

    record Request(ControlClass controlClass, OpaqueKey enforcementKey, int cost) {
        public Request {
            Objects.requireNonNull(controlClass, "controlClass");
            Objects.requireNonNull(enforcementKey, "enforcementKey");
            if (cost < 1 || cost > 1_000_000) {
                throw new IllegalArgumentException("Invalid rate-control cost");
            }
        }
    }

    sealed interface Decision permits Allowed, Throttled, ControlUnavailable {
    }

    record Allowed() implements Decision {
    }

    record Throttled(int retryAfterSeconds) implements Decision {
        public Throttled {
            if (retryAfterSeconds < 1 || retryAfterSeconds > 86_400) {
                throw new IllegalArgumentException("Invalid Retry-After interval");
            }
        }
    }

    record ControlUnavailable() implements Decision {
    }
}
