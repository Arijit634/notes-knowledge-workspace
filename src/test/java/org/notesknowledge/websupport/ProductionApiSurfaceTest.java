package org.notesknowledge.websupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

@Tag("API")
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
        "management.endpoint.health.validate-group-membership=false",
        "identity.core.enabled=false"
})
class ProductionApiSurfaceTest {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping requestMappings;

    @Test
    void productionContextContainsOnlyAuthorizedNotesAndNoApiProbeController() {
        Set<String> paths = requestMappings.getHandlerMethods().keySet().stream()
                .flatMap(mapping -> mapping.getPatternValues().stream())
                .collect(Collectors.toSet());

        assertThat(paths)
                .noneMatch(path -> path.startsWith("/__api-probe"));
        assertThat(paths.stream().filter(path -> path.startsWith("/api/"))).containsExactlyInAnyOrder(
                "/api/me/note-preferences", "/api/notes", "/api/notes/{noteId}",
                "/api/notes/{noteId}/tags", "/api/notes/{noteId}/pin",
                "/api/notes/{noteId}/archive", "/api/notes/{noteId}/return-from-archive",
                "/api/notes/{noteId}/trash", "/api/notes/{noteId}/restore",
                "/api/notes/{noteId}/ai-access", "/api/notes/ai-access-bulk",
                "/api/notes/{noteId}/versions", "/api/notes/{noteId}/versions/{versionId}",
                "/api/notes/{noteId}/versions/{versionId}/restore", "/api/me/profile", "/api/me/profile/avatar",
                "/api/notes/{noteId}/attachments", "/api/notes/{noteId}/attachments/{attachmentId}",
                "/api/notes/{noteId}/attachments/{attachmentId}/content");
        assertThat(requestMappings.getHandlerMethods().keySet().stream()
                .filter(mapping -> mapping.getPatternValues().contains("/api/notes/{noteId}/attachments"))
                .flatMap(mapping -> mapping.getMethodsCondition().getMethods().stream()))
                .containsExactlyInAnyOrder(org.springframework.web.bind.annotation.RequestMethod.POST,
                        org.springframework.web.bind.annotation.RequestMethod.GET);
        assertThat(requestMappings.getHandlerMethods().keySet().stream()
                .filter(mapping -> mapping.getPatternValues().contains("/api/notes/{noteId}/attachments/{attachmentId}"))
                .flatMap(mapping -> mapping.getMethodsCondition().getMethods().stream()))
                .containsExactly(org.springframework.web.bind.annotation.RequestMethod.GET);
        assertThat(requestMappings.getHandlerMethods().keySet().stream()
                .filter(mapping -> mapping.getPatternValues().contains("/api/notes/{noteId}/attachments/{attachmentId}/content"))
                .flatMap(mapping -> mapping.getMethodsCondition().getMethods().stream()))
                .containsExactly(org.springframework.web.bind.annotation.RequestMethod.GET);
    }
}
