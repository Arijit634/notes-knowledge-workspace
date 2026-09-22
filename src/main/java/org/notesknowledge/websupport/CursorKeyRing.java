package org.notesknowledge.websupport;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/** External key-source boundary. No production key source is selected by this package. */
public interface CursorKeyRing {

    KeySnapshot keys();

    /** AES-256 material is copied and never exposed through a public accessor or toString. */
    final class CursorKey {
        private final String version;
        private final byte[] material;

        public CursorKey(String version, byte[] material) {
            if (!validVersion(version) || material == null || material.length != 32) {
                throw new IllegalArgumentException("Invalid cursor key definition");
            }
            this.version = version;
            this.material = material.clone();
        }

        public String version() {
            return version;
        }

        SecretKey secretKey() {
            return new SecretKeySpec(material, "AES");
        }

        @Override
        public String toString() {
            return "CursorKey[REDACTED]";
        }
    }

    /** One active key and no more than two retained, distinct decode-only versions. */
    record KeySnapshot(CursorKey active, List<CursorKey> previous) {
        public KeySnapshot {
            Objects.requireNonNull(active, "active");
            previous = List.copyOf(Objects.requireNonNull(previous, "previous"));
            if (previous.size() > 2) {
                throw new IllegalArgumentException("Too many previous cursor keys");
            }
            Set<String> versions = new HashSet<>();
            versions.add(active.version());
            for (CursorKey key : previous) {
                if (!versions.add(key.version())) {
                    throw new IllegalArgumentException("Duplicate cursor key version");
                }
            }
        }

        Optional<CursorKey> accepted(String version) {
            if (active.version().equals(version)) {
                return Optional.of(active);
            }
            return previous.stream().filter(key -> key.version().equals(version)).findFirst();
        }
    }

    static boolean validVersion(String version) {
        return version != null && version.matches("[A-Za-z0-9_-]{1,16}");
    }
}
