package org.notesknowledge.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("FAST") @Tag("SECURITY")
class QualificationChildClasspathTest {
    @TempDir Path directory;

    @Test void longClasspathRunsWithSpacesAndUnicodeWithoutExpandingTheCommandLine() throws Exception {
        String original = System.getProperty("java.class.path");
        Path owned = Files.createDirectory(directory.resolve("synthetic space café"));
        // An actual child launch, not merely an assertion about serialized text.
        String padding = owned.resolve("unused dependency.jar").toString() + File.pathSeparator;
        String argument;
        try {
            System.setProperty("java.class.path", padding.repeat(800) + original);
            argument = QualificationChildClasspath.argument(owned);
        } finally {
            System.setProperty("java.class.path", original);
        }
        assertThat(argument.length()).isLessThan(1000);
        var child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                argument, Probe.class.getName()).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            assertThat(child.waitFor(30, TimeUnit.SECONDS)).isTrue();
            assertThat(child.exitValue()).isZero();
        } finally {
            if (child.isAlive()) child.destroyForcibly();
        }
    }

    public static final class Probe {
        public static void main(String[] args) {
            if (args.length != 0) throw new IllegalStateException("Unexpected probe arguments");
        }
    }
}
