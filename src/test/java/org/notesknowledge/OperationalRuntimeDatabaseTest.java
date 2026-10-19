package org.notesknowledge;

import java.net.*;
import java.net.http.*;
import java.sql.DriverManager;
import java.nio.charset.StandardCharsets;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.*;

@Tag("DATABASE") @Tag("SECURITY") @Tag("API")
@Testcontainers
@ActiveProfiles({"test","operations"})
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "identity.core.enabled=false","knowledge.operations.scheduling-enabled=false","knowledge.derivation.scheduling-enabled=false",
    "knowledge.public.scheduling-enabled=false","operations.telemetry.enabled=false"})
class OperationalRuntimeDatabaseTest {
    @Container static final PostgreSQLContainer DB=new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18-trixie");
    static final int MANAGEMENT=port();
    static int port(){try(var socket=new java.net.ServerSocket(0,1,InetAddress.getLoopbackAddress())){return socket.getLocalPort();}catch(Exception failure){throw new IllegalStateException(failure);}}
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties)throws Exception{
        Flyway.configure().dataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword()).load().migrate();
        try(var connection=DriverManager.getConnection(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword());var sql=connection.createStatement()){
            sql.execute("create role synthetic_runtime login password 'synthetic-runtime-password' nosuperuser nocreatedb nocreaterole noreplication");
            try(var input=OperationalRuntimeDatabaseTest.class.getResourceAsStream("/operations/runtime-role.sql")){
                sql.execute(new String(input.readAllBytes(),StandardCharsets.UTF_8).replace(":\"runtime_role\"","synthetic_runtime"));
            }
        }
        properties.add("spring.datasource.url",DB::getJdbcUrl);properties.add("spring.datasource.username",()->"synthetic_runtime");
        properties.add("spring.datasource.password",()->"synthetic-runtime-password");properties.add("management.server.port",()->MANAGEMENT);
    }
    @Autowired Environment environment;@Autowired JdbcClient jdbc;@Autowired CoreSchemaHealthIndicator schema;
    final HttpClient client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    HttpResponse<String> get(int port,String path)throws Exception{return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(java.time.Duration.ofSeconds(5)).GET().build(),HttpResponse.BodyHandlers.ofString());}
    @Test void actualManagementListenerExportsOnlyLoopbackReadOnlyEndpoints()throws Exception{
        var metrics=get(MANAGEMENT,"/actuator/prometheus");assertThat(metrics.statusCode()).isEqualTo(200);
        assertThat(metrics.body()).contains("jvm_memory_used_bytes").doesNotContain("synthetic-runtime-password");
        assertThat(get(environment.getProperty("local.server.port",Integer.class),"/actuator/prometheus").statusCode()).isIn(401,403,404);
        assertThat(get(MANAGEMENT,"/actuator/env").statusCode()).isIn(401,403,404);
        assertThat(get(MANAGEMENT,"/api/notes").statusCode()).isIn(401,403,404);
        var unsafe=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+MANAGEMENT+"/actuator/prometheus")).POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.discarding());
        assertThat(unsafe.statusCode()).isIn(401,403,404,405);
    }
    @Test void restrictedRuntimeValidatesFlywayWithoutMigrationCredentialsAndCannotRewriteEvidence()throws Exception{
        assertThat(schema.health().getStatus().getCode()).isEqualTo("UP");OperationalDatabaseConfiguration.requireRuntimeRole(jdbc);
        assertThatThrownBy(()->jdbc.sql("update identity.security_audit_fact set outcome_code='changed'").update()).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->jdbc.sql("delete from moderation.moderation_decision").update()).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->jdbc.sql("update notes.note_version set title='changed'").update()).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->jdbc.sql("create table notes.unauthorized_probe(id integer)").update()).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void privilegedRoleIsRejectedInsteadOfBeingUsedByTheOperationsProfile(){
        var admin=new org.springframework.jdbc.datasource.DriverManagerDataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword());
        assertThatThrownBy(()->OperationalDatabaseConfiguration.requireRuntimeRole(JdbcClient.create(admin))).isInstanceOf(IllegalStateException.class);
    }
    @Test void indirectFileOrProcessAuthorityIsNotMistakenForOrdinaryRuntimeDml()throws Exception{
        try(var connection=DriverManager.getConnection(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword());var sql=connection.createStatement()){
            sql.execute("grant pg_execute_server_program to synthetic_runtime");
            try{assertThatThrownBy(()->OperationalDatabaseConfiguration.requireRuntimeRole(jdbc)).isInstanceOf(IllegalStateException.class);}
            finally{sql.execute("revoke pg_execute_server_program from synthetic_runtime");}
        }
        OperationalDatabaseConfiguration.requireRuntimeRole(jdbc);
    }
}
