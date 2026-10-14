package org.notesknowledge.notes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Supported Linux profile: a bounded cgroup-v2 container, not an unbounded
 * host service. The unprivileged backend cannot raise these operator limits. */
final class MediaContainerBudget {
    private MediaContainerBudget() { }
    static void require() throws IOException {
        try {
            if (!"0::/".equals(read(Path.of("/proc/self/cgroup")).strip())
                    || !bounded(read(Path.of("/sys/fs/cgroup/memory.max")),
                                read(Path.of("/sys/fs/cgroup/pids.max")),
                                read(Path.of("/sys/fs/cgroup/cpu.max")))) throw unavailable();
        } catch (IOException | RuntimeException invalid) { throw unavailable(); }
    }
    static boolean bounded(String memory, String pids, String cpu) {
        try {
            long bytes = positive(memory), tasks = positive(pids);
            String[] parts = cpu.strip().split("\\s+");
            if (parts.length != 2) return false;
            long quota = positive(parts[0]), period = positive(parts[1]);
            return bytes <= 2_147_483_648L && tasks <= 512 && quota <= 2 * period;
        } catch (RuntimeException invalid) { return false; }
    }
    private static long positive(String value) {
        String text = value.strip();
        if (!text.matches("[1-9][0-9]{0,11}")) throw new IllegalArgumentException("Invalid resource ceiling");
        return Long.parseLong(text);
    }
    private static String read(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) throw unavailable();
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(129);
            if (bytes.length == 129) throw unavailable();
            return new String(bytes, java.nio.charset.StandardCharsets.US_ASCII);
        }
    }
    private static IOException unavailable() { return new IOException("Media container resource budget unavailable"); }
}
