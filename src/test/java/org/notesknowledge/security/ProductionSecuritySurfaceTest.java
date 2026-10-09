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
                "/api/me/note-preferences", "/api/notes", "/api/notes/search", "/api/notes/{noteId}",
                "/api/notes/{noteId}/tags", "/api/notes/{noteId}/pin",
                "/api/notes/{noteId}/archive", "/api/notes/{noteId}/return-from-archive",
                "/api/notes/{noteId}/trash", "/api/notes/{noteId}/restore",
                "/api/notes/{noteId}/ai-access", "/api/notes/ai-access-bulk",
                "/api/notes/{noteId}/versions", "/api/notes/{noteId}/versions/{versionId}",
                "/api/notes/{noteId}/versions/{versionId}/restore", "/api/me/profile", "/api/me/profile/avatar",
                "/api/notes/{noteId}/attachments", "/api/notes/{noteId}/attachments/{attachmentId}",
                "/api/notes/{noteId}/attachments/{attachmentId}/content", "/api/ai/processing-policy", "/api/ai/processing-policy/acknowledgements", "/api/notes/{noteId}/ai-processing",
                "/api/knowledge/query", "/api/knowledge/operations/{operationId}",
                "/api/notes/{noteId}/related", "/api/notes/{noteId}/organization-suggestions",
                "/api/me/public-profile", "/api/public/profiles/{handle}", "/api/public/profiles/{handle}/publications",
                "/api/public/profiles/{handle}/avatar/{publicAvatarId}/content",
                "/api/notes/{noteId}/publication-preview", "/api/notes/{noteId}/publication",
                "/api/me/publications", "/api/me/publications/{id}", "/api/me/publications/{id}/source-status",
                "/api/me/publications/{id}/unpublish", "/api/me/publications/{id}/republish",
                "/api/public/publications/{id}", "/api/public/publications/{id}/media/{mediaId}/content",
                "/api/public/explore", "/api/public/search", "/api/public/publications/{id}/like",
                "/api/public/publications/{publicationId}/reports", "/api/moderation/reports", "/api/moderation/reports/{reportId}",
                "/api/moderation/reports/{reportId}/begin-review", "/api/moderation/reports/{reportId}/decisions");
        assertThat(requestMappings.getHandlerMethods().keySet().stream()
                .filter(mapping -> mapping.getPatternValues().contains("/api/public/publications/{id}/like"))
                .flatMap(mapping -> mapping.getMethodsCondition().getMethods().stream()))
                .containsExactlyInAnyOrder(org.springframework.web.bind.annotation.RequestMethod.PUT,
                        org.springframework.web.bind.annotation.RequestMethod.DELETE);
        assertThat(requestMappings.getHandlerMethods().keySet().stream()
                .filter(mapping -> mapping.getPatternValues().contains("/api/notes/search"))
                .flatMap(mapping -> mapping.getMethodsCondition().getMethods().stream()))
                .containsExactly(org.springframework.web.bind.annotation.RequestMethod.POST);
        assertThat(requestMappings.getHandlerMethods().keySet().stream()
                .filter(mapping -> mapping.getPatternValues().contains("/api/notes/{noteId}/attachments/{attachmentId}"))
                .flatMap(mapping -> mapping.getMethodsCondition().getMethods().stream()))
                .containsExactlyInAnyOrder(org.springframework.web.bind.annotation.RequestMethod.GET,
                        org.springframework.web.bind.annotation.RequestMethod.DELETE);
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
