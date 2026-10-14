package org.notesknowledge.notes;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Operator-owned deployment selection, not source or provider authority. */
@ConfigurationProperties("notes.media-runtime")
record MediaRuntimeProperties(Selection pdf, Selection video) {
    record Selection(boolean enabled, String profile, Path root, Path recoveryDirectory,
            Path scratchDirectory, String artifactIdentity, String version) {
        void requireLinuxProfile() {
            if (!"linux-confined-v1".equals(profile)
                    || !"Linux".equals(System.getProperty("os.name"))
                    || !java.util.Set.of("amd64", "aarch64").contains(System.getProperty("os.arch"))
                    || !absolute(root) || !absolute(recoveryDirectory)
                    || artifactIdentity == null || !artifactIdentity.matches("[0-9a-f]{64}")
                    || version == null || !version.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")) {
                throw new IllegalStateException("Media runtime deployment configuration unavailable");
            }
        }
        private static boolean absolute(Path path) {
            return path != null && path.isAbsolute() && path.equals(path.normalize());
        }
        void requireVideoScratch() {
            if (!absolute(scratchDirectory)) {
                throw new IllegalStateException("Media runtime deployment configuration unavailable");
            }
        }
        @Override public String toString() { return "MediaRuntimeSelection[REDACTED]"; }
    }
    @Override public String toString() { return "MediaRuntimeProperties[REDACTED]"; }
}
