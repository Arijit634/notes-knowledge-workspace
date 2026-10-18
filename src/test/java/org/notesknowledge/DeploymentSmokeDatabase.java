package org.notesknowledge;

import java.sql.*;
import org.flywaydb.core.Flyway;

/** Disposable fixture/migration preparation outside the production application.
 * Uses the packaged migration resources. No user-input SQL, real credentials,
 * Spring test context or test-only application beans. */
public final class DeploymentSmokeDatabase {
    public static void main(String[] args)throws Exception {
        if(args.length==1&&args[0].equals("metrics")){
            var client=java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(3)).build();
            var response=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:8081/actuator/prometheus"))
                .timeout(java.time.Duration.ofSeconds(5)).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200)throw new IllegalStateException("Restricted metric probe failed");
            for(String module:java.util.List.of("identity","knowledge"))
                if(!java.util.regex.Pattern.compile("(?m)^notes_workspace_telemetry_available\\{[^}]*module=\\\""+module+"\\\"[^}]*\\} +1(?:\\.0)?(?: +[^\\n]*)?$")
                        .matcher(response.body()).find())throw new IllegalStateException("Owner queue telemetry unavailable");
            var names=new java.util.TreeSet<String>();
            for(String line:response.body().split("\n")){
                var match=java.util.regex.Pattern.compile("^([A-Za-z_:][A-Za-z0-9_:]*)[ {]").matcher(line);
                if(match.find())names.add(match.group(1));
            }
            System.out.println(new tools.jackson.databind.ObjectMapper().writeValueAsString(names));return;
        }
        if(args.length!=1||!args[0].equals("initialize"))throw new IllegalArgumentException("Explicit synthetic initialization required");
        String url="jdbc:postgresql://database:5432/deployment_smoke";
        try{
            Flyway.configure().dataSource(url,"synthetic_migrator","synthetic-migration-password").load().migrate();
            try(var connection=DriverManager.getConnection(url,"synthetic_migrator","synthetic-migration-password");var sql=connection.createStatement()){
                sql.execute("create role synthetic_runtime login password 'synthetic-runtime-password' nosuperuser nocreatedb nocreaterole noreplication");
                try(var resource=DeploymentSmokeDatabase.class.getResourceAsStream("/operations/runtime-role.sql")){
                    sql.execute(new String(resource.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).replace(":\"runtime_role\"","synthetic_runtime"));
                }
                try(var rows=sql.executeQuery("select count(*) from information_schema.tables where table_schema in ('identity','profile','notes','knowledge','publishing','discovery','moderation') and table_type='BASE TABLE'")){
                    if(!rows.next()||rows.getInt(1)!=38)throw new IllegalStateException();
                }
            }
            System.out.println("deployment_smoke_database_ready_relations_38");
        }catch(Exception failure){throw new IllegalStateException("Synthetic migration/role preparation failed");}
    }
}
