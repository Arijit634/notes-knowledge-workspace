package org.notesknowledge.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.notesknowledge.LeaseOwner;
import org.notesknowledge.LeasePolicy;
import org.notesknowledge.security.RateLimitPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("DATABASE")
@Tag("API")
@Tag("SECURITY")
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@Import(IdentityCoreIntegrationTest.Doubles.class)
@ExtendWith(OutputCaptureExtension.class)
class IdentityCoreIntegrationTest {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            "pgvector/pgvector:0.8.6-pg18-trixie")
            .withDatabaseName("identity_core")
            .withUsername("identity_migrator")
            .withPassword("synthetic-identity-migrator-password");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("identity.delivery.key-base64", () ->
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        registry.add("identity.rate.key-base64", () ->
                "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=");
        registry.add("identity.delivery.public-origin", () -> "https://example.test");
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired SecurityEmailDeliveryRepository delivery;
    @Autowired SecurityEmailWorker worker;
    @Autowired CapturingProvider provider;
    @Autowired Clock clock;
    @Autowired SyntheticRatePort ratePort;
    @Autowired RegistrationService registrationService;
    @Autowired EmailVerificationService verificationService;
    @Autowired SecurityEmailMaterialCipher cipher;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    @Test
    void onlyTheSixAuthorizedProductPathsAreMapped() {
        Set<String> paths = mappings.getHandlerMethods().keySet().stream()
                .flatMap(mapping -> mapping.getPatternValues().stream())
                .filter(path -> path.startsWith("/api/"))
                .collect(java.util.stream.Collectors.toSet());
        assertThat(paths).containsExactlyInAnyOrder(
                "/api/auth/csrf", "/api/auth/session", "/api/auth/registrations",
                "/api/auth/email-verification/requests",
                "/api/auth/email-verification/confirmations",
                "/api/auth/login/password");
    }

    @Test
    void registrationVerificationAndPasswordSessionAreDatabaseBacked(CapturedOutput output)
            throws Exception {
        String email = "wp3a-" + UUID.randomUUID() + "@example.test";
        Browser browser = bootstrap();
        MvcResult registration = mvc.perform(post("/api/auth/registrations")
                .cookie(browser.cookie()).header("X-CSRF-TOKEN", browser.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email.toUpperCase() +
                        "\",\"password\":\"SyntheticPassword-2026!\"}"))
                .andExpect(status().isAccepted()).andReturn();
        assertThat(registration.getResponse().getHeader("Location")).isNull();
        assertThat(registration.getResponse().getContentAsString()).isBlank();
        assertThat(jdbc.queryForObject("""
                select count(*) from identity.account where canonical_email = ?
                """, Integer.class, email)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select user_id from identity.account where canonical_email = ?
                """, UUID.class, email).version()).isEqualTo(7);
        assertThat(jdbc.queryForObject("""
                select c.capability_id from identity.identity_capability c
                join identity.account a on a.user_id = c.user_id
                where a.canonical_email = ?
                """, UUID.class, email).version()).isEqualTo(7);
        String verifier = jdbc.queryForObject("""
                select password_verifier from identity.account where canonical_email = ?
                """, String.class, email);
        assertThat(verifier).startsWith("{argon2id}$argon2id$")
                .doesNotContain("SyntheticPassword-2026!");
        assertThat(passwords.matches("SyntheticPassword-2026!", verifier)).isTrue();
        assertThat(jdbc.queryForObject("""
                select count(*) from identity.security_email_delivery where state = 'queued'
                """, Integer.class)).isPositive();

        var claims = delivery.claimReady(clock.instant(), new LeaseOwner("synthetic_worker"),
                new LeasePolicy(Duration.ofMinutes(2), 10), 10);
        assertThat(claims).isNotEmpty();
        var claim = claims.stream().filter(c -> email.equalsIgnoreCase(
                jdbc.queryForObject("""
                    select a.display_email from identity.account a
                    join identity.identity_capability c on c.user_id = a.user_id
                    where c.capability_id = ?
                    """, String.class, c.capabilityId()))).findFirst().orElseThrow();
        worker.process(claim);
        assertThat(provider.lastRecipient).isEqualToIgnoringCase(email);
        String body = provider.lastMessage.body();
        String token = body.substring(body.indexOf("#token=") + 7,
                body.indexOf("\nThis link"));
        assertThat(jdbc.queryForObject("""
                select encode(verifier_digest, 'hex') from identity.identity_capability
                where capability_id = ?
                """, String.class, claim.capabilityId())).doesNotContain(token);
        assertThat(jdbc.queryForObject("""
                select sealed_token_ciphertext is null from identity.security_email_delivery
                where security_email_delivery_id = ?
                """, Boolean.class, claim.id()))
                .describedAs("delivery state: %s", jdbc.queryForObject("""
                    select state from identity.security_email_delivery
                    where security_email_delivery_id = ?
                    """, String.class, claim.id())).isTrue();

        mvc.perform(post("/api/auth/email-verification/confirmations")
                .cookie(browser.cookie()).header("X-CSRF-TOKEN", browser.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/auth/email-verification/confirmations")
                .cookie(browser.cookie()).header("X-CSRF-TOKEN", browser.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isConflict());

        MvcResult login = mvc.perform(post("/api/auth/login/password")
                .cookie(browser.cookie()).header("X-CSRF-TOKEN", browser.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email +
                        "\",\"password\":\"SyntheticPassword-2026!\"}"))
                .andExpect(status().isOk()).andReturn();
        assertThat(login.getResponse().getContentAsString()).contains("authenticated");
        Cookie loginCookie = login.getResponse().getCookie("SESSION");
        assertThat(loginCookie).isNotNull();
        assertThat(loginCookie.getValue()).isNotEqualTo(browser.cookie().getValue());
        mvc.perform(get("/api/auth/session").cookie(loginCookie))
                .andExpect(status().isOk());
        MvcResult refreshed = mvc.perform(get("/api/auth/csrf").cookie(loginCookie))
                .andExpect(status().isOk()).andReturn();
        assertThat(refreshed.getResponse().getContentAsString()).doesNotContain(browser.csrf());
        jdbc.update("""
                update identity.account set account_state = 'suspended'
                where canonical_email = ?
                """, email);
        assertThat(mvc.perform(get("/api/auth/session").cookie(loginCookie))
                .andReturn().getResponse().getContentAsString()).contains("anonymous");
        assertThat(output.getAll()).doesNotContain(email, token, "SyntheticPassword-2026!");
    }

    @Test
    void unknownVerificationRequestIsBlindAndCreatesNoAuthority() throws Exception {
        Browser browser = bootstrap();
        int before = jdbc.queryForObject(
                "select count(*) from identity.identity_capability", Integer.class);
        var response = mvc.perform(post("/api/auth/email-verification/requests")
                .cookie(browser.cookie()).header("X-CSRF-TOKEN", browser.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"unknown-" + UUID.randomUUID() + "@example.test\"}"))
                .andExpect(status().isAccepted()).andReturn().getResponse();
        assertThat(response.getHeader("Location")).isNull();
        assertThat(jdbc.queryForObject(
                "select count(*) from identity.identity_capability", Integer.class)).isEqualTo(before);
    }

    @Test
    void resendSupersedesOldAuthorityAndDuplicateRegistrationStaysBlind() throws Exception {
        ratePort.decision = new RateLimitPort.Allowed();
        String email = "wp3a-resend-" + UUID.randomUUID() + "@example.test";
        Browser browser = bootstrap();
        String registration = "{\"email\":\"" + email +
                "\",\"password\":\"SyntheticPassword-2026!\"}";
        mvc.perform(post("/api/auth/registrations")
                .cookie(browser.cookie()).header("X-CSRF-TOKEN", browser.csrf())
                .contentType(MediaType.APPLICATION_JSON).content(registration))
                .andExpect(status().isAccepted());
        UUID old = jdbc.queryForObject("""
                select c.capability_id from identity.identity_capability c
                join identity.account a on a.user_id = c.user_id
                where a.canonical_email = ? and c.superseded_at is null
                """, UUID.class, email);
        String oldToken = currentToken(email);
        var oldClaim = delivery.claimReady(clock.instant(), new LeaseOwner("synthetic_before_resend"),
                new LeasePolicy(Duration.ofMinutes(2), 10), 10)
                .stream().filter(c -> c.capabilityId().equals(old)).findFirst().orElseThrow();
        mvc.perform(post("/api/auth/registrations")
                .cookie(browser.cookie()).header("X-CSRF-TOKEN", browser.csrf())
                .contentType(MediaType.APPLICATION_JSON).content(registration))
                .andExpect(status().isAccepted());
        assertThat(jdbc.queryForObject("""
                select count(*) from identity.account where canonical_email = ?
                """, Integer.class, email)).isEqualTo(1);
        mvc.perform(post("/api/auth/email-verification/requests")
                .cookie(browser.cookie()).header("X-CSRF-TOKEN", browser.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isAccepted());
        assertThat(jdbc.queryForObject("""
                select superseded_at is not null from identity.identity_capability
                where capability_id = ?
                """, Boolean.class, old)).isTrue();
        assertThat(jdbc.queryForObject("""
                select state from identity.security_email_delivery where capability_id = ?
                """, String.class, old)).isEqualTo("obsolete");
        assertThat(jdbc.queryForObject("""
                select sealed_token_ciphertext is null from identity.security_email_delivery
                where capability_id = ?
                """, Boolean.class, old)).isTrue();
        assertThatThrownBy(() -> verificationService.confirm(oldToken))
                .isInstanceOf(org.notesknowledge.websupport.ApiFailureException.class);
        int beforeDispatch = provider.submissions.get();
        worker.process(oldClaim);
        assertThat(provider.submissions.get()).isEqualTo(beforeDispatch);
    }

    @Test
    void criticalRateRejectionAndUnavailableBackingFailClosed() throws Exception {
        Browser browser = bootstrap();
        String body = "{\"email\":\"synthetic@example.test\","
                + "\"password\":\"SyntheticPassword-2026!\"}";
        try {
            ratePort.decision = new RateLimitPort.Throttled(17);
            mvc.perform(post("/api/auth/login/password")
                    .cookie(browser.cookie()).header("X-CSRF-TOKEN", browser.csrf())
                    .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isTooManyRequests());
            ratePort.decision = new RateLimitPort.ControlUnavailable();
            mvc.perform(post("/api/auth/login/password")
                    .cookie(browser.cookie()).header("X-CSRF-TOKEN", browser.csrf())
                    .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isServiceUnavailable());
        } finally {
            ratePort.decision = new RateLimitPort.Allowed();
        }
    }

    @Test
    void unverifiedAndWrongPasswordLoginShareGenericDenialAndWeakHashUpgrades() throws Exception {
        String email = "wp3a-login-" + UUID.randomUUID() + "@example.test";
        registrationService.begin(email, "SyntheticPassword-2026!");
        Browser browser = bootstrap();
        String base = "{\"email\":\"" + email + "\",\"password\":\"";
        mvc.perform(post("/api/auth/login/password")
                .cookie(browser.cookie()).header("X-CSRF-TOKEN", browser.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(base + "SyntheticPassword-2026!\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login/password")
                .cookie(browser.cookie()).header("X-CSRF-TOKEN", browser.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(base + "WrongSyntheticPassword!\"}"))
                .andExpect(status().isUnauthorized());
        String token = currentToken(email);
        verificationService.confirm(token);
        String weak = "{argon2id}" + new Argon2PasswordEncoder(16, 32, 1, 16_384, 2)
                .encode("SyntheticPassword-2026!");
        jdbc.update("update identity.account set password_verifier = ? where canonical_email = ?",
                weak, email);
        mvc.perform(post("/api/auth/login/password")
                .cookie(browser.cookie()).header("X-CSRF-TOKEN", browser.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(base + "SyntheticPassword-2026!\"}"))
                .andExpect(status().isOk());
        String upgraded = jdbc.queryForObject("""
                select password_verifier from identity.account where canonical_email = ?
                """, String.class, email);
        assertThat(upgraded).isNotEqualTo(weak).contains("m=65536,t=3,p=1");
    }

    @Test
    void concurrentConfirmationConsumesAtMostOnce() throws Exception {
        String email = "wp3a-race-" + UUID.randomUUID() + "@example.test";
        registrationService.begin(email, "SyntheticPassword-2026!");
        String token = currentToken(email);
        var start = new CountDownLatch(1);
        var success = new AtomicInteger();
        var rejected = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> confirmOnLatch(start, token, success, rejected));
            var second = executor.submit(() -> confirmOnLatch(start, token, success, rejected));
            start.countDown();
            first.get(20, TimeUnit.SECONDS);
            second.get(20, TimeUnit.SECONDS);
        }
        assertThat(success.get()).isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select count(*) from identity.identity_capability c
                join identity.account a on a.user_id = c.user_id
                where a.canonical_email = ? and c.consumed_at is not null
                """, Integer.class, email)).isEqualTo(1);
    }

    @Test
    void canonicalEmailRaceCommitsExactlyOneCompleteRegistration() throws Exception {
        String email = "wp3a-unique-" + UUID.randomUUID() + "@example.test";
        var start = new CountDownLatch(1);
        var success = new AtomicInteger();
        var collided = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> registerOnLatch(start, email, success, collided));
            var second = executor.submit(() -> registerOnLatch(start, email.toUpperCase(),
                    success, collided));
            start.countDown();
            first.get(20, TimeUnit.SECONDS);
            second.get(20, TimeUnit.SECONDS);
        }
        assertThat(success.get()).isEqualTo(1);
        assertThat(collided.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select count(*) from identity.account where canonical_email = ?
                """, Integer.class, email)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select count(*) from identity.identity_capability c
                join identity.account a on a.user_id = c.user_id
                where a.canonical_email = ?
                """, Integer.class, email)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select count(*) from identity.security_email_delivery d
                join identity.identity_capability c on c.capability_id = d.capability_id
                join identity.account a on a.user_id = c.user_id
                where a.canonical_email = ?
                """, Integer.class, email)).isEqualTo(1);
    }

    @Test
    void reclaimChangesFenceAndRejectsStaleFinalization() {
        String email = "wp3a-fence-" + UUID.randomUUID() + "@example.test";
        registrationService.begin(email, "SyntheticPassword-2026!");
        Instant now = clock.instant();
        LeasePolicy policy = new LeasePolicy(Duration.ofSeconds(5), 10);
        var first = delivery.claimReady(now, new LeaseOwner("synthetic_one"), policy, 10)
                .stream().filter(c -> accountEmail(c.capabilityId()).equals(email))
                .findFirst().orElseThrow();
        var second = delivery.reclaimExpired(now.plusSeconds(6),
                new LeaseOwner("synthetic_two"), policy, 10)
                .stream().filter(c -> c.id().equals(first.id())).findFirst().orElseThrow();
        assertThat(first.token()).isNotEqualTo(second.token());
        assertThat(delivery.submitted(first, now.plusSeconds(7))).isFalse();
        assertThat(delivery.submitted(second, now.plusSeconds(7))).isTrue();
        assertThat(jdbc.queryForObject("""
                select sealed_token_ciphertext is null from identity.security_email_delivery
                where security_email_delivery_id = ?
                """, Boolean.class, first.id())).isTrue();
    }

    @Test
    void boundedAttemptsReachTerminalFailureAndEraseEnvelope() {
        String email = "wp3a-limit-" + UUID.randomUUID() + "@example.test";
        registrationService.begin(email, "SyntheticPassword-2026!");
        Instant at = clock.instant();
        LeasePolicy policy = new LeasePolicy(Duration.ofSeconds(5), 10);
        SecurityEmailDeliveryRepository.Claim last = null;
        for (int attempt = 1; attempt <= 5; attempt++) {
            final int current = attempt;
            last = delivery.claimReady(at, new LeaseOwner("synthetic_retry"), policy, 10)
                    .stream().filter(c -> accountEmail(c.capabilityId()).equals(email))
                    .findFirst().orElseThrow();
            assertThat(last.attempt()).isEqualTo(current);
            if (attempt < 5) {
                assertThat(delivery.retry(last, at, at.plusSeconds(1), "provider_failure"))
                        .isTrue();
                at = at.plusSeconds(2);
            }
        }
        assertThat(delivery.failExhausted(at.plusSeconds(6), 10)).isPositive();
        UUID id = last.id();
        assertThat(jdbc.queryForObject("""
                select state from identity.security_email_delivery
                where security_email_delivery_id = ?
                """, String.class, id)).isEqualTo("failed");
        assertThat(jdbc.queryForObject("""
                select sealed_token_ciphertext is null from identity.security_email_delivery
                where security_email_delivery_id = ?
                """, Boolean.class, id)).isTrue();
    }

    @Test
    void ambiguousProviderAcceptanceCanDuplicateMailButNotCapabilityAuthority() {
        String email = "wp3a-duplicate-mail-" + UUID.randomUUID() + "@example.test";
        registrationService.begin(email, "SyntheticPassword-2026!");
        Instant now = clock.instant();
        LeasePolicy policy = new LeasePolicy(Duration.ofMinutes(2), 10);
        var first = delivery.claimReady(now, new LeaseOwner("synthetic_ambiguous"), policy, 10)
                .stream().filter(c -> accountEmail(c.capabilityId()).equals(email))
                .findFirst().orElseThrow();
        int submissionsBefore = provider.submissions.get();
        provider.throwAfterCapture = true;
        try {
            worker.process(first);
        } finally {
            provider.throwAfterCapture = false;
        }
        assertThat(provider.submissions.get()).isEqualTo(submissionsBefore + 1);
        String firstBody = provider.lastMessage.body();
        var second = delivery.claimReady(now.plusSeconds(31),
                new LeaseOwner("synthetic_retry_worker"), policy, 10)
                .stream().filter(c -> c.id().equals(first.id())).findFirst().orElseThrow();
        worker.process(second);
        assertThat(provider.submissions.get()).isEqualTo(submissionsBefore + 2);
        assertThat(provider.lastMessage.body()).isEqualTo(firstBody);
        String token = firstBody.substring(firstBody.indexOf("#token=") + 7,
                firstBody.indexOf("\nThis link"));
        verificationService.confirm(token);
        assertThatThrownBy(() -> verificationService.confirm(token))
                .isInstanceOf(org.notesknowledge.websupport.ApiFailureException.class);
    }

    @Test
    void malformedWrongPurposeExpiredAndMissingCsrfNeverVerifyAccount() throws Exception {
        String email = "wp3a-invalid-" + UUID.randomUUID() + "@example.test";
        registrationService.begin(email, "SyntheticPassword-2026!");
        String token = currentToken(email);
        UUID capabilityId = VerificationToken.locator(token);
        Browser browser = bootstrap();
        mvc.perform(post("/api/auth/email-verification/confirmations")
                .cookie(browser.cookie()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/email-verification/confirmations")
                .cookie(browser.cookie()).header("X-CSRF-TOKEN", browser.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"invalid\"}"))
                .andExpect(status().isUnprocessableContent());
        jdbc.update("""
                update identity.identity_capability set purpose = 'password_reset'
                where capability_id = ?
                """, capabilityId);
        assertThatThrownBy(() -> verificationService.confirm(token))
                .isInstanceOf(org.notesknowledge.websupport.ApiFailureException.class);
        jdbc.update("""
                update identity.identity_capability set purpose = 'email_verification',
                    issued_at = now() - interval '2 days',
                    expires_at = now() - interval '1 day'
                where capability_id = ?
                """, capabilityId);
        assertThatThrownBy(() -> verificationService.confirm(token))
                .isInstanceOf(org.notesknowledge.websupport.ApiFailureException.class);
        assertThat(jdbc.queryForObject("""
                select account_state from identity.account where canonical_email = ?
                """, String.class, email)).isEqualTo("pending_verification");
    }

    private void confirmOnLatch(CountDownLatch latch, String token,
            AtomicInteger success, AtomicInteger rejected) {
        try {
            latch.await();
            verificationService.confirm(token);
            success.incrementAndGet();
        } catch (org.notesknowledge.websupport.ApiFailureException exception) {
            rejected.incrementAndGet();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private void registerOnLatch(CountDownLatch latch, String email,
            AtomicInteger success, AtomicInteger collided) {
        try {
            latch.await();
            registrationService.begin(email, "SyntheticPassword-2026!");
            success.incrementAndGet();
        } catch (org.springframework.dao.DataIntegrityViolationException exception) {
            collided.incrementAndGet();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private String accountEmail(UUID capabilityId) {
        return jdbc.queryForObject("""
                select a.canonical_email from identity.account a
                join identity.identity_capability c on c.user_id = a.user_id
                where c.capability_id = ?
                """, String.class, capabilityId);
    }

    private String currentToken(String email) {
        return jdbc.query("""
                select d.capability_id, d.sealed_token_ciphertext, d.sealed_token_nonce,
                       d.sealed_token_tag, d.token_key_version
                from identity.security_email_delivery d
                join identity.identity_capability c on c.capability_id = d.capability_id
                join identity.account a on a.user_id = c.user_id
                where a.canonical_email = ? and d.state = 'queued'
                """, rs -> {
                    rs.next();
                    UUID id = rs.getObject("capability_id", UUID.class);
                    return cipher.open(id, new SecurityEmailMaterialCipher.Envelope(
                            rs.getBytes("sealed_token_ciphertext"),
                            rs.getBytes("sealed_token_nonce"), rs.getBytes("sealed_token_tag"),
                            rs.getString("token_key_version")));
                }, email);
    }

    private Browser bootstrap() throws Exception {
        MvcResult result = mvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk()).andReturn();
        Cookie cookie = result.getResponse().getCookie("SESSION");
        assertThat(cookie).isNotNull();
        String json = result.getResponse().getContentAsString();
        String token = json.substring(json.indexOf(":\"") + 2, json.lastIndexOf('"'));
        return new Browser(cookie, token);
    }

    record Browser(Cookie cookie, String csrf) { }

    static final class CapturingProvider implements SecurityEmailProviderPort {
        volatile String lastRecipient;
        volatile SecurityEmailMessageRenderer.Message lastMessage;
        final AtomicInteger submissions = new AtomicInteger();
        volatile boolean throwAfterCapture;

        @Override public void submit(String recipient, SecurityEmailMessageRenderer.Message message) {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager
                    .isActualTransactionActive()).isFalse();
            lastRecipient = recipient;
            lastMessage = message;
            submissions.incrementAndGet();
            if (throwAfterCapture) {
                throw new IllegalStateException("synthetic ambiguous acceptance");
            }
        }
    }

    static final class SyntheticRatePort implements RateLimitPort {
        volatile RateLimitPort.Decision decision = new RateLimitPort.Allowed();
        @Override public Decision evaluate(Request request) { return decision; }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Doubles {
        @Bean @Primary SyntheticRatePort syntheticAllowingRatePort() {
            return new SyntheticRatePort();
        }
        @Bean CapturingProvider capturingProvider() { return new CapturingProvider(); }
    }
}
