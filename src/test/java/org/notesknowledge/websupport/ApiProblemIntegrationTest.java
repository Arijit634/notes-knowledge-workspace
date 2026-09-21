package org.notesknowledge.websupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("API")
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@Import(ApiProblemIntegrationTest.ApiProbeConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class ApiProblemIntegrationTest {

    private static final String IMAGE = "pgvector/pgvector:0.8.6-pg18-trixie";
    private static final String PROBE = "/__api-probe";
    private static final String TRACE_PATTERN = "tr_[0-9a-f]{32}";

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(IMAGE)
            .withDatabaseName("api_foundation")
            .withUsername("api_migrator")
            .withPassword("synthetic-api-migrator-password");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void validCommandRemainsOrdinaryJson() throws Exception {
        mockMvc.perform(post(PROBE + "/commands")
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"valid-name\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.name").value("valid-name"));
    }

    @Test
    void malformedJsonUsesSafeProblemContractAndOmitsQuery() throws Exception {
        mockMvc.perform(post(PROBE + "/commands")
                        .queryParam("private", "synthetic-private-query-2c")
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value("Malformed JSON request"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.instance").value(PROBE + "/commands"))
                .andExpect(jsonPath("$.code").value("malformed_json"))
                .andExpect(jsonPath("$.traceId").value(
                        org.hamcrest.Matchers.matchesPattern(TRACE_PATTERN)))
                .andExpect(content().string(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(
                                "synthetic-private-query-2c"))));
    }

    @Test
    void unknownCommandPropertyIsRejectedWithoutEchoingItsValue() throws Exception {
        String privateMarker = "synthetic-private-unknown-value-2c";
        mockMvc.perform(post(PROBE + "/commands")
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"valid\",\"unexpected\":\""
                                + privateMarker + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("unknown_property"))
                .andExpect(content().string(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(
                                privateMarker))));
    }

    @Test
    void malformedRequestBindingUsesStable400Problem() throws Exception {
        MvcResult result = mockMvc.perform(get(PROBE + "/required-header"))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertProblem(result, 400, "malformed_request");
    }

    @Test
    void malformedTypedQueryUsesSafe400ProblemWithoutEchoingValue() throws Exception {
        String rejected = "synthetic-private-integer-2c";
        MvcResult result = mockMvc.perform(get(PROBE + "/typed-query")
                        .queryParam("count", rejected))
                .andExpect(status().isBadRequest())
                .andReturn();

        assertProblem(result, 400, "malformed_request");
        assertThat(result.getResolvedException())
                .isInstanceOf(MethodArgumentTypeMismatchException.class);
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain(rejected)
                .doesNotContain("MethodArgumentTypeMismatchException")
                .doesNotContain("NumberFormatException")
                .doesNotContain("java.lang.Integer");
    }

    @Test
    void mvcMissingResourceUsesSafe404Problem() throws Exception {
        MvcResult result = mockMvc.perform(get(PROBE + "/missing-resource"))
                .andExpect(status().isNotFound())
                .andReturn();

        assertProblem(result, 404, "resource_not_found");
        assertThat(result.getResolvedException()).isInstanceOf(NoResourceFoundException.class);
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("NoResourceFoundException")
                .doesNotContain("static resource")
                .doesNotContain("classpath:")
                .doesNotContain("file:");
    }

    @Test
    void validationErrorsAreAllowlistedAndNeverContainRejectedValue() throws Exception {
        String privateMarker = "synthetic-private-validation-value-2c";
        MvcResult result = mockMvc.perform(post(PROBE + "/commands")
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + privateMarker + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("validation_failed"))
                .andExpect(jsonPath("$.errors[0].field").value("name"))
                .andExpect(jsonPath("$.errors[0].code").value("invalid_length"))
                .andExpect(jsonPath("$.errors[0].message")
                        .value("Value length is outside the allowed range."))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain(privateMarker);
        JsonNode error = objectMapper.readTree(result.getResponse().getContentAsByteArray())
                .path("errors")
                .get(0);
        Set<String> fields = new java.util.HashSet<>();
        fields.addAll(error.propertyNames());
        assertThat(fields).containsExactlyInAnyOrder("field", "code", "message");
    }

    @Test
    void securityFailuresUseTheSameSafeProblemShape() throws Exception {
        assertProblem(
                mockMvc.perform(get(PROBE + "/authentication-required"))
                        .andExpect(status().isUnauthorized())
                        .andReturn(),
                401,
                "authentication_required");

        assertProblem(
                mockMvc.perform(get(PROBE + "/forbidden")
                                .with(user("synthetic-user").roles("OTHER")))
                        .andExpect(status().isForbidden())
                        .andReturn(),
                403,
                "access_denied");

        assertProblem(
                mockMvc.perform(post(PROBE + "/commands")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"valid\"}"))
                        .andExpect(status().isForbidden())
                        .andReturn(),
                403,
                "csrf_invalid");
    }

    @ParameterizedTest
    @MethodSource("typedFailureCases")
    void typedFailuresUseStableStatusAndCode(
            String failure,
            int expectedStatus,
            String expectedCode,
            String expectedTitle) throws Exception {
        MvcResult result = mockMvc.perform(get(PROBE + "/failures/" + failure))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        assertProblem(result, expectedStatus, expectedCode);
        assertThat(objectMapper.readTree(result.getResponse().getContentAsByteArray())
                .path("title").asText()).isEqualTo(expectedTitle);
    }

    static Stream<Arguments> typedFailureCases() {
        return Stream.of(
                Arguments.of("not-found", 404, "resource_not_found", "Resource not found"),
                Arguments.of("conflict", 409, "invalid_lifecycle_transition",
                        "Request conflicts with current state"),
                Arguments.of("stale", 412, "stale_write",
                        "Resource changed since it was loaded"),
                Arguments.of("too-large", 413, "request_too_large", "Request is too large"),
                Arguments.of("unsupported", 415, "unsupported_media_type",
                        "Unsupported media type"),
                Arguments.of("precondition", 428, "precondition_required",
                        "Required precondition is missing"),
                Arguments.of("unavailable", 503, "service_unavailable",
                        "Required service is unavailable"));
    }

    @Test
    void sharedFailureConstructionAcceptsOnlyRegisteredKinds() throws Exception {
        assertThat(ApiFailureException.class.getDeclaredConstructors())
                .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers()));
        assertThat(ApiFailureException.class.getDeclaredMethods())
                .filteredOn(method -> Modifier.isPublic(method.getModifiers()))
                .allMatch(method -> Stream.of(method.getParameterTypes())
                        .noneMatch(parameter -> parameter == String.class));
        assertThatThrownBy(() -> ApiFailureException.of(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ApiFailureException.of(
                ApiFailureException.Kind.RATE_LIMITED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ApiFailureException.rateLimited(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ApiFailureException.rateLimited(86_401))
                .isInstanceOf(IllegalArgumentException.class);

        String privateMarker = "synthetic-private-title-code-2c";
        MvcResult result = mockMvc.perform(get(PROBE + "/failures/not-found")
                        .queryParam("title", privateMarker)
                        .queryParam("code", privateMarker))
                .andExpect(status().isNotFound())
                .andReturn();
        assertProblem(result, 404, "resource_not_found");
        assertThat(result.getResponse().getContentAsString()).doesNotContain(privateMarker);
    }

    @Test
    void unsupportedMediaTypeUsesStable415Problem() throws Exception {
        MvcResult result = mockMvc.perform(post(PROBE + "/commands")
                        .with(csrf().asHeader())
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("synthetic-unsupported-body-2c"))
                .andExpect(status().isUnsupportedMediaType())
                .andReturn();
        assertProblem(result, 415, "unsupported_media_type");
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("synthetic-unsupported-body-2c");
    }

    @Test
    void rateLimitProblemCarriesOnlySafeRetryAfter() throws Exception {
        MvcResult result = mockMvc.perform(get(PROBE + "/failures/rate-limited"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "17"))
                .andReturn();
        assertProblem(result, 429, "rate_limited");
        assertThat(objectMapper.readTree(result.getResponse().getContentAsByteArray())
                .path("title").asText()).isEqualTo("Request rate limit reached");
    }

    @Test
    void unexpectedExceptionNeverExposesExceptionOrPrivateMarkers() throws Exception {
        MvcResult result = mockMvc.perform(get(PROBE + "/explode"))
                .andExpect(status().isInternalServerError())
                .andReturn();
        assertProblem(result, 500, "internal_error");
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("synthetic-private-exception-2c")
                .doesNotContain("IllegalStateException")
                .doesNotContain("select * from private_notes")
                .doesNotContain("provider-secret-2c");
    }

    @Test
    void traceContextIsUniqueServerControlledAndClearedAfterEachRequest() throws Exception {
        MvcResult first = mockMvc.perform(get(PROBE + "/failures/not-found")
                        .header("X-Correlation-ID", "malicious-correlation-2c"))
                .andReturn();
        String firstTrace = traceId(first);
        assertThat(MDC.get(RequestTraceContext.MDC_KEY)).isNull();

        MvcResult second = mockMvc.perform(get(PROBE + "/failures/not-found"))
                .andReturn();
        String secondTrace = traceId(second);

        assertThat(firstTrace)
                .matches(TRACE_PATTERN)
                .isNotEqualTo("malicious-correlation-2c");
        assertThat(secondTrace).matches(TRACE_PATTERN).isNotEqualTo(firstTrace);
        assertThat(MDC.get(RequestTraceContext.MDC_KEY)).isNull();
    }

    @Test
    void requestCompletedEventContainsOnlyBoundedSafeRequestData(CapturedOutput output)
            throws Exception {
        String queryMarker = "synthetic-private-query-log-marker-2c";
        String correlationMarker = "synthetic-correlation-log-marker-2c";
        MvcResult result = mockMvc.perform(get(PROBE + "/failures/not-found")
                        .queryParam("private", queryMarker)
                        .header("X-Correlation-ID", correlationMarker))
                .andExpect(status().isNotFound())
                .andReturn();

        String traceId = traceId(result);
        assertThat(output.getAll())
                .contains("request.completed")
                .contains(traceId)
                .contains(PROBE + "/failures/{kind}")
                .doesNotContain(queryMarker)
                .doesNotContain(correlationMarker);
    }

    private void assertProblem(MvcResult result, int status, String code) throws Exception {
        assertThat(result.getResponse().getContentType())
                .startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        JsonNode problem = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        assertThat(problem.path("type").asText()).isEqualTo("about:blank");
        assertThat(problem.path("title").asText()).isNotBlank();
        assertThat(problem.path("status").asInt()).isEqualTo(status);
        assertThat(problem.path("instance").asText()).startsWith(PROBE);
        assertThat(problem.path("code").asText()).isEqualTo(code);
        assertThat(problem.path("traceId").asText()).matches(TRACE_PATTERN);
        assertThat(problem.has("stackTrace")).isFalse();
        assertThat(problem.has("exception")).isFalse();
    }

    private String traceId(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray())
                .path("traceId")
                .asText();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ApiProbeConfiguration {

        @Bean
        ApiProbeController apiProbeController() {
            return new ApiProbeController();
        }

        @Bean
        @Order(1)
        SecurityFilterChain apiProbeFilterChain(
                HttpSecurity http,
                HttpSessionCsrfTokenRepository csrfTokenRepository,
                ApiProblemWriter problemWriter) throws Exception {
            http
                    .securityMatcher(PROBE + "/**")
                    .csrf(csrfConfig -> csrfConfig
                            .csrfTokenRepository(csrfTokenRepository))
                    .authorizeHttpRequests(authorize -> authorize
                            .requestMatchers(HttpMethod.GET,
                                    PROBE + "/authentication-required")
                            .authenticated()
                            .requestMatchers(HttpMethod.GET, PROBE + "/forbidden")
                            .hasRole("API_PROBE")
                            .anyRequest()
                            .permitAll())
                    .httpBasic(AbstractHttpConfigurer::disable)
                    .formLogin(AbstractHttpConfigurer::disable)
                    .logout(AbstractHttpConfigurer::disable)
                    .requestCache(AbstractHttpConfigurer::disable)
                    .exceptionHandling(exceptions -> exceptions
                            .authenticationEntryPoint((request, response, exception) ->
                                    problemWriter.writeAuthenticationRequired(
                                            request, response))
                            .accessDeniedHandler((request, response, exception) ->
                                    problemWriter.writeAccessDenied(
                                            request, response, exception)));
            return http.build();
        }
    }

    @RestController
    @RequestMapping(PROBE)
    static class ApiProbeController {

        @PostMapping(
                path = "/commands",
                consumes = MediaType.APPLICATION_JSON_VALUE,
                produces = MediaType.APPLICATION_JSON_VALUE)
        Map<String, String> command(@Valid @RequestBody ProbeCommand command) {
            return Map.of("name", command.name());
        }

        @GetMapping("/authentication-required")
        ResponseEntity<Void> authenticationRequired() {
            return ResponseEntity.noContent().build();
        }

        @GetMapping("/required-header")
        ResponseEntity<Void> requiredHeader(
                @RequestHeader("X-Synthetic-Required") String required) {
            return ResponseEntity.noContent().build();
        }

        @GetMapping("/typed-query")
        ResponseEntity<Void> typedQuery(@RequestParam int count) {
            return ResponseEntity.noContent().build();
        }

        @GetMapping("/forbidden")
        ResponseEntity<Void> forbidden() {
            return ResponseEntity.noContent().build();
        }

        @GetMapping("/failures/{kind}")
        ResponseEntity<Void> failure(@PathVariable String kind) {
            throw switch (kind) {
                case "not-found" -> ApiFailureException.of(
                        ApiFailureException.Kind.RESOURCE_NOT_FOUND);
                case "conflict" -> ApiFailureException.of(
                        ApiFailureException.Kind.INVALID_LIFECYCLE_TRANSITION);
                case "stale" -> ApiFailureException.of(
                        ApiFailureException.Kind.STALE_WRITE);
                case "too-large" -> ApiFailureException.of(
                        ApiFailureException.Kind.REQUEST_TOO_LARGE);
                case "unsupported" -> ApiFailureException.of(
                        ApiFailureException.Kind.UNSUPPORTED_MEDIA_TYPE);
                case "precondition" -> ApiFailureException.of(
                        ApiFailureException.Kind.PRECONDITION_REQUIRED);
                case "rate-limited" -> ApiFailureException.rateLimited(17);
                case "unavailable" -> ApiFailureException.of(
                        ApiFailureException.Kind.SERVICE_UNAVAILABLE);
                default -> ApiFailureException.of(
                        ApiFailureException.Kind.RESOURCE_NOT_FOUND);
            };
        }

        @GetMapping("/explode")
        ResponseEntity<Void> explode() {
            throw new IllegalStateException(
                    "synthetic-private-exception-2c select * from private_notes "
                            + "provider-secret-2c");
        }
    }

    record ProbeCommand(
            @NotBlank
            @Size(max = 12)
            String name) {
    }
}
