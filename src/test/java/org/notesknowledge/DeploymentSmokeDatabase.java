package org.notesknowledge;

import java.sql.*;
import org.flywaydb.core.Flyway;

/** Disposable fixture/migration preparation outside the production application.
 * Uses the packaged migration resources. No user-input SQL, real credentials,
 * Spring test context or test-only application beans. */
public final class DeploymentSmokeDatabase {
    public static void main(String[] args)throws Exception {
        if(args.length!=1||!args[0].equals("initialize"))throw new IllegalArgumentException("Explicit synthetic initialization required");
        String url="jdbc:postgresql://database:5432/deployment_smoke";
        try{
            Flyway.configure().dataSource(url,"synthetic_migrator","synthetic-migration-password").load().migrate();
            try(var connection=DriverManager.getConnection(url,"synthetic_migrator","synthetic-migration-password");var sql=connection.createStatement()){
                sql.execute("create role synthetic_runtime login password 'synthetic-runtime-password' nosuperuser nocreatedb nocreaterole noreplication");
                sql.execute("revoke create on schema public from public");
                sql.execute("grant usage on schema public to synthetic_runtime");
                sql.execute("grant select on public.flyway_schema_history to synthetic_runtime");
                for(String schema:java.util.List.of("identity","profile","notes","knowledge","publishing","discovery","moderation")){
                    sql.execute("grant usage on schema "+schema+" to synthetic_runtime");
                    sql.execute("grant select,insert,update,delete on all tables in schema "+schema+" to synthetic_runtime");
                    sql.execute("grant usage,select on all sequences in schema "+schema+" to synthetic_runtime");
                }
                try(var rows=sql.executeQuery("select count(*) from information_schema.tables where table_schema in ('identity','profile','notes','knowledge','publishing','discovery','moderation') and table_type='BASE TABLE'")){
                    if(!rows.next()||rows.getInt(1)!=38)throw new IllegalStateException();
                }
            }
            System.out.println("deployment_smoke_database_ready_relations_38");
        }catch(Exception failure){throw new IllegalStateException("Synthetic migration/role preparation failed");}
    }
}
