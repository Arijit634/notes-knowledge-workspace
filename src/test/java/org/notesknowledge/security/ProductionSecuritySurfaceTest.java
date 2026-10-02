package org.notesknowledge.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

@Tag("SECURITY")
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
        "management.endpoint.health.validate-group-membership=false",
        "identity.core.enabled=false"
})
@AutoConfigureMockMvc
class ProductionSecuritySurfaceTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping requestMappings;

    @Test
    void productionContextContainsOnlyAuthorizedNotesAndNoSecurityProbeController() {
        Set<String> paths = requestMappings.getHandlerMethods().keySet().stream()
                .flatMap(mapping -> mapping.getPatternValues().stream())
                .collect(Collectors.toSet());

        assertThat(paths)
                .noneMatch(path -> path.startsWith("/__security-probe"))
                .noneMatch(path -> path.startsWith("/__api-probe"));
        assertThat(paths.stream().filter(path -> path.startsWith("/api/"))).containsExactlyInAnyOrder(
                "/api/me/note-preferences", "/api/notes", "/api/notes/{noteId}",
                "/api/notes/{noteId}/tags", "/api/notes/{noteId}/pin",
                "/api/notes/{noteId}/archive", "/api/notes/{noteId}/return-from-archive",
                "/api/notes/{noteId}/trash", "/api/notes/{noteId}/restore",
                "/api/notes/{noteId}/ai-access", "/api/notes/ai-access-bulk",
                "/api/notes/{noteId}/versions", "/api/notes/{noteId}/versions/{versionId}",
                "/api/notes/{noteId}/versions/{versionId}/restore");
    }

    @Test
    void productionChainHasNoFrameworkLoginOrBasicChallenge() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE));

        mockMvc.perform(get("/api/not-implemented")
                        .header(HttpHeaders.AUTHORIZATION,
                                syntheticBasicAuthorization()))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE));
    }

    private String syntheticBasicAuthorization() {
        String value = "synthetic-user:synthetic-password";
        return "Basic " + Base64.getEncoder()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
