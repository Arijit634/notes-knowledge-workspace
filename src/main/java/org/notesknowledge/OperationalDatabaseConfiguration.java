package org.notesknowledge;

import org.flywaydb.core.Flyway;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The service validates migration history with its runtime identity; it never migrates with DDL credentials. */
@Configuration(proxyBeanMethods=false)
@Profile("operations")
class OperationalDatabaseConfiguration {
    OperationalDatabaseConfiguration(org.springframework.core.env.Environment environment){
        if(environment.getProperty("spring.flyway.enabled",Boolean.class,true))
            throw new IllegalStateException("Operations runtime must use separately migrated schema");
    }
    @Bean
    @ConditionalOnProperty(name="spring.flyway.enabled",havingValue="false")
    Flyway readOnlySchemaValidation(javax.sql.DataSource datasource){
        requireRuntimeRole(JdbcClient.create(datasource));
        return Flyway.configure().dataSource(datasource).load();
    }

    @Bean ApplicationRunner verifyRuntimeRole(JdbcClient jdbc){return args->requireRuntimeRole(jdbc);}

    static void requireRuntimeRole(JdbcClient jdbc){
        boolean privileged=jdbc.sql("select rolsuper or rolcreatedb or rolcreaterole or rolreplication or rolbypassrls from pg_roles where rolname=current_user").query(Boolean.class).single();
        for(String schema:java.util.List.of("public","identity","profile","notes","knowledge","publishing","discovery","moderation"))
            privileged|=jdbc.sql("select has_schema_privilege(current_user,:schema,'CREATE')").param("schema",schema).query(Boolean.class).single();
        for(String table:java.util.List.of("identity.security_audit_fact","publishing.publication_audit_fact","moderation.moderation_decision","moderation.moderation_audit_fact"))
            privileged|=jdbc.sql("select has_table_privilege(current_user,:relation,'UPDATE,DELETE,TRUNCATE')").param("relation",table).query(Boolean.class).single();
        privileged|=jdbc.sql("select has_table_privilege(current_user,'notes.note_version','UPDATE,TRUNCATE')").query(Boolean.class).single();
        if(privileged)throw new IllegalStateException("Restricted runtime database role required");
    }
}
