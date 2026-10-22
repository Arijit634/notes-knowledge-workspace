package org.notesknowledge.knowledge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Keeps offline restart probes below the Windows process command-line limit. */
final class QualificationChildClasspath {
    private QualificationChildClasspath() { }

    static String argument(Path ownedTestDirectory) throws IOException {
        String classpath = System.getProperty("java.class.path");
        if (classpath.indexOf('\n') >= 0 || classpath.indexOf('\r') >= 0) {
            throw new IOException("Invalid test classpath");
        }
        Path arguments = Files.createTempFile(ownedTestDirectory, "child-classpath-", ".args");
        // Java launcher argument files quote spaces and escape Windows separators.
        // Only the trusted test classpath is written; never probe data or credentials.
        Files.writeString(arguments, "-cp\n\"" + classpath.replace("\\", "\\\\").replace("\"", "\\\"")
                + "\"\n", StandardCharsets.UTF_8);
        return "@" + arguments.toAbsolutePath();
    }
}
