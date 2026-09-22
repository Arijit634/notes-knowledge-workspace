package org.notesknowledge.websupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("API")
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@Import(EtagPreconditionIntegrationTest.ProbeConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class EtagPreconditionIntegrationTest {

    private static final String PROBE = "/__etag-probe/core";
    private static final String TRACE_PATTERN = "tr_[0-9a-f]{32}";

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            "pgvector/pgvector:0.8.6-pg18-trixie")
            .withDatabaseName("etag_foundation")
            .withUsername("etag_migrator")
            .withPassword("synthetic-etag-migrator-password");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired ProbeController controller;

    @BeforeEach
    void resetSyntheticState() {
        controller.reset();
    }

    @Test
    void getReturnsStrongCoreTagAndMissingPreconditionCannotMutate() throws Exception {
        String original = currentEtag();
        MvcResult missing = mockMvc.perform(post(PROBE).with(csrf().asHeader()))
                .andExpect(status().isPreconditionRequired())
                .andReturn();
        assertProblem(missing, 428, "precondition_required");
        assertThat(currentEtag()).isEqualTo(original);
    }

    @ParameterizedTest
    @MethodSource("malformedHeaders")
    void malformedAndUnsupportedHeadersReturnSafe400(String headerValue) throws Exception {
        String original = currentEtag();
        MvcResult malformed = mockMvc.perform(post(PROBE)
                        .with(csrf().asHeader())
                        .header(HttpHeaders.IF_MATCH, headerValue))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertProblem(malformed, 400, "malformed_request");
        assertThat(currentEtag()).isEqualTo(original);
    }

    static Stream<String> malformedHeaders() {
        String tag = new StrongCoreEtagCodec().encode(new NoteCoreVersion(
                UUID.fromString("01991800-0000-7000-8000-000000000001"), 1));
        return Stream.of("", "*", "W/" + tag, tag + "," + tag,
                tag.substring(1, tag.length() - 1), "\"\"",
                "\"" + "a".repeat(129) + "\"");
    }

    @Test
    void multipleIfMatchHeaderFieldsAreUnsupported() throws Exception {
        String original = currentEtag();
        MvcResult malformed = mockMvc.perform(post(PROBE)
                        .with(csrf().asHeader())
                        .header(HttpHeaders.IF_MATCH, original, original))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertProblem(malformed, 400, "malformed_request");
        assertThat(currentEtag()).isEqualTo(original);
    }

    @Test
    void staleResourceBoundTagReturns412WithoutStateChangeOrPrivateLeak(
            CapturedOutput output) throws Exception {
        String original = currentEtag();
        String otherResourceTag = new StrongCoreEtagCodec().encode(new NoteCoreVersion(
                UUID.fromString("01991800-0000-7000-8000-000000000099"), 1));
        String privateMarker = "synthetic-private-precondition-marker-2d";

        MvcResult stale = mockMvc.perform(post(PROBE)
                        .with(csrf().asHeader())
                        .header(HttpHeaders.IF_MATCH, otherResourceTag)
                        .header("X-Synthetic-Private", privateMarker))
                .andExpect(status().isPreconditionFailed())
                .andReturn();
        assertProblem(stale, 412, "stale_write");
        assertThat(stale.getResponse().getHeader(HttpHeaders.ETAG)).isNull();
        assertThat(stale.getResponse().getContentAsString())
                .doesNotContain(privateMarker, otherResourceTag, "revision", "01991800");
        assertThat(output.getAll()).doesNotContain(privateMarker, otherResourceTag);
        assertThat(currentEtag()).isEqualTo(original);
    }

    @Test
    void differentButWellFormedStrongTagIsStaleRatherThanMalformed() throws Exception {
        String original = currentEtag();
        MvcResult stale = mockMvc.perform(post(PROBE)
                        .with(csrf().asHeader())
                        .header(HttpHeaders.IF_MATCH, "\"other\""))
                .andExpect(status().isPreconditionFailed())
                .andReturn();
        assertProblem(stale, 412, "stale_write");
        assertThat(currentEtag()).isEqualTo(original);
    }

    @Test
    void currentTagCanStillFailDomainRuleWith409() throws Exception {
        String original = currentEtag();
        MvcResult conflict = mockMvc.perform(post(PROBE + "/illegal-transition")
                        .with(csrf().asHeader())
                        .header(HttpHeaders.IF_MATCH, original))
                .andExpect(status().isConflict())
                .andReturn();
        assertProblem(conflict, 409, "invalid_lifecycle_transition");
        assertThat(currentEtag()).isEqualTo(original);
    }

    @Test
    void currentTagAllowsMutationAndNewTagMakesOldTagStale() throws Exception {
        String original = currentEtag();
        MvcResult success = mockMvc.perform(post(PROBE)
                        .with(csrf().asHeader())
                        .header(HttpHeaders.IF_MATCH, original))
                .andExpect(status().isNoContent())
                .andExpect(header().exists(HttpHeaders.ETAG))
                .andReturn();

        String advanced = success.getResponse().getHeader(HttpHeaders.ETAG);
        assertThat(advanced).matches("\"[A-Za-z0-9_-]{43}\"").isNotEqualTo(original);
        assertThat(currentEtag()).isEqualTo(advanced);

        MvcResult stale = mockMvc.perform(post(PROBE)
                        .with(csrf().asHeader())
                        .header(HttpHeaders.IF_MATCH, original))
                .andExpect(status().isPreconditionFailed())
                .andReturn();
        assertProblem(stale, 412, "stale_write");
        assertThat(currentEtag()).isEqualTo(advanced);
    }

    private String currentEtag() throws Exception {
        return mockMvc.perform(get(PROBE))
                .andExpect(status().isOk())
                .andExpect(header().exists(HttpHeaders.ETAG))
                .andReturn().getResponse().getHeader(HttpHeaders.ETAG);
    }

    private void assertProblem(MvcResult result, int status, String code) throws Exception {
        assertThat(result.getResponse().getContentType())
                .startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        JsonNode problem = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        assertThat(problem.path("type").asText()).isEqualTo("about:blank");
        assertThat(problem.path("status").asInt()).isEqualTo(status);
        assertThat(problem.path("code").asText()).isEqualTo(code);
        assertThat(problem.path("instance").asText()).startsWith(PROBE);
        assertThat(problem.path("traceId").asText()).matches(TRACE_PATTERN);
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("stackTrace", "exception", "currentEtag");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration {

        @Bean
        ProbeController etagProbeController(
                StrongCoreEtagCodec codec, IfMatchPrecondition precondition) {
            return new ProbeController(codec, precondition);
        }

        @Bean
        @Order(1)
        SecurityFilterChain etagProbeFilterChain(
                HttpSecurity http, HttpSessionCsrfTokenRepository csrfTokenRepository)
                throws Exception {
            http.securityMatcher("/__etag-probe/**")
                    .csrf(csrf -> csrf.csrfTokenRepository(csrfTokenRepository))
                    .authorizeHttpRequests(authorize -> authorize
                            .requestMatchers(HttpMethod.GET, "/__etag-probe/**").permitAll()
                            .requestMatchers(HttpMethod.POST, "/__etag-probe/**").permitAll()
                            .anyRequest().denyAll())
                    .httpBasic(AbstractHttpConfigurer::disable)
                    .formLogin(AbstractHttpConfigurer::disable)
                    .logout(AbstractHttpConfigurer::disable);
            return http.build();
        }
    }

    @RestController
    @RequestMapping(PROBE)
    static class ProbeController {

        private static final UUID ID = UUID.fromString(
                "01991800-0000-7000-8000-000000000001");

        private final StrongCoreEtagCodec codec;
        private final IfMatchPrecondition precondition;
        private final AtomicLong revision = new AtomicLong(1);

        ProbeController(StrongCoreEtagCodec codec, IfMatchPrecondition precondition) {
            this.codec = codec;
            this.precondition = precondition;
        }

        void reset() {
            revision.set(1);
        }

        @GetMapping
        ResponseEntity<Map<String, String>> read() {
            long current = revision.get();
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .eTag(codec.encode(new NoteCoreVersion(ID, current)))
                    .body(Map.of("state", current == 1 ? "initial" : "changed"));
        }

        @PostMapping
        ResponseEntity<Void> mutate(
                @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
            long before = revision.get();
            precondition.requireCurrent(ifMatch, codec.encode(new NoteCoreVersion(ID, before)));
            if (!revision.compareAndSet(before, before + 1)) {
                throw ApiFailureException.of(ApiFailureException.Kind.STALE_WRITE);
            }
            return ResponseEntity.noContent()
                    .cacheControl(CacheControl.noStore())
                    .eTag(codec.encode(new NoteCoreVersion(ID, before + 1)))
                    .build();
        }

        @PostMapping("/illegal-transition")
        ResponseEntity<Void> illegalTransition(
                @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
            precondition.requireCurrent(ifMatch,
                    codec.encode(new NoteCoreVersion(ID, revision.get())));
            throw ApiFailureException.of(
                    ApiFailureException.Kind.INVALID_LIFECYCLE_TRANSITION);
        }
    }
}
