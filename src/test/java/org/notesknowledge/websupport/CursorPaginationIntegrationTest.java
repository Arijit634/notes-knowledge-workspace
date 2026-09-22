package org.notesknowledge.websupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import tools.jackson.databind.ObjectMapper;

/** Test-only HTTP fixture; no Product route, repository query, or production key source. */
@Tag("API")
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@Import(CursorPaginationIntegrationTest.ProbeConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class CursorPaginationIntegrationTest {

    private static final String PROBE = "/__cursor-probe/pages";
    private static final Instant START = Instant.parse("2026-09-22T00:00:00Z");

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            "pgvector/pgvector:0.8.6-pg18-trixie")
            .withDatabaseName("cursor_probe")
            .withUsername("cursor_migrator")
            .withPassword("synthetic-cursor-migrator-password");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired ProbeState state;

    @BeforeEach
    void reset() {
        state.instant.set(START);
        state.keyRing.set(new CursorKeyRing.KeySnapshot(state.v1, List.of()));
        state.contextChecks.set(0);
    }

    @Test
    void initialPageIsBoundedAndContinuationReestablishesExpectedContext() throws Exception {
        MvcResult first = mockMvc.perform(get(PROBE + "/owner"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0]").value("synthetic-item-0"))
                .andExpect(jsonPath("$.nextCursor").isString())
                .andReturn();
        String cursor = objectMapper.readTree(first.getResponse().getContentAsString())
                .get("nextCursor").asText();
        assertThat(first.getResponse().getContentAsString()).doesNotContain("totalCount");

        mockMvc.perform(get(PROBE + "/owner").queryParam("cursor", cursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0]").value("synthetic-item-2"))
                .andExpect(jsonPath("$.items.length()").value(2));
        assertThat(state.contextChecks).hasValue(2);
    }

    @Test
    @Tag("SECURITY")
    void malformedExpiredAndDifferentBindingsAreSafe400(CapturedOutput output)
            throws Exception {
        String cursor = firstCursor();
        String tampered = cursor.substring(0, cursor.length() - 1)
                + (cursor.endsWith("A") ? "B" : "A");
        for (String rejected : List.of(tampered, cursor + ".extra", "c1.v1.not-base64!")) {
            reject("owner", rejected);
        }
        for (String other : List.of("public", "other-scope", "other-filter", "other-sort")) {
            reject(other, cursor);
        }
        state.instant.set(START.plus(Duration.ofMinutes(6)));
        reject("owner", cursor);

        assertThat(output.getAll()).doesNotContain(cursor, "AEADBadTagException",
                "synthetic-private-identity", "synthetic-private-filter",
                "synthetic-private-ordering-marker", "synthetic-cursor-key-material");
    }

    @Test
    @Tag("SECURITY")
    void rotationRetainsThenRetiresOldVersion() throws Exception {
        String old = firstCursor();
        assertThat(old).startsWith("c1.v1.");
        state.keyRing.set(new CursorKeyRing.KeySnapshot(state.v2, List.of(state.v1)));
        mockMvc.perform(get(PROBE + "/owner").queryParam("cursor", old))
                .andExpect(status().isOk());
        assertThat(firstCursor()).startsWith("c1.v2.");
        state.keyRing.set(new CursorKeyRing.KeySnapshot(state.v2, List.of()));
        reject("owner", old);
    }

    @Test
    void invalidLimitsAreRejectedWithoutChangingProductPolicy() throws Exception {
        mockMvc.perform(get(PROBE + "/owner").queryParam("limit", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3));
        for (String limit : List.of("0", "-1", "4")) {
            mockMvc.perform(get(PROBE + "/owner").queryParam("limit", limit))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("malformed_request"));
        }
    }

    private String firstCursor() throws Exception {
        MvcResult result = mockMvc.perform(get(PROBE + "/owner"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("nextCursor").asText();
    }

    private void reject(String context, String cursor) throws Exception {
        MvcResult result = mockMvc.perform(get(PROBE + "/" + context)
                        .queryParam("cursor", cursor))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("malformed_request"))
                .andReturn();
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain(cursor, "AEADBadTagException", "synthetic-private");
    }

    @TestConfiguration
    static class ProbeConfiguration {
        @Bean ProbeState cursorProbeState() { return new ProbeState(); }

        @Bean CursorProbeController cursorProbeController(ProbeState state) {
            return new CursorProbeController(state);
        }

        @Bean
        @Order(1)
        SecurityFilterChain cursorProbeFilterChain(HttpSecurity http) throws Exception {
            http.securityMatcher("/__cursor-probe/**")
                    .authorizeHttpRequests(authorize -> authorize
                            .requestMatchers(HttpMethod.GET, "/__cursor-probe/**").permitAll()
                            .anyRequest().denyAll())
                    .httpBasic(AbstractHttpConfigurer::disable)
                    .formLogin(AbstractHttpConfigurer::disable)
                    .logout(AbstractHttpConfigurer::disable);
            return http.build();
        }
    }

    static final class ProbeState {
        final AtomicReference<Instant> instant = new AtomicReference<>(START);
        final CursorKeyRing.CursorKey v1 = new CursorKeyRing.CursorKey("v1", key((byte) 0x31));
        final CursorKeyRing.CursorKey v2 = new CursorKeyRing.CursorKey("v2", key((byte) 0x32));
        final AtomicReference<CursorKeyRing.KeySnapshot> keyRing = new AtomicReference<>(
                new CursorKeyRing.KeySnapshot(v1, List.of()));
        final AtomicInteger contextChecks = new AtomicInteger();
        final Clock clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return ProbeState.this.instant.get(); }
        };
        final OpaqueCursorCodec codec = new OpaqueCursorCodec(clock, keyRing::get);
        final PageLimitPolicy limits = new PageLimitPolicy(2, 3);

        private static byte[] key(byte value) {
            byte[] bytes = new byte[32];
            java.util.Arrays.fill(bytes, value);
            return bytes;
        }
    }

    @RestController
    @RequestMapping(PROBE)
    static class CursorProbeController {
        private static final List<String> ITEMS = List.of("synthetic-item-0", "synthetic-item-1",
                "synthetic-item-2", "synthetic-item-3", "synthetic-item-4");
        private final ProbeState state;

        CursorProbeController(ProbeState state) { this.state = state; }

        @GetMapping("/{context}")
        CursorPage<String> page(@PathVariable String context,
                @RequestParam(required = false) String cursor,
                @RequestParam(required = false) Integer limit) {
            // A real caller authorizes first. This fixture intentionally has no Product authority.
            OpaqueCursorCodec.ExpectedCursorContext expected = expected(context);
            state.contextChecks.incrementAndGet();
            int pageLimit = state.limits.resolve(limit);
            int from = 0;
            if (cursor != null) {
                OpaqueCursorCodec.Continuation continuation = state.codec.decode(cursor, expected);
                from = Math.toIntExact(((OpaqueCursorCodec.SignedLongValue)
                        continuation.position().scalars().getFirst()).value());
            }
            if (from < 0 || from > ITEMS.size()) {
                throw ApiFailureException.of(ApiFailureException.Kind.MALFORMED_REQUEST);
            }
            int to = Math.min(from + pageLimit, ITEMS.size());
            String next = to < ITEMS.size() ? state.codec.encode(expected,
                    new OpaqueCursorCodec.OrderingTuple(List.of(
                            new OpaqueCursorCodec.SignedLongValue(to))), Duration.ofMinutes(5))
                    : null;
            return new CursorPage<>(ITEMS.subList(from, to), next);
        }

        private static OpaqueCursorCodec.ExpectedCursorContext expected(String name) {
            return switch (name) {
                case "owner" -> context("SYNTHETIC_OWNER_LIST", "synthetic-private-identity",
                        "synthetic-private-filter", "CREATED_DESC");
                case "public" -> context("SYNTHETIC_PUBLIC_LIST", "synthetic-private-identity",
                        "synthetic-private-filter", "CREATED_DESC");
                case "other-scope" -> context("SYNTHETIC_OWNER_LIST", "other-private-identity",
                        "synthetic-private-filter", "CREATED_DESC");
                case "other-filter" -> context("SYNTHETIC_OWNER_LIST", "synthetic-private-identity",
                        "other-private-filter", "CREATED_DESC");
                case "other-sort" -> context("SYNTHETIC_OWNER_LIST", "synthetic-private-identity",
                        "synthetic-private-filter", "UPDATED_ASC");
                default -> throw ApiFailureException.of(
                        ApiFailureException.Kind.RESOURCE_NOT_FOUND);
            };
        }

        private static OpaqueCursorCodec.ExpectedCursorContext context(String route,
                String scope, String filter, String sort) {
            return new OpaqueCursorCodec.ExpectedCursorContext(
                    new OpaqueCursorCodec.RouteFamily(route),
                    new OpaqueCursorCodec.ScopeFingerprint(sha256(scope)),
                    new OpaqueCursorCodec.FilterFingerprint(sha256(filter)),
                    new OpaqueCursorCodec.SortCode(sort));
        }

        private static String sha256(String value) {
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(value.getBytes(StandardCharsets.UTF_8)));
            } catch (NoSuchAlgorithmException exception) {
                throw new AssertionError(exception);
            }
        }
    }
}
