package org.notesknowledge.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("DATABASE")
@Testcontainers
@SpringBootTest
class SpringSessionRepositoryTest {

    private static final String IMAGE = "pgvector/pgvector:0.8.6-pg18-trixie";

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(IMAGE)
            .withDatabaseName("session_repository")
            .withUsername("session_migrator")
            .withPassword("synthetic-migrator-password");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    JdbcIndexedSessionRepository sessions;

    @Autowired
    Environment environment;

    @Test
    void persistsUpdatesFindsAndRevokesUsingConfiguredRepository() {
        assertThat(environment.getProperty("spring.session.jdbc.initialize-schema"))
                .isEqualTo("never");
        assertThat(environment.getProperty("spring.session.jdbc.table-name"))
                .isEqualTo("identity.spring_session");
        assertThat(ReflectionTestUtils.getField(sessions, "tableName"))
                .isEqualTo("identity.spring_session");

        FindByIndexNameSessionRepository<Session> repository = publicRepository(sessions);
        Session session = repository.createSession();
        session.setAttribute("syntheticAttribute", "bounded-value");
        session.setAttribute(
                FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME,
                "synthetic-principal");
        repository.save(session);

        Session reloaded = repository.findById(session.getId());
        assertThat(reloaded).isNotNull();
        assertThat(reloaded.<String>getAttribute("syntheticAttribute"))
                .isEqualTo("bounded-value");

        reloaded.setAttribute("syntheticAttribute", "updated-bounded-value");
        reloaded.setMaxInactiveInterval(Duration.ofMinutes(17));
        repository.save(reloaded);

        Session updated = repository.findById(session.getId());
        assertThat(updated).isNotNull();
        assertThat(updated.<String>getAttribute("syntheticAttribute"))
                .isEqualTo("updated-bounded-value");
        assertThat(updated.getMaxInactiveInterval()).isEqualTo(Duration.ofMinutes(17));

        assertThat(repository.findByIndexNameAndIndexValue(
                FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME,
                "synthetic-principal"))
                .containsKey(session.getId());

        repository.deleteById(session.getId());
        assertThat(repository.findById(session.getId())).isNull();
    }

    @SuppressWarnings("unchecked")
    private FindByIndexNameSessionRepository<Session> publicRepository(
            JdbcIndexedSessionRepository repository) {
        return (FindByIndexNameSessionRepository<Session>)
                (FindByIndexNameSessionRepository<?>) repository;
    }
}
