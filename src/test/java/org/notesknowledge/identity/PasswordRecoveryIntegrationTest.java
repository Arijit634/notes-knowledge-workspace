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
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import org.notesknowledge.DatabaseUuidV7Generator;
import org.notesknowledge.security.RateLimitPort;
import org.notesknowledge.security.RateKeyDeriver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
class PasswordRecoveryIntegrationTest {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            "pgvector/pgvector:0.8.6-pg18-trixie")
            .withDatabaseName("password_recovery")
            .withUsername("identity_migrator")
            .withPassword("synthetic-identity-migrator-password");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("identity.delivery.key-base64", () ->
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        registry.add("identity.mfa.key-base64", () ->
                "AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=");
        registry.add("identity.mfa.handle-key-base64", () ->
                "AwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwM=");
        registry.add("identity.rate.key-base64", () ->
                "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=");
        registry.add("identity.delivery.public-origin", () -> "https://example.test");
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired RegistrationService registration;
    @Autowired EmailVerificationService verification;
    @Autowired PasswordRecoveryService recovery;
    @Autowired SecurityEmailMaterialCipher cipher;
    @Autowired SecurityEmailDeliveryRepository delivery;
    @Autowired SecurityEmailWorker worker;
    @Autowired IdentityCoreIntegrationTest.CapturingProvider provider;
    @Autowired IdentityCoreIntegrationTest.SyntheticRatePort rates;
    @Autowired PasswordEncoder passwords;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcIndexedSessionRepository sessions;
    @Autowired Clock clock;
    @Autowired DatabaseUuidV7Generator ids;
    @Autowired OidcIdentityRepository oidc;
    @Autowired IdentityPersistence identity;
    @Autowired SecurityEmailMessageRenderer renderer;
    @Autowired ObjectProvider<SecurityEmailProviderPort> providerPort;
    @Autowired SecurityEmailDeliveryProperties deliverySettings;
    @Autowired RateLimitPort capacity;
    @Autowired RateKeyDeriver capacityKeys;
    @Autowired MfaRepository mfa;
    @Autowired MfaSecretCipher mfaCipher;

    @Test
    void requestIsBlindAndSupersedesOnlyEligibleAuthority(CapturedOutput output) throws Exception {
        String eligible = activeAccount();
        String pending = "pending-" + UUID.randomUUID() + "@example.test";
        registration.begin(pending, "SyntheticPassword-2026!");
        String unknown = "unknown-" + UUID.randomUUID() + "@example.test";
        Browser browser = bootstrap();
        int beforeMail = provider.submissions.get();
        var known = request(browser, eligible);
        var missing = request(browser, unknown);
        var ineligible = request(browser, pending);
        for (var response : new org.springframework.mock.web.MockHttpServletResponse[] {
                known, missing, ineligible }) {
            assertThat(response.getContentAsString()).isBlank();
            assertThat(response.getHeader("Location")).isNull();
            assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        }
        assertThat(provider.submissions.get()).isEqualTo(beforeMail);
        assertThat(resetCount(eligible)).isEqualTo(1);
        assertThat(resetCount(unknown)).isZero();
        assertThat(resetCount(pending)).isZero();
        String first = resetToken(eligible);
        UUID firstId = PasswordResetToken.locator(first);
        request(browser, eligible);
        assertThat(resetCount(eligible)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                select superseded_at is not null from identity.identity_capability
                where capability_id = ?
                """, Boolean.class, firstId)).isTrue();
        assertThat(jdbc.queryForObject("""
                select state from identity.security_email_delivery where capability_id = ?
                """, String.class, firstId)).isEqualTo("obsolete");
        assertThatThrownBy(() -> recovery.confirm(first, "NewSyntheticPassword-2026!"))
                .isInstanceOf(org.notesknowledge.websupport.ApiFailureException.class);
        String current = resetToken(eligible);
        var link = delivery.claimReady(clock.instant(), new LeaseOwner("reset_link"),
                new LeasePolicy(Duration.ofMinutes(2), 10), 10).stream()
                .filter(claim -> PasswordResetToken.locator(current).equals(claim.capabilityId()))
                .findFirst().orElseThrow();
        worker.process(link);
        assertThat(provider.lastRecipient).isEqualTo(eligible);
        assertThat(provider.lastMessage.body()).contains("/reset-password#token=")
                .doesNotContain("SyntheticPassword-2026!");
        assertThat(output.getAll()).doesNotContain(first, eligible,
                "NewSyntheticPassword-2026!");
    }

    @Test
    void requestTargetClassesSharePublicShapeAndCoarseTiming() throws Exception {
        String eligible = activeAccount();
        String pending = "reset-pending-" + UUID.randomUUID() + "@example.test";
        registration.begin(pending, "SyntheticPassword-2026!");
        String unknown = "reset-unknown-" + UUID.randomUUID() + "@example.test";
        Browser browser = bootstrap();
        Map<String, List<Long>> elapsed = Map.of(eligible, new ArrayList<>(),
                pending, new ArrayList<>(), unknown, new ArrayList<>());
        List<String> targets = List.of(eligible, pending, unknown);
        for (int round = 0; round < 3; round++) {
            for (int offset = 0; offset < targets.size(); offset++) {
                String email = targets.get((round + offset) % targets.size());
                long start = System.nanoTime();
                var response = request(browser, email);
                elapsed.get(email).add(System.nanoTime() - start);
                assertThat(response.getContentAsString()).isBlank();
                assertThat(response.getHeader("Location")).isNull();
                assertThat(response.getHeader("Content-Type")).isNull();
                assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
            }
        }
        var medians = elapsed.values().stream().map(samples -> samples.stream()
                .mapToLong(Long::longValue).sorted().skip(1).findFirst().orElseThrow()).toList();
        long shortest = medians.stream().mapToLong(Long::longValue).min().orElseThrow();
        long longest = medians.stream().mapToLong(Long::longValue).max().orElseThrow();
        assertThat(longest).isLessThan(shortest * 25);
        assertThat(resetCount(eligible)).isEqualTo(3);
        assertThat(resetCount(pending)).isZero();
        assertThat(resetCount(unknown)).isZero();
    }

    @Test
    void resetRevokesPasswordSessionsAndQueuesDispatchableNotice() throws Exception {
        String email = activeAccount();
        Browser browser = bootstrap();
        Cookie prior = login(browser, email, "SyntheticPassword-2026!");
        Browser resetBrowser = bootstrap();
        UUID userId = jdbc.queryForObject(
                "select user_id from identity.account where canonical_email = ?", UUID.class, email);
        String oldOidcSession = persistedSession(userId, "ROLE_USER", true);
        String preMfaSession = persistedSession(userId, "ROLE_MFA_PENDING", false);
        jdbc.update("""
                update identity.spring_session set principal_name = ? where session_id = ?
                """, "IdentitySessionPrincipal[REDACTED]", oldOidcSession);
        assertThat(jdbc.queryForObject("""
                select count(*) from identity.spring_session where principal_name = ?
                """, Integer.class, userId.toString())).isPositive();
        request(resetBrowser, email);
        String token = resetToken(email);
        var response = mvc.perform(post("/api/auth/password-reset/confirmations")
                .cookie(resetBrowser.cookie()).header("X-CSRF-TOKEN", resetBrowser.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token
                        + "\",\"newPassword\":\"NewSyntheticPassword-2026!\"}"))
                .andExpect(status().isNoContent()).andReturn().getResponse();
        assertThat(response.getCookie("SESSION")).isNull();
        assertThat(mvc.perform(get("/api/auth/session").cookie(prior))
                .andReturn().getResponse().getContentAsString()).contains("anonymous");
        assertThat(jdbc.queryForObject("""
                select count(*) from identity.spring_session where principal_name = ?
                """, Integer.class, userId.toString())).isZero();
        assertThat(sessions.findById(oldOidcSession)).isNull();
        assertThat(sessions.findById(preMfaSession)).isNull();
        String verifier = jdbc.queryForObject("""
                select password_verifier from identity.account where user_id = ?
                """, String.class, userId);
        assertThat(passwords.matches("NewSyntheticPassword-2026!", verifier)).isTrue();
        assertThat(passwords.matches("SyntheticPassword-2026!", verifier)).isFalse();
        assertThat(jdbc.queryForObject("""
                select count(*) from identity.security_email_delivery
                where subject_user_id = ? and delivery_kind = 'security_notice'
                  and notice_kind = 'password_reset_completed' and state = 'queued'
                  and sealed_token_ciphertext is null and sealed_recipient_ciphertext is null
                """, Integer.class, userId)).isEqualTo(1);
        var notice = delivery.claimReady(clock.instant(), new LeaseOwner("reset_notice"),
                new LeasePolicy(Duration.ofMinutes(2), 10), 10).stream()
                .filter(claim -> userId.equals(claim.subjectUserId())).findFirst().orElseThrow();
        worker.process(notice);
        assertThat(provider.lastRecipient).isEqualTo(email);
        assertThat(provider.lastMessage.body()).contains("password was reset")
                .doesNotContain(token, "NewSyntheticPassword-2026!");
        assertThat(jdbc.queryForObject("""
                select state from identity.security_email_delivery
                where security_email_delivery_id = ?
                """, String.class, notice.id())).isEqualTo("submitted");
    }

    @Test
    void oneTransactionRollsBackConsumedTokenPasswordSessionAuditAndNotice() throws Exception {
        String email = activeAccount();
        Browser browser = bootstrap();
        Cookie prior = login(browser, email, "SyntheticPassword-2026!");
        request(bootstrap(), email);
        String token = resetToken(email);
        UUID capability = PasswordResetToken.locator(token);
        UUID userId = jdbc.queryForObject(
                "select user_id from identity.account where canonical_email = ?", UUID.class, email);
        int auditBefore = jdbc.queryForObject("""
                select count(*) from identity.security_audit_fact
                where target_user_id = ? and event_category = 'password_reset'
                  and outcome_code = 'completed'
                """, Integer.class, userId);
        var transaction = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            recovery.confirm(token, "NewSyntheticPassword-2026!");
            throw new IllegalStateException("synthetic transaction abort");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("""
                select consumed_at is null from identity.identity_capability where capability_id = ?
                """, Boolean.class, capability)).isTrue();
        assertThat(jdbc.queryForObject("""
                select password_verifier from identity.account where user_id = ?
                """, String.class, userId)).satisfies(verifier ->
                assertThat(passwords.matches("SyntheticPassword-2026!", verifier)).isTrue());
        assertThat(mvc.perform(get("/api/auth/session").cookie(prior))
                .andReturn().getResponse().getContentAsString()).contains("authenticated");
        assertThat(jdbc.queryForObject("""
                select count(*) from identity.security_audit_fact
                where target_user_id = ? and event_category = 'password_reset'
                  and outcome_code = 'completed'
                """, Integer.class, userId)).isEqualTo(auditBefore);
        assertThat(jdbc.queryForObject("""
                select count(*) from identity.security_email_delivery
                where subject_user_id = ? and notice_kind = 'password_reset_completed'
                """, Integer.class, userId)).isZero();
    }

    @Test
    void concurrentConfirmationConsumesExactlyOnce() throws Exception {
        String email = activeAccount();
        Browser browser = bootstrap();
        request(browser, email);
        String token = resetToken(email);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < 2; i++) pool.submit(() -> {
                try {
                    start.await();
                    recovery.confirm(token, "NewSyntheticPassword-2026!");
                    success.incrementAndGet();
                } catch (org.notesknowledge.websupport.ApiFailureException failure) {
                    rejected.incrementAndGet();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
        UUID userId = jdbc.queryForObject(
                "select user_id from identity.account where canonical_email = ?", UUID.class, email);
        assertThat(success).hasValue(1);
        assertThat(rejected).hasValue(1);
        assertThat(jdbc.queryForObject("""
                select count(*) from identity.security_email_delivery
                where subject_user_id = ? and notice_kind = 'password_reset_completed'
                """, Integer.class, userId)).isEqualTo(1);
    }

    @Test
    void oidcOnlyAccountCanEstablishPasswordWithoutRemovingLink() throws Exception {
        String email = "oidc-reset-" + UUID.randomUUID() + "@example.test";
        UUID userId = ids.generate();
        String subject = "synthetic-subject-" + UUID.randomUUID();
        assertThat(oidc.createVerifiedAccount(userId, email, email, clock.instant())).isTrue();
        assertThat(oidc.createLink(userId, "https://accounts.google.com", subject,
                clock.instant())).isTrue();
        assertThat(jdbc.queryForObject("""
                select password_verifier is null from identity.account where user_id = ?
                """, Boolean.class, userId)).isTrue();
        request(bootstrap(), email);
        recovery.confirm(resetToken(email), "NewSyntheticPassword-2026!");
        String verifier = jdbc.queryForObject("""
                select password_verifier from identity.account where user_id = ?
                """, String.class, userId);
        assertThat(passwords.matches("NewSyntheticPassword-2026!", verifier)).isTrue();
        assertThat(jdbc.queryForObject("""
                select count(*) from identity.external_identity_link
                where user_id = ? and issuer = 'https://accounts.google.com'
                  and subject = ? and revoked_at is null
                """, Integer.class, userId, subject)).isEqualTo(1);
    }

    @Test
    void resetPreservesActiveMfaAndNewPasswordStillRequiresSecondFactor() throws Exception {
        String email = activeAccount();
        UUID userId = jdbc.queryForObject(
                "select user_id from identity.account where canonical_email = ?", UUID.class, email);
        var seed = mfaCipher.seal(userId, new byte[20]);
        assertThat(mfa.begin(userId, seed, clock.instant())).isEqualTo(1);
        assertThat(mfa.activate(userId, seed.nonce(), 0, clock.instant())).isEqualTo(1);
        request(bootstrap(), email);
        recovery.confirm(resetToken(email), "NewSyntheticPassword-2026!");
        assertThat(mfa.configuration(userId).orElseThrow().state()).isEqualTo("active");
        Browser browser = bootstrap();
        mvc.perform(post("/api/auth/login/password")
                .cookie(browser.cookie()).header("X-CSRF-TOKEN", browser.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email
                        + "\",\"password\":\"SyntheticPassword-2026!\"}"))
                .andExpect(status().isUnauthorized());
        var newLogin = mvc.perform(post("/api/auth/login/password")
                .cookie(browser.cookie()).header("X-CSRF-TOKEN", browser.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email
                        + "\",\"password\":\"NewSyntheticPassword-2026!\"}"))
                .andExpect(status().isAccepted()).andReturn().getResponse();
        assertThat(newLogin.getContentAsString()).contains("mfaRequired", "challengeId");
    }

    @Test
    void simultaneousRequestsLeaveOneCurrentResetCapability() throws Exception {
        String email = activeAccount();
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger issued = new AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(2)) {
            for (int index = 0; index < 2; index++) pool.submit(() -> {
                try {
                    start.await();
                    recovery.request(email);
                    issued.incrementAndGet();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(issued).hasValue(2);
        assertThat(resetCount(email)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                select count(*) from identity.identity_capability c
                join identity.account a on a.user_id = c.user_id
                where a.canonical_email = ? and c.purpose = 'password_reset'
                  and c.consumed_at is null and c.superseded_at is null
                  and c.revoked_at is null
                """, Integer.class, email)).isEqualTo(1);
    }

    @Test
    void resetNoticeRetryUsesTheExistingLeaseFence() throws Exception {
        String email = activeAccount();
        request(bootstrap(), email);
        recovery.confirm(resetToken(email), "NewSyntheticPassword-2026!");
        UUID userId = jdbc.queryForObject(
                "select user_id from identity.account where canonical_email = ?", UUID.class, email);
        LeasePolicy lease = new LeasePolicy(Duration.ofMinutes(2), 10);
        var first = delivery.claimReady(clock.instant(), new LeaseOwner("notice_first"), lease, 10)
                .stream().filter(claim -> userId.equals(claim.subjectUserId()))
                .findFirst().orElseThrow();
        provider.nextOutcome = SecurityEmailProviderPort.Outcome.RETRYABLE;
        try {
            worker.process(first);
            assertThat(jdbc.queryForObject("""
                    select state from identity.security_email_delivery
                    where security_email_delivery_id = ?
                    """, String.class, first.id())).isEqualTo("retry_wait");
            Instant retryAt = jdbc.queryForObject("""
                    select next_attempt_at from identity.security_email_delivery
                    where security_email_delivery_id = ?
                    """, java.sql.Timestamp.class, first.id()).toInstant().plusMillis(1);
            var second = delivery.claimReady(retryAt, new LeaseOwner("notice_second"), lease, 10)
                    .stream().filter(claim -> claim.id().equals(first.id()))
                    .findFirst().orElseThrow();
            assertThat(delivery.submitted(first, retryAt)).isFalse();
            provider.nextOutcome = SecurityEmailProviderPort.Outcome.SUBMITTED;
            workerAt(retryAt).process(second);
            assertThat(jdbc.queryForObject("""
                    select state from identity.security_email_delivery
                    where security_email_delivery_id = ?
                    """, String.class, second.id())).isEqualTo("submitted");
        } finally {
            provider.nextOutcome = SecurityEmailProviderPort.Outcome.SUBMITTED;
        }
    }

    @Test
    void resetNoticePermanentAndAmbiguousOutcomesStayFenced() throws Exception {
        LeasePolicy lease = new LeasePolicy(Duration.ofMinutes(2), 10);
        String permanentEmail = activeAccount();
        request(bootstrap(), permanentEmail);
        recovery.confirm(resetToken(permanentEmail), "NewSyntheticPassword-2026!");
        UUID permanentUser = jdbc.queryForObject(
                "select user_id from identity.account where canonical_email = ?",
                UUID.class, permanentEmail);
        var permanent = delivery.claimReady(clock.instant(),
                new LeaseOwner("notice_permanent"), lease, 10).stream()
                .filter(claim -> permanentUser.equals(claim.subjectUserId()))
                .findFirst().orElseThrow();
        provider.nextOutcome = SecurityEmailProviderPort.Outcome.NON_RETRYABLE;
        try {
            worker.process(permanent);
        } finally {
            provider.nextOutcome = SecurityEmailProviderPort.Outcome.SUBMITTED;
        }
        assertThat(jdbc.queryForObject("""
                select state from identity.security_email_delivery
                where security_email_delivery_id = ?
                """, String.class, permanent.id())).isEqualTo("failed");

        String ambiguousEmail = activeAccount();
        request(bootstrap(), ambiguousEmail);
        recovery.confirm(resetToken(ambiguousEmail), "NewSyntheticPassword-2026!");
        UUID ambiguousUser = jdbc.queryForObject(
                "select user_id from identity.account where canonical_email = ?",
                UUID.class, ambiguousEmail);
        var first = delivery.claimReady(clock.instant(),
                new LeaseOwner("notice_ambiguous"), lease, 10).stream()
                .filter(claim -> ambiguousUser.equals(claim.subjectUserId()))
                .findFirst().orElseThrow();
        provider.throwAfterCapture = true;
        try {
            worker.process(first);
        } finally {
            provider.throwAfterCapture = false;
        }
        String firstBody = provider.lastMessage.body();
        Instant retryAt = jdbc.queryForObject("""
                select next_attempt_at from identity.security_email_delivery
                where security_email_delivery_id = ?
                """, java.sql.Timestamp.class, first.id()).toInstant().plusMillis(1);
        var reclaimed = delivery.claimReady(retryAt,
                new LeaseOwner("notice_after_ambiguity"), lease, 10).stream()
                .filter(claim -> claim.id().equals(first.id())).findFirst().orElseThrow();
        workerAt(retryAt).process(reclaimed);
        assertThat(provider.lastMessage.body()).isEqualTo(firstBody);
        assertThat(jdbc.queryForObject("""
                select state from identity.security_email_delivery
                where security_email_delivery_id = ?
                """, String.class, first.id())).isEqualTo("submitted");
    }

    @Test
    void expiredNoticeClaimIsReclaimedWithFreshFence() throws Exception {
        String email = activeAccount();
        request(bootstrap(), email);
        recovery.confirm(resetToken(email), "NewSyntheticPassword-2026!");
        UUID userId = jdbc.queryForObject(
                "select user_id from identity.account where canonical_email = ?", UUID.class, email);
        LeasePolicy lease = new LeasePolicy(Duration.ofMinutes(2), 1_000);
        var first = delivery.claimReady(clock.instant(), new LeaseOwner("notice_crashed"), lease, 1_000)
                .stream().filter(claim -> userId.equals(claim.subjectUserId()))
                .findFirst().orElseThrow();
        Instant afterExpiry = clock.instant().plus(lease.leaseDuration()).plusSeconds(1);
        var reclaimed = delivery.reclaimExpired(afterExpiry,
                new LeaseOwner("notice_reclaimer"), lease, 1_000).stream()
                .filter(claim -> claim.id().equals(first.id())).findFirst().orElseThrow();
        assertThat(reclaimed.token().value()).isNotEqualTo(first.token().value());
        assertThat(delivery.submitted(first, afterExpiry)).isFalse();
        workerAt(afterExpiry).process(reclaimed);
        assertThat(jdbc.queryForObject("""
                select state from identity.security_email_delivery
                where security_email_delivery_id = ?
                """, String.class, reclaimed.id())).isEqualTo("submitted");
    }

    @Test
    void rateControlAndCsrfFailClosed() throws Exception {
        Browser browser = bootstrap();
        String body = "{\"email\":\"nobody@example.test\"}";
        mvc.perform(post("/api/auth/password-reset/requests")
                .cookie(browser.cookie()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        try {
            rates.decision = new RateLimitPort.Throttled(17);
            mvc.perform(post("/api/auth/password-reset/requests")
                    .cookie(browser.cookie()).header("X-CSRF-TOKEN", browser.csrf())
                    .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isTooManyRequests());
            rates.decision = new RateLimitPort.ControlUnavailable();
            mvc.perform(post("/api/auth/password-reset/requests")
                    .cookie(browser.cookie()).header("X-CSRF-TOKEN", browser.csrf())
                    .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isServiceUnavailable());
        } finally {
            rates.decision = new RateLimitPort.Allowed();
        }
    }

    @Test
    void sourceCandidateAndGlobalCapacityRetainTheirOrdering() throws Exception {
        Browser browser = bootstrap();
        rates.enableThresholds(1, 10, 100);
        try {
            request(browser, "candidate-a@example.test");
            mvc.perform(post("/api/auth/password-reset/requests")
                    .cookie(browser.cookie()).header("X-CSRF-TOKEN", browser.csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"candidate-a@example.test\"}"))
                    .andExpect(status().isTooManyRequests());
            assertThat(rates.count("IDENTITY_GLOBAL", "whole-deployment")).isEqualTo(1);
        } finally {
            rates.disableThresholds();
        }
        rates.enableThresholds(10, 2, 100);
        try {
            for (int index = 0; index < 2; index++) {
                int sourceIndex = index;
                mvc.perform(post("/api/auth/password-reset/requests")
                        .with(req -> { req.setRemoteAddr("192.0.2." + (sourceIndex + 1)); return req; })
                        .cookie(browser.cookie()).header("X-CSRF-TOKEN", browser.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"candidate-" + index + "@example.test\"}"))
                        .andExpect(status().isAccepted());
            }
            mvc.perform(post("/api/auth/password-reset/requests")
                    .with(req -> { req.setRemoteAddr("192.0.2.3"); return req; })
                    .cookie(browser.cookie()).header("X-CSRF-TOKEN", browser.csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"candidate-3@example.test\"}"))
                    .andExpect(status().isTooManyRequests());
        } finally {
            rates.disableThresholds();
        }
    }

    @Test
    void invalidPasswordWrongSecretExpiryAndReplayNeverEstablishAuthority() throws Exception {
        String email = activeAccount();
        request(bootstrap(), email);
        String token = resetToken(email);
        UUID id = PasswordResetToken.locator(token);
        assertThatThrownBy(() -> recovery.confirm(token, "short"))
                .isInstanceOf(org.notesknowledge.websupport.ApiFailureException.class);
        assertThat(jdbc.queryForObject("""
                select consumed_at is null from identity.identity_capability where capability_id = ?
                """, Boolean.class, id)).isTrue();
        String wrong = token.substring(0, token.length() - 1)
                + (token.endsWith("A") ? "B" : "A");
        assertThatThrownBy(() -> recovery.confirm(wrong, "NewSyntheticPassword-2026!"))
                .isInstanceOf(org.notesknowledge.websupport.ApiFailureException.class);
        jdbc.update("""
                update identity.identity_capability
                set issued_at = now() - interval '2 hours',
                    expires_at = now() - interval '1 hour'
                where capability_id = ?
                """, id);
        assertThatThrownBy(() -> recovery.confirm(token, "NewSyntheticPassword-2026!"))
                .isInstanceOf(org.notesknowledge.websupport.ApiFailureException.class);
        jdbc.update("""
                update identity.identity_capability set issued_at = now() - interval '30 minutes',
                    expires_at = now() + interval '30 minutes' where capability_id = ?
                """, id);
        recovery.confirm(token, "NewSyntheticPassword-2026!");
        assertThatThrownBy(() -> recovery.confirm(token, "ThirdSyntheticPassword-2026!"))
                .isInstanceOf(org.notesknowledge.websupport.ApiFailureException.class);
    }

    private String activeAccount() {
        String email = "reset-" + UUID.randomUUID() + "@example.test";
        registration.begin(email, "SyntheticPassword-2026!");
        verification.confirm(verificationToken(email));
        return email;
    }

    private SecurityEmailWorker workerAt(Instant instant) {
        return new SecurityEmailWorker(identity, delivery, cipher, renderer, providerPort,
                Clock.fixed(instant, ZoneOffset.UTC), deliverySettings, capacity, capacityKeys);
    }

    private String verificationToken(String email) {
        return sealedToken(email, "email_verification");
    }

    private String resetToken(String email) {
        return sealedToken(email, "password_reset");
    }

    private String sealedToken(String email, String purpose) {
        return jdbc.query("""
                select d.capability_id, d.sealed_token_ciphertext, d.sealed_token_nonce,
                       d.sealed_token_tag, d.token_key_version
                from identity.security_email_delivery d
                join identity.identity_capability c on c.capability_id = d.capability_id
                join identity.account a on a.user_id = c.user_id
                where a.canonical_email = ? and c.purpose = ? and d.state = 'queued'
                """, rs -> {
            rs.next();
            UUID id = rs.getObject("capability_id", UUID.class);
            return cipher.open(purpose, id, new SecurityEmailMaterialCipher.Envelope(
                    rs.getBytes("sealed_token_ciphertext"), rs.getBytes("sealed_token_nonce"),
                    rs.getBytes("sealed_token_tag"), rs.getString("token_key_version")));
        }, email, purpose);
    }

    private int resetCount(String email) {
        return jdbc.queryForObject("""
                select count(*) from identity.identity_capability c
                join identity.account a on a.user_id = c.user_id
                where a.canonical_email = ? and c.purpose = 'password_reset'
                """, Integer.class, email);
    }

    private org.springframework.mock.web.MockHttpServletResponse request(Browser browser,
            String email) throws Exception {
        return mvc.perform(post("/api/auth/password-reset/requests")
                .cookie(browser.cookie()).header("X-CSRF-TOKEN", browser.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isAccepted()).andReturn().getResponse();
    }

    private Cookie login(Browser browser, String email, String password) throws Exception {
        return mvc.perform(post("/api/auth/login/password")
                .cookie(browser.cookie()).header("X-CSRF-TOKEN", browser.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("SESSION");
    }

    private Browser bootstrap() throws Exception {
        var result = mvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk()).andReturn();
        String json = result.getResponse().getContentAsString();
        return new Browser(result.getResponse().getCookie("SESSION"),
                json.substring(json.indexOf(":\"") + 2, json.lastIndexOf('"')));
    }

    record Browser(Cookie cookie, String csrf) { }

    private String persistedSession(UUID userId, String authority, boolean oidcRecent) {
        @SuppressWarnings({"unchecked", "rawtypes"})
        SessionRepository<Session> repository = (SessionRepository) sessions;
        Session session = repository.createSession();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new IdentitySessionPrincipal(userId), null,
                java.util.List.of(new SimpleGrantedAuthority(authority))));
        session.setAttribute("SPRING_SECURITY_CONTEXT", context);
        if (oidcRecent) {
            session.setAttribute(IdentitySessionState.RECENT_ATTRIBUTE,
                    new IdentitySessionState.RecentAuthentication(userId, clock.instant(), "oidc"));
        }
        repository.save(session);
        return session.getId();
    }
}
