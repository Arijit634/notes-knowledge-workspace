package org.notesknowledge;

import java.util.Objects;
import java.util.UUID;

/** Opaque fencing value, not identity, permission, or proof of authorization. */
public final class LeaseToken {

    private final UUID value;

    private LeaseToken(UUID value) {
        this.value = Objects.requireNonNull(value, "value");
        if (value.version() != 7) {
            throw new IllegalArgumentException("A database-generated UUIDv7 lease token is required");
        }
    }

    /** Accepts the fresh value returned by an owner module's conditional PostgreSQL claim. */
    public static LeaseToken fromDatabase(UUID value) {
        return new LeaseToken(value);
    }

    public UUID value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof LeaseToken token && value.equals(token.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return "LeaseToken[REDACTED]";
    }
}
