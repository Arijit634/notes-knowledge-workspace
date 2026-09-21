package org.notesknowledge.compatibility;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingModel;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

import tools.jackson.databind.json.JsonMapper;

@Tag("FAST")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
        "springdoc.api-docs.enabled=true",
        "management.endpoints.web.exposure.include=health,prometheus"
})
@Import(BackendCompatibilityTest.CompatibilityProbeSecurity.class)
class BackendCompatibilityTest {

    @LocalServerPort
    int port;

    @Autowired
    JsonMapper jsonMapper;

    @Test
    void resolvesApprovedSpringAiClassesWithoutAProviderCall() {
        assertThat(GoogleGenAiChatModel.class).isNotNull();
        assertThat(GoogleGenAiTextEmbeddingModel.class).isNotNull();
        assertThat(PgVectorStore.class).isNotNull();
    }

    @Test
    void generatesAndLoadsEmptyOpenApiWithJacksonThree() throws Exception {
        HttpResponse<String> response = get("/v3/api-docs");

        assertThat(response.statusCode()).isEqualTo(200);
        var document = jsonMapper.readTree(response.body());
        assertThat(document.path("openapi").asText()).startsWith("3.");
        assertThat(document.path("paths").isObject()).isTrue();
        assertThat(document.path("paths").isEmpty()).isTrue();
    }

    @Test
    void exposesPrometheusFormatOnlyWhenCompatibilityProfileEnablesIt() throws Exception {
        HttpResponse<String> response = get("/actuator/prometheus");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("# HELP", "# TYPE");
        assertThat(response.body()).doesNotContain("note_body", "search_query", "ai_prompt");
    }

    private HttpResponse<String> get(String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .GET()
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CompatibilityProbeSecurity {

        @Bean
        @Order(1)
        SecurityFilterChain compatibilityProbeFilterChain(HttpSecurity http) throws Exception {
            return http
                    .securityMatcher("/v3/api-docs/**", "/actuator/prometheus")
                    .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                    .build();
        }
    }
}
