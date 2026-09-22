package org.notesknowledge.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.Cookie;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.notesknowledge.DependencyHealthTracker;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("SECURITY")
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@Import(SecurityRuntimeTest.SecurityProbeConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class SecurityRuntimeTest {

    private static final String IMAGE = "pgvector/pgvector:0.8.6-pg18-trixie";
    private static final String PROBE = "/__security-probe";
    private static final String CSRF_HEADER = "X-CSRF-TOKEN";

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(IMAGE)
            .withDatabaseName("security_runtime")
            .withUsername("security_migrator")
            .withPassword("synthetic-security-migrator-password");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcIndexedSessionRepository sessions;

    @Autowired
    DependencyHealthTracker dependencyHealth;

    @Autowired
    JdbcClient jdbc;

    @Test
    void productionChainDeniesUnmatchedPathsAndExposesNoLoginOrBasicSurface() throws Exception {
        mockMvc.perform(get("/api/not-implemented"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE));

        mockMvc.perform(get("/login"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE));

        mockMvc.perform(get("/api/not-implemented")
                        .header(HttpHeaders.AUTHORIZATION, basicAuthorization()))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE));
    }

    @Test
    void onlyConservativeHealthSurfaceRemainsAvailable() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"UP\"}", false));

        for (String path : List.of(
                "/actuator",
                "/actuator/env",
                "/actuator/configprops",
                "/actuator/beans",
                "/actuator/mappings",
                "/actuator/heapdump",
                "/actuator/loggers",
                "/actuator/prometheus")) {
            mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void optionalFeatureFailureDoesNotChangeCoreHealthOrSecurity(CapturedOutput output)
            throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"UP\"}"));
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"UP\"}"));
        Integer sessionsBefore = jdbc.sql("select count(*) from identity.spring_session")
                .query(Integer.class).single();

        dependencyHealth.transition(DependencyHealthTracker.Dependency.GEMINI_CHAT,
                DependencyHealthTracker.State.DOWN,
                DependencyHealthTracker.FeatureImpact.OPTIONAL_FEATURE,
                DependencyHealthTracker.SafeReasonClass.UNAVAILABLE);
        assertThat(jdbc.sql("select count(*) from identity.spring_session")
                .query(Integer.class).single()).isEqualTo(sessionsBefore);
        dependencyHealth.transition(DependencyHealthTracker.Dependency.GEMINI_CHAT,
                DependencyHealthTracker.State.DOWN,
                DependencyHealthTracker.FeatureImpact.OPTIONAL_FEATURE,
                DependencyHealthTracker.SafeReasonClass.UNAVAILABLE);

        assertThat(dependencyHealth.status(DependencyHealthTracker.Dependency.GEMINI_CHAT)
                .state()).isEqualTo(DependencyHealthTracker.State.DOWN);
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"UP\"}"));
        MvcResult readiness = mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(readiness.getResponse().getContentAsString()).isEqualTo("{\"status\":\"UP\"}");
        assertThat(readiness.getResponse().getContentAsString())
                .doesNotContain("GEMINI", "postgres", "identity", "jdbc:", "exception");
        mockMvc.perform(get("/api/not-implemented")).andExpect(status().isUnauthorized());
        mockMvc.perform(post(PROBE + "/unsafe")).andExpect(status().isForbidden());
        assertThat(output.getAll()).contains("dependency.state_change");
        assertThat(mockMvc.perform(get("/api/dependency-health"))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void safeRequestsNeedNoCsrfButEveryUnsafeMethodRejectsMissingProof() throws Exception {
        mockMvc.perform(get(PROBE + "/safe")).andExpect(status().isNoContent());
        mockMvc.perform(head(PROBE + "/safe")).andExpect(status().isNoContent());

        mockMvc.perform(post(PROBE + "/unsafe")).andExpect(status().isForbidden());
        mockMvc.perform(put(PROBE + "/unsafe")).andExpect(status().isForbidden());
        mockMvc.perform(patch(PROBE + "/unsafe")).andExpect(status().isForbidden());
        mockMvc.perform(delete(PROBE + "/unsafe")).andExpect(status().isForbidden());
    }

    @Test
    void invalidAndCrossSessionCsrfProofsAreRejected() throws Exception {
        SessionProof sessionA = newSessionProof();
        SessionProof sessionB = newSessionProof();

        mockMvc.perform(post(PROBE + "/unsafe")
                        .cookie(sessionA.cookie())
                        .header(CSRF_HEADER, "synthetic-invalid-csrf-proof"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post(PROBE + "/unsafe")
                        .cookie(sessionB.cookie())
                        .header(CSRF_HEADER, sessionA.token()))
                .andExpect(status().isForbidden());
    }

    @Test
    void currentSessionCsrfProofAllowsEveryUnsafeProbeMethod() throws Exception {
        SessionProof proof = newSessionProof();

        mockMvc.perform(post(PROBE + "/unsafe")
                        .cookie(proof.cookie())
                        .header(CSRF_HEADER, proof.token()))
                .andExpect(status().isNoContent());
        mockMvc.perform(put(PROBE + "/unsafe")
                        .cookie(proof.cookie())
                        .header(CSRF_HEADER, proof.token()))
                .andExpect(status().isNoContent());
        mockMvc.perform(patch(PROBE + "/unsafe")
                        .cookie(proof.cookie())
                        .header(CSRF_HEADER, proof.token()))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete(PROBE + "/unsafe")
                        .cookie(proof.cookie())
                        .header(CSRF_HEADER, proof.token()))
                .andExpect(status().isNoContent());
    }

    @Test
    void authenticationCookieIsHttpOnlySecureHostOnlyAndSameSiteLax() throws Exception {
        MvcResult result = mockMvc.perform(get(PROBE + "/session").secure(true))
                .andExpect(status().isNoContent())
                .andReturn();

        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie)
                .isNotBlank()
                .contains("SESSION=")
                .contains("Path=/")
                .contains("Secure")
                .contains("HttpOnly")
                .contains("SameSite=Lax")
                .doesNotContain("Domain=")
                .doesNotContain("synthetic-surviving-state");
        assertThat(result.getResponse().getContentAsString()).isEmpty();
    }

    @Test
    void syntheticAuthenticationRotatesSessionPreservesStateAndRefreshesCsrf() throws Exception {
        SessionProof before = newSessionProof();
        mockMvc.perform(get(PROBE + "/session").cookie(before.cookie()))
                .andExpect(status().isNoContent());
        String oldSessionId = before.sessionId();

        MvcResult authentication = mockMvc.perform(post(PROBE + "/authenticate")
                        .secure(true)
                        .cookie(before.cookie())
                        .header(CSRF_HEADER, before.token())
                        .param("username", "synthetic-user")
                        .param("password", "synthetic-password"))
                .andExpect(status().isNoContent())
                .andReturn();

        Cookie rotatedCookie = authentication.getResponse().getCookie("SESSION");
        assertThat(rotatedCookie).isNotNull();
        String rotatedSessionId = new String(
                java.util.Base64.getDecoder().decode(rotatedCookie.getValue()),
                StandardCharsets.UTF_8);
        assertThat(rotatedSessionId).isNotEqualTo(oldSessionId);

        FindByIndexNameSessionRepository<Session> repository = publicRepository(sessions);
        assertThat(repository.findById(oldSessionId)).isNull();
        Session rotated = repository.findById(rotatedSessionId);
        assertThat(rotated).isNotNull();
        assertThat((Object) rotated.getAttribute("survivesRotation"))
                .isEqualTo("synthetic-surviving-state");

        mockMvc.perform(post(PROBE + "/unsafe")
                        .cookie(before.cookie())
                        .header(CSRF_HEADER, before.token()))
                .andExpect(status().isForbidden());

        String refreshedToken = mockMvc.perform(get(PROBE + "/csrf")
                        .cookie(rotatedCookie))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(refreshedToken).isNotBlank().isNotEqualTo(before.token());

        mockMvc.perform(post(PROBE + "/unsafe")
                        .cookie(rotatedCookie)
                        .header(CSRF_HEADER, refreshedToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(PROBE + "/authenticated").cookie(rotatedCookie))
                .andExpect(status().isOk())
                .andExpect(content().string("synthetic-surviving-state"));
    }

    @Test
    void arbitraryOriginsReceiveNoCredentialedCorsPermission() throws Exception {
        String unapprovedOrigin = "https://unapproved.invalid";

        mockMvc.perform(get(PROBE + "/safe").header(HttpHeaders.ORIGIN, unapprovedOrigin))
                .andExpect(status().isNoContent())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));

        mockMvc.perform(options(PROBE + "/unsafe")
                        .header(HttpHeaders.ORIGIN, unapprovedOrigin)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.POST.name()))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
    }

    @Test
    void securityHeadersUseSafeFrameworkFoundation() throws Exception {
        mockMvc.perform(get(PROBE + "/safe").secure(true))
                .andExpect(status().isNoContent())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("X-XSS-Protection", "0"))
                .andExpect(header().exists("Strict-Transport-Security"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL,
                        "no-cache, no-store, max-age=0, must-revalidate"));

        mockMvc.perform(get(PROBE + "/safe").secure(false))
                .andExpect(status().isNoContent())
                .andExpect(header().doesNotExist("Strict-Transport-Security"));
    }

    @Test
    void rejectedSecurityRequestDoesNotLeakSubmittedValues(CapturedOutput output)
            throws Exception {
        List<String> forbiddenMarkers = List.of(
                "synthetic-session-marker-2b",
                "synthetic-csrf-marker-2b",
                "synthetic-authorization-marker-2b",
                "synthetic-credential-marker-2b",
                "synthetic-private-body-marker-2b");

        mockMvc.perform(post("/api/not-implemented")
                        .header(HttpHeaders.COOKIE,
                                "SESSION=" + forbiddenMarkers.get(0))
                        .header(CSRF_HEADER, forbiddenMarkers.get(1))
                        .header(HttpHeaders.AUTHORIZATION,
                                "Bearer " + forbiddenMarkers.get(2))
                        .header("X-Synthetic-Credential", forbiddenMarkers.get(3))
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(forbiddenMarkers.get(4)))
                .andExpect(status().isForbidden());

        assertThat(output.getAll()).doesNotContain(forbiddenMarkers.toArray(String[]::new));
    }

    private SessionProof newSessionProof() throws Exception {
        MvcResult result = mockMvc.perform(get(PROBE + "/csrf").secure(true))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andReturn();
        String token = result.getResponse().getContentAsString();
        Cookie cookie = result.getResponse().getCookie("SESSION");
        assertThat(token).isNotBlank();
        assertThat(cookie).isNotNull();
        return new SessionProof(
                cookie,
                token,
                new String(java.util.Base64.getDecoder().decode(cookie.getValue()),
                        StandardCharsets.UTF_8));
    }

    private String basicAuthorization() {
        String value = "synthetic-user:synthetic-password";
        return "Basic " + java.util.Base64.getEncoder()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private FindByIndexNameSessionRepository<Session> publicRepository(
            JdbcIndexedSessionRepository repository) {
        return (FindByIndexNameSessionRepository<Session>)
                (FindByIndexNameSessionRepository<?>) repository;
    }

    private record SessionProof(
            Cookie cookie,
            String token,
            String sessionId) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SecurityProbeConfiguration {

        @Bean
        SecurityProbeController securityProbeController() {
            return new SecurityProbeController();
        }

        @Bean
        InMemoryUserDetailsManager syntheticProbeUsers() {
            return new InMemoryUserDetailsManager(User.withUsername("synthetic-user")
                    .password("{noop}synthetic-password")
                    .roles("PROBE")
                    .build());
        }

        @Bean
        @Order(1)
        SecurityFilterChain securityProbeFilterChain(
                HttpSecurity http,
                HttpSessionCsrfTokenRepository csrfTokenRepository,
                InMemoryUserDetailsManager syntheticProbeUsers) throws Exception {
            DaoAuthenticationProvider provider =
                    new DaoAuthenticationProvider(syntheticProbeUsers);
            ProviderManager authenticationManager = new ProviderManager(provider);

            http
                    .securityMatcher(PROBE + "/**")
                    .authenticationManager(authenticationManager)
                    .csrf(csrf -> csrf.csrfTokenRepository(csrfTokenRepository))
                    .authorizeHttpRequests(authorize -> authorize
                            .requestMatchers(HttpMethod.GET, PROBE + "/authenticated")
                            .authenticated()
                            .requestMatchers(HttpMethod.GET,
                                    PROBE + "/safe",
                                    PROBE + "/session",
                                    PROBE + "/csrf")
                            .permitAll()
                            .requestMatchers(HttpMethod.HEAD, PROBE + "/safe")
                            .permitAll()
                            .requestMatchers(HttpMethod.POST, PROBE + "/authenticate")
                            .permitAll()
                            .requestMatchers(
                                    HttpMethod.POST,
                                    PROBE + "/unsafe")
                            .permitAll()
                            .requestMatchers(
                                    HttpMethod.PUT,
                                    PROBE + "/unsafe")
                            .permitAll()
                            .requestMatchers(
                                    HttpMethod.PATCH,
                                    PROBE + "/unsafe")
                            .permitAll()
                            .requestMatchers(
                                    HttpMethod.DELETE,
                                    PROBE + "/unsafe")
                            .permitAll()
                            .anyRequest()
                            .denyAll())
                    .httpBasic(AbstractHttpConfigurer::disable)
                    .logout(AbstractHttpConfigurer::disable)
                    .requestCache(AbstractHttpConfigurer::disable)
                    .exceptionHandling(exceptions -> exceptions
                            .authenticationEntryPoint((request, response, exception) ->
                                    response.sendError(HttpStatus.FORBIDDEN.value())))
                    .formLogin(form -> form
                            .loginProcessingUrl(PROBE + "/authenticate")
                            .successHandler((request, response, authentication) -> {
                                csrfTokenRepository.saveToken(null, request, response);
                                response.setStatus(HttpStatus.NO_CONTENT.value());
                            })
                            .failureHandler((request, response, exception) ->
                                    response.setStatus(HttpStatus.UNAUTHORIZED.value())))
                    .sessionManagement(session -> session
                            .sessionFixation(fixation -> fixation.changeSessionId()))
                    .headers(headers -> headers
                            .referrerPolicy(policy ->
                                    policy.policy(ReferrerPolicy.NO_REFERRER)));

            return http.build();
        }
    }

    @RestController
    static class SecurityProbeController {

        @GetMapping(PROBE + "/safe")
        ResponseEntity<Void> safe() {
            return ResponseEntity.noContent().build();
        }

        @GetMapping(PROBE + "/session")
        ResponseEntity<Void> createSession(HttpSession session) {
            session.setAttribute("survivesRotation", "synthetic-surviving-state");
            return ResponseEntity.noContent().build();
        }

        @GetMapping(PROBE + "/csrf")
        ResponseEntity<String> csrf(CsrfToken token) {
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(token.getToken());
        }

        @RequestMapping(
                path = PROBE + "/unsafe",
                method = {
                        RequestMethod.POST,
                        RequestMethod.PUT,
                        RequestMethod.PATCH,
                        RequestMethod.DELETE
                })
        ResponseEntity<Void> unsafe() {
            return ResponseEntity.noContent().build();
        }

        @GetMapping(PROBE + "/authenticated")
        ResponseEntity<String> authenticated(
                Authentication authentication,
                HttpSession session) {
            if (!authentication.isAuthenticated()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
            return ResponseEntity.ok((String) session.getAttribute("survivesRotation"));
        }
    }
}
