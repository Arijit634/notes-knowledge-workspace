package org.notesknowledge;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Arrays;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Startup-qualified schema truth; the live datasource indicator handles later DB outages. */
@Component("coreSchema")
final class CoreSchemaHealthIndicator implements HealthIndicator {

    private final ObjectProvider<Flyway> flyway;
    private final ObjectProvider<JdbcClient> jdbc;
    private final AtomicBoolean qualified = new AtomicBoolean();

    CoreSchemaHealthIndicator(ObjectProvider<Flyway> flyway, ObjectProvider<JdbcClient> jdbc) {
        this.flyway = flyway;
        this.jdbc = jdbc;
    }

    @EventListener
    void qualifyAtStartup(ApplicationReadyEvent event) {
        Flyway migrationAuthority = flyway.getIfAvailable();
        JdbcClient runtimeJdbc = jdbc.getIfAvailable();
        if (migrationAuthority == null || runtimeJdbc == null) {
            return;
        }

        if (!migrationAuthority.validateWithResult().validationSuccessful
                || !Arrays.stream(migrationAuthority.info().applied())
                        .anyMatch(migration -> "V001__platform__spring_session.sql"
                                .equals(migration.getScript()))
                || migrationAuthority.info().pending().length != 0) {
            return;
        }
        Boolean vectorAvailable = runtimeJdbc.sql("""
                select exists (
                    select 1 from pg_available_extensions
                    where name = 'vector' and default_version = '0.8.6'
                )
                """).query(Boolean.class).single();
        qualified.set(Boolean.TRUE.equals(vectorAvailable));
    }

    @Override
    public Health health() {
        return qualified.get() ? Health.up().build() : Health.down().build();
    }
}
