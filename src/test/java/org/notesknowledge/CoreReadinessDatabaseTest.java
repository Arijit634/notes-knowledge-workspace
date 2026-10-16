package org.notesknowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("DATABASE")
@Testcontainers
@SpringBootTest(properties = "spring.datasource.hikari.connection-timeout=1000")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class CoreReadinessDatabaseTest {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            "pgvector/pgvector:0.8.6-pg18-trixie")
            .withDatabaseName("readiness")
            .withUsername("readiness_migrator")
            .withPassword("synthetic-readiness-password");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    MockMvc mockMvc;

    @Test
    void databaseOutageMakesReadinessDownButKeepsLivenessUp() throws Exception {
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"UP\"}"));
        var bootstrap = mockMvc.perform(get("/api/auth/csrf").secure(true))
                .andExpect(status().isOk()).andReturn();
        var sessionCookie = bootstrap.getResponse().getCookie("SESSION");
        assertThat(sessionCookie).isNotNull();

        postgres.stop();

        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().json("{\"status\":\"DOWN\"}"));
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"UP\"}"));
        var denied = mockMvc.perform(get("/api/auth/session").secure(true).cookie(sessionCookie))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("service_unavailable"))
                .andExpect(jsonPath("$.traceId").isString()).andReturn();
        assertThat(denied.getResponse().getContentAsString()).doesNotContain(
                sessionCookie.getValue(), "jdbc:", "password", "Exception", "select ");
    }
}
