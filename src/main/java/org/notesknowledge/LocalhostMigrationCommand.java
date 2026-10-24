package org.notesknowledge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.flywaydb.core.Flyway;

/** Explicit separate local migration operation. Never launched by the application runtime. */
public final class LocalhostMigrationCommand {
    private LocalhostMigrationCommand() { }
    public static void main(String[] arguments) {
        try {
            if (arguments.length != 1) throw new IllegalArgumentException();
            var properties = new Properties();
            try (var input = Files.newInputStream(Path.of(arguments[0]))) { properties.load(input); }
            String url = properties.getProperty("spring.flyway.url");
            if (url == null || !url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]{4,5}/nkw_owner_local")) throw new IllegalArgumentException();
            // Credential-bearing driver exceptions must never reach the console.
            var flyway = Flyway.configure().dataSource(url, properties.getProperty("spring.flyway.user"),
                    properties.getProperty("spring.flyway.password")).load();
            flyway.migrate(); flyway.validate();
            System.out.println("Local migration and validation complete");
        } catch (Exception failure) {
            String category = failure instanceof IllegalArgumentException ? "configuration" : "database";
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof java.sql.SQLException sql && sql.getSQLState() != null && sql.getSQLState().matches("[0-9A-Z]{5}")) {
                    category = "sql_state_" + sql.getSQLState(); break;
                }
                if (cause instanceof java.net.ConnectException || cause instanceof java.net.SocketTimeoutException) category = "connectivity";
            }
            System.err.println("Local database migration unavailable: " + category);
            System.exit(1);
        }
    }
}
