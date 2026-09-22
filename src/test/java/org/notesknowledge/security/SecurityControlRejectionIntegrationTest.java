package org.notesknowledge.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("SECURITY")
@Testcontainers
@SpringBootTest(properties = "notes.synthetic-rejection-test=true")
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class SecurityControlRejectionIntegrationTest {

    @Container
    static final PostgreSQLContainer postgres =
            new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18-trixie")
                    .withDatabaseName("security_rejection_foundation")
                    .withUsername("synthetic_migrator")
                    .withPassword("synthetic-migrator-password");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    MockMvc mockMvc;

    @Test
    void actualCsrfAndAuthorizationDenialsEmitOnlyFiniteSafeFields(CapturedOutput output)
            throws Exception {
        mockMvc.perform(post("/api/synthetic-private-path-marker")
                        .queryParam("private", "synthetic-private-query-marker")
                        .header("X-CSRF-TOKEN", "synthetic-secret-csrf-marker")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("synthetic-private-body-marker"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("csrf_invalid"));

        mockMvc.perform(get("/api/synthetic-private-path-marker")
                        .with(user("synthetic-user"))
                        .queryParam("private", "synthetic-private-query-marker")
                        .header("Authorization", "Bearer synthetic-secret-auth-marker"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("access_denied"));

        List<String> events = output.getAll().lines()
                .filter(line -> line.contains("\"logger\":\"org.notesknowledge.security.SecurityControlRejectionEvents\""))
                .toList();
        assertThat(events).hasSize(2);
        assertThat(events.get(0)).contains("security.control.rejected", "\"control\":\"CSRF\"",
                "\"safeReasonClass\":\"INVALID_CSRF\"");
        assertThat(events.get(1)).contains("security.control.rejected",
                "\"control\":\"AUTHORIZATION\"",
                "\"safeReasonClass\":\"ACCESS_DENIED\"");
        assertThat(String.join("\n", events)).doesNotContain(
                "synthetic-private-path-marker", "synthetic-private-query-marker",
                "synthetic-private-body-marker", "synthetic-secret-csrf-marker",
                "synthetic-secret-auth-marker", "synthetic-user");
    }
}
