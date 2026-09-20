package org.notesknowledge.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("DATABASE")
@Testcontainers
class SpringSessionRuntimeRoleTest {

    private static final String IMAGE = "pgvector/pgvector:0.8.6-pg18-trixie";
    private static final String RUNTIME_USER = "session_runtime";
    private static final String RUNTIME_PASSWORD = "synthetic-runtime-password";

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(IMAGE)
            .withDatabaseName("session_privileges")
            .withUsername("session_migrator")
            .withPassword("synthetic-migrator-password");

    @Test
    void runtimeRoleHasDmlWithoutDdlAuthority() {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        JdbcTemplate migrator = new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        migrator.execute("""
                create role session_runtime
                login password 'synthetic-runtime-password'
                nosuperuser nocreatedb nocreaterole noinherit
                """);
        migrator.execute("grant connect on database session_privileges to session_runtime");
        migrator.execute("grant usage on schema identity to session_runtime");
        migrator.execute("""
                grant select, insert, update, delete
                on identity.spring_session, identity.spring_session_attributes
                to session_runtime
                """);

        var runtimeDataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), RUNTIME_USER, RUNTIME_PASSWORD);
        JdbcTemplate runtime = new JdbcTemplate(runtimeDataSource);

        assertThat(runtime.queryForObject(
                "select rolsuper from pg_roles where rolname = current_user",
                Boolean.class)).isFalse();
        assertThat(runtime.queryForObject(
                "select rolcreatedb or rolcreaterole from pg_roles where rolname = current_user",
                Boolean.class)).isFalse();
        assertThat(runtime.queryForObject(
                "select has_schema_privilege(current_user, 'identity', 'USAGE')",
                Boolean.class)).isTrue();
        assertThat(runtime.queryForObject(
                "select has_schema_privilege(current_user, 'identity', 'CREATE')",
                Boolean.class)).isFalse();
        assertThat(runtime.queryForObject(
                "select has_table_privilege(current_user, 'identity.spring_session', 'SELECT,INSERT,UPDATE,DELETE')",
                Boolean.class)).isTrue();
        assertThat(runtime.queryForObject(
                "select has_table_privilege(current_user, 'identity.spring_session_attributes', 'SELECT,INSERT,UPDATE,DELETE')",
                Boolean.class)).isTrue();
        assertThat(runtime.queryForObject(
                "select has_table_privilege(current_user, 'public.flyway_schema_history', 'SELECT')",
                Boolean.class)).isFalse();

        assertInsufficientPrivilege(() -> runtime.execute(
                "create table identity.forbidden_probe(id integer)"));
        assertInsufficientPrivilege(() -> runtime.execute(
                "create schema forbidden_probe"));
        assertInsufficientPrivilege(() -> runtime.execute(
                "alter table identity.spring_session add column forbidden_probe integer"));
        assertInsufficientPrivilege(() -> runtime.execute(
                "drop table identity.spring_session"));

        var transactionManager = new DataSourceTransactionManager(runtimeDataSource);
        var sessions = new JdbcIndexedSessionRepository(
                runtime, new TransactionTemplate(transactionManager));
        sessions.setTableName("identity.spring_session");
        sessions.afterPropertiesSet();
        try {
            SessionRepository<Session> repository = publicRepository(sessions);
            Session session = repository.createSession();
            session.setAttribute("syntheticAttribute", "runtime-bounded-value");
            repository.save(session);

            assertThat(repository.findById(session.getId()))
                    .isNotNull()
                    .extracting(found -> found.<String>getAttribute("syntheticAttribute"))
                    .isEqualTo("runtime-bounded-value");

            repository.deleteById(session.getId());
            assertThat(repository.findById(session.getId())).isNull();
        }
        finally {
            sessions.destroy();
        }
    }

    @SuppressWarnings("unchecked")
    private SessionRepository<Session> publicRepository(
            JdbcIndexedSessionRepository repository) {
        return (SessionRepository<Session>) (SessionRepository<?>) repository;
    }

    private void assertInsufficientPrivilege(Runnable statement) {
        Throwable failure = catchThrowable(statement::run);
        assertThat(failure).isNotNull();
        Throwable rootCause = failure;
        while (rootCause.getCause() != null) {
            rootCause = rootCause.getCause();
        }
        assertThat(rootCause).isInstanceOfSatisfying(PSQLException.class,
                exception -> assertThat(exception.getSQLState()).isEqualTo("42501"));
    }
}
