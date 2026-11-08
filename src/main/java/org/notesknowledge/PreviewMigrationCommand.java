package org.notesknowledge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.Properties;
import org.flywaydb.core.Flyway;

/** Explicit operator job with separate credentials; never called by application startup. */
public final class PreviewMigrationCommand {
    private PreviewMigrationCommand() {}
    public static void main(String[] arguments) {
        try {
            if (arguments.length != 1) throw new IllegalArgumentException();
            var p=new Properties();
            try(var input=Files.newInputStream(Path.of(arguments[0]))) { p.load(input); }
            if (!"jdbc:postgresql://postgres:5432/nkw_preview".equals(p.getProperty("url"))
                    || !"nkw_preview_migrator".equals(p.getProperty("user"))
                    || !p.getProperty("runtimePassword", "").matches("[A-Za-z0-9+/]{43}=")) throw new IllegalArgumentException();
            var flyway=Flyway.configure().dataSource(p.getProperty("url"),p.getProperty("user"),p.getProperty("password")).load();
            flyway.migrate();
            try(var connection=DriverManager.getConnection(p.getProperty("url"),p.getProperty("user"),p.getProperty("password"))) {
                grantRuntime(connection, p.getProperty("runtimePassword"));
            }
            System.out.println("private_preview_migration_complete");
        } catch (Exception unavailable) {
            System.err.println("private_preview_migration_failed; state retained; raw diagnostics withheld");
            System.exit(2);
        }
    }

    static void grantRuntime(java.sql.Connection connection, String runtimePassword) throws Exception {
        if (!runtimePassword.matches("[A-Za-z0-9+/]{43}=")) throw new IllegalArgumentException();
        try (var statement=connection.createStatement()) {
                boolean create;
                try(var exists=statement.executeQuery("select count(*) from pg_roles where rolname='nkw_preview_runtime'")) {
                    exists.next();
                    create=exists.getInt(1)==0;
                }
                if(create) statement.execute("create role nkw_preview_runtime login password '"
                    + runtimePassword + "' nosuperuser nocreatedb nocreaterole noreplication nobypassrls");
                String grants;
                try(var input=PreviewMigrationCommand.class.getResourceAsStream("/operations/runtime-role.sql")) {
                    grants=new String(input.readAllBytes(),StandardCharsets.UTF_8).replace(":\"runtime_role\"","\"nkw_preview_runtime\"");
                }
                statement.execute(grants);
                statement.execute("revoke connect on database nkw_preview from public");
                statement.execute("grant connect on database nkw_preview to nkw_preview_runtime");
        }
    }
}
