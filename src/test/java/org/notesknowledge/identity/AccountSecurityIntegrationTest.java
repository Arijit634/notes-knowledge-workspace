package org.notesknowledge.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.notesknowledge.security.RateLimitPort;
import org.notesknowledge.LeaseOwner;
import org.notesknowledge.LeasePolicy;
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
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
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
@Import(AccountSecurityIntegrationTest.Doubles.class)
@ExtendWith(OutputCaptureExtension.class)
class AccountSecurityIntegrationTest {
    private static final String OLD = "SyntheticPassword-2026!";
    private static final String NEW = "NewSyntheticPassword-2026!";

    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            "pgvector/pgvector:0.8.6-pg18-trixie")
            .withDatabaseName("account_security")
            .withUsername("identity_migrator")
            .withPassword("synthetic-identity-migrator-password");

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
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
    @Autowired JdbcIndexedSessionRepository sessions;
    @Autowired PasswordEncoder passwords;
    @Autowired Clock clock;
    @Autowired OidcIdentityRepository oidc;
    @Autowired MfaRepository mfa;
    @Autowired MfaSecretCipher mfaCipher;
    @Autowired RecoveryCodeService recoveryCodes;
    @Autowired MfaProperties mfaPolicy;
    @Autowired FaultCheckpoint faults;
    @Autowired SyntheticRates rates;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired SecurityEmailDeliveryRepository delivery;
    @Autowired SecurityEmailMaterialCipher deliveryCipher;
    @Autowired SecurityEmailWorker deliveryWorker;
    @Autowired IdentityCoreIntegrationTest.CapturingProvider mail;

    @Test void emailChangeRequestIsBlindAndCandidateLinkIsPurposeBound(CapturedOutput output)
            throws Exception {
        UUID owner = account(true);
        UUID occupied = account(true);
        Browser browser = csrf(cookie(session(owner, "ROLE_USER", "password")));
        String first = "first-" + UUID.randomUUID() + "@example.test";
        var collision = emailRequest(browser, email(occupied)).andExpect(status().isAccepted())
                .andReturn().getResponse();
        assertThat(collision.getHeader("Location")).isNull();
        assertThat(collision.getContentAsString()).isEmpty();
        assertThat(jdbc.queryForObject("""
                select count(*) from identity.identity_capability
                where user_id = ? and purpose = 'email_change'
                """, Integer.class, owner)).isZero();
        var issued = emailRequest(browser, first.toUpperCase())
                .andExpect(status().isAccepted()).andReturn().getResponse();
        assertThat(issued.getHeader("Location")).isNull();
        assertThat(issued.getContentAsString()).isEqualTo(collision.getContentAsString());
        assertThat(email(owner)).isEqualTo(accountEmail(owner));
        UUID firstId = currentEmailChange(owner);
        String firstToken = emailChangeToken(firstId);
        var claim = delivery.claimReady(clock.instant(), new LeaseOwner("email_change_test"),
                new LeasePolicy(Duration.ofMinutes(2), 100), 100).stream()
                .filter(c -> firstId.equals(c.capabilityId())).findFirst().orElseThrow();
        deliveryWorker.process(claim);
        assertThat(mail.lastRecipient).isEqualTo(first);
        assertThat(mail.lastMessage.body()).contains("/confirm-email-change#token=");
        String second = "second-" + UUID.randomUUID() + "@example.test";
        emailRequest(browser, second).andExpect(status().isAccepted());
        assertThat(jdbc.queryForObject("""
                select superseded_at is not null from identity.identity_capability
                where capability_id = ?
                """, Boolean.class, firstId)).isTrue();
        assertThat(jdbc.queryForObject("""
                select state from identity.security_email_delivery where capability_id = ?
                """, String.class, firstId)).isEqualTo("submitted");
        // Submitted mail cannot be retracted, but its superseded token cannot be consumed.
        emailConfirm(browser, firstToken).andExpect(status().isConflict());
        UUID secondId = currentEmailChange(owner);
        assertThat(secondId).isNotEqualTo(firstId);
        String secondToken = emailChangeToken(secondId);
        emailRequest(browser, "third-" + UUID.randomUUID() + "@example.test")
                .andExpect(status().isAccepted());
        assertThat(jdbc.queryForObject("""
                select state from identity.security_email_delivery where capability_id = ?
                """, String.class, secondId)).isEqualTo("obsolete");
        assertThat(jdbc.queryForObject("""
                select sealed_token_ciphertext is null from identity.security_email_delivery
                where capability_id = ?
                """, Boolean.class, secondId)).isTrue();
        emailConfirm(browser, secondToken).andExpect(status().isConflict());
        assertThat(output.getAll()).doesNotContain(firstToken, first, second,
                browser.csrf(), browser.cookie().getValue());
    }

    @Test void emailChangeConfirmationRotatesSessionsAndRetainsHistoricalNotices()
            throws Exception {
        UUID owner = account(true);
        UUID unrelatedOwner = account(true);
        String original = email(owner);
        String second = "candidate-" + UUID.randomUUID() + "@example.test";
        String third = "later-" + UUID.randomUUID() + "@example.test";
        Browser current = csrf(cookie(session(owner, "ROLE_USER", "password")));
        String otherFull = session(owner, "ROLE_USER", null);
        String otherPreMfa = session(owner, "ROLE_MFA_PENDING", null);
        String otherRecent = session(owner, "ROLE_USER", "oidc");
        String unrelated = session(unrelatedOwner, "ROLE_USER", null);
        emailRequest(current, second).andExpect(status().isAccepted());
        String token = emailChangeToken(currentEmailChange(owner));
        var result = emailConfirm(current, token).andExpect(status().isNoContent()).andReturn();
        Cookie rotated = result.getResponse().getCookie("SESSION");
        assertThat(rotated).isNotNull();
        assertThat(rotated.getValue()).isNotEqualTo(current.cookie().getValue());
        assertThat(accountEmail(owner)).isEqualTo(second);
        assertThat(jdbc.queryForObject("""
                select email_verified_at is not null from identity.account where user_id = ?
                """, Boolean.class, owner)).isTrue();
        assertThat(passwords.matches(OLD, verifier(owner))).isTrue();
        for (String id : List.of(raw(current.cookie()), otherFull, otherPreMfa, otherRecent)) {
            assertThat(sessions.findById(id)).isNull();
        }
        assertThat(sessions.findById(unrelated)).isNotNull();
        mvc.perform(get("/api/me/security").cookie(rotated)).andExpect(status().isOk());
        assertThat((Object) repository().findById(raw(rotated)).getAttribute(
                IdentitySessionState.RECENT_ATTRIBUTE)).isNull();
        emailConfirm(new Browser(rotated, current.csrf()), token)
                .andExpect(status().isForbidden());
        Browser fresh = csrf(rotated);
        addRecent(fresh.cookie(), owner);
        emailConfirm(fresh, token).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("""
                select count(*) from identity.security_email_delivery
                where subject_user_id = ? and notice_kind like 'email_change_%'
                """, Integer.class, owner)).isEqualTo(2);
        UUID firstEvent = jdbc.queryForObject("""
                select security_event_id from identity.security_email_delivery
                where subject_user_id = ? and notice_kind = 'email_change_old_address'
                """, UUID.class, owner);
        assertEventRecipients(owner, firstEvent, original, second);
        Browser secondCurrent = csrf(fresh.cookie());
        emailRequest(secondCurrent, third).andExpect(status().isAccepted());
        String secondToken = emailChangeToken(currentEmailChange(owner));
        emailConfirm(secondCurrent, secondToken).andExpect(status().isNoContent());
        assertEventRecipients(owner, firstEvent, original, second);
        UUID secondEvent = jdbc.queryForObject("""
                select security_event_id from identity.security_email_delivery
                where subject_user_id = ? and notice_kind = 'email_change_new_address'
                  and security_event_id <> ?
                """, UUID.class, owner, firstEvent);
        assertEventRecipients(owner, secondEvent, second, third);
        assertThat(accountEmail(owner)).isEqualTo(third);
        var claims = delivery.claimReady(clock.instant(), new LeaseOwner("notice_test"),
                new LeasePolicy(Duration.ofMinutes(2), 100), 100);
        for (var claim : claims) {
            if (firstEvent.equals(claim.securityEventId())) {
                deliveryWorker.process(claim);
                assertThat(mail.lastRecipient).isEqualTo(
                        "email_change_old_address".equals(claim.noticeKind())
                                ? original : second);
                assertThat(jdbc.queryForObject("""
                        select sealed_recipient_ciphertext is null
                        from identity.security_email_delivery
                        where security_email_delivery_id = ?
                        """, Boolean.class, claim.id())).isTrue();
            }
        }
    }

    @Test void emailChangeRejectsMissingAuthorityCsrfAndUnavailableRateControl()
            throws Exception {
        UUID owner = account(true);
        String candidate = "candidate-" + UUID.randomUUID() + "@example.test";
        Browser noRecent = csrf(cookie(session(owner, "ROLE_USER", null)));
        emailRequest(noRecent, candidate).andExpect(status().isForbidden());
        Browser preMfa = csrf(cookie(session(owner, "ROLE_MFA_PENDING", "password")));
        emailRequest(preMfa, candidate).andExpect(status().isForbidden());
        Browser current = csrf(cookie(session(owner, "ROLE_USER", "password")));
        mvc.perform(post("/api/me/security/email-change/requests")
                .cookie(current.cookie()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"newEmail\":\"" + candidate + "\"}"))
                .andExpect(status().isForbidden());
        emailRequest(current, "not-an-email").andExpect(status().isUnprocessableEntity());
        rates.unavailable = true;
        emailRequest(current, candidate).andExpect(status().isServiceUnavailable());
        rates.unavailable = false;
        rates.throttled = true;
        emailRequest(current, candidate).andExpect(status().isTooManyRequests());
        rates.throttled = false;
        emailRequest(current, candidate).andExpect(status().isAccepted());
        String token = emailChangeToken(currentEmailChange(owner));
        rates.unavailable = true;
        emailConfirm(current, token).andExpect(status().isServiceUnavailable());
        rates.unavailable = false;
        rates.throttled = true;
        emailConfirm(current, token).andExpect(status().isTooManyRequests());
        rates.throttled = false;
        emailConfirm(current, token.substring(0, 37)
                + (token.charAt(37) == 'A' ? "B" : "A") + token.substring(38))
                .andExpect(status().isConflict());
        UUID another = account(true);
        Browser other = csrf(cookie(session(another, "ROLE_USER", "password")));
        emailConfirm(other, token).andExpect(status().isConflict());
        jdbc.update("update identity.account set account_state = 'suspended' where user_id = ?",
                owner);
        assertThat(emailConfirm(current, token).andReturn().getResponse().getStatus())
                .isIn(401, 403);
    }

    @Test void emailChangeRejectsExpiredReusedAndWrongPurposeCapabilities() throws Exception {
        UUID owner = account(true);
        Browser browser = csrf(cookie(session(owner, "ROLE_USER", "password")));
        String candidate = "expiring-" + UUID.randomUUID() + "@example.test";
        emailRequest(browser, candidate).andExpect(status().isAccepted());
        UUID capability = currentEmailChange(owner);
        String token = emailChangeToken(capability);
        jdbc.update("""
                update identity.identity_capability set issued_at = ?, expires_at = ?
                where capability_id = ?
                """, Timestamp.from(clock.instant().minusSeconds(3600)),
                Timestamp.from(clock.instant().minusSeconds(1)), capability);
        emailConfirm(browser, token).andExpect(status().isConflict());
        assertThat(accountEmail(owner)).isEqualTo(email(owner));
        emailRequest(browser, candidate).andExpect(status().isAccepted());
        UUID second = currentEmailChange(owner);
        String secondToken = emailChangeToken(second);
        UUID occupied = account(true);
        jdbc.update("update identity.account set canonical_email = ?, display_email = ? where user_id = ?",
                candidate, candidate, occupied);
        emailConfirm(browser, secondToken).andExpect(status().isConflict());
        assertThat(accountEmail(owner)).isEqualTo(email(owner));
        assertThat(jdbc.queryForObject("""
                select consumed_at is null from identity.identity_capability where capability_id = ?
                """, Boolean.class, second)).isTrue();
        assertThat(jdbc.queryForObject("""
                select count(*) from identity.security_email_delivery
                where subject_user_id = ? and notice_kind like 'email_change_%'
                """, Integer.class, owner)).isZero();
        UUID wrongPurpose = jdbc.queryForObject("select uuidv7()", UUID.class);
        String wrongToken = PasswordResetToken.issue(wrongPurpose);
        jdbc.update("""
                insert into identity.identity_capability
                    (capability_id, user_id, purpose, verifier_digest, issued_at, expires_at)
                values (?, ?, 'password_reset', ?, ?, ?)
                """, wrongPurpose, owner, PasswordResetToken.digest(wrongToken),
                Timestamp.from(clock.instant()), Timestamp.from(clock.instant().plusSeconds(3600)));
        emailConfirm(browser, wrongToken).andExpect(status().isConflict());
    }

    @Test void emailChangeFaultsRollBackAccountSessionsAndNoticeIntents() throws Exception {
        for (int point = 1; point <= 4; point++) {
            UUID owner = account(true);
            String oldEmail = email(owner);
            Browser current = csrf(cookie(session(owner, "ROLE_USER", "password")));
            String other = session(owner, "ROLE_USER", null);
            emailRequest(current, "rollback-" + UUID.randomUUID() + "@example.test")
                    .andExpect(status().isAccepted());
            UUID capability = currentEmailChange(owner);
            String token = emailChangeToken(capability);
            faults.failAt = point;
            mvc.perform(post("/api/me/security/email-change/confirmations")
                    .cookie(current.cookie()).header("X-CSRF-TOKEN", current.csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"token\":\"" + token + "\"}"))
                    .andExpect(status().is5xxServerError());
            faults.failAt = 0;
            assertThat(accountEmail(owner)).isEqualTo(oldEmail);
            assertThat(jdbc.queryForObject("""
                    select consumed_at is null from identity.identity_capability
                    where capability_id = ?
                    """, Boolean.class, capability)).isTrue();
            assertThat(sessions.findById(raw(current.cookie()))).isNotNull();
            assertThat(sessions.findById(other)).isNotNull();
            assertThat(jdbc.queryForObject("""
                    select count(*) from identity.security_email_delivery
                    where subject_user_id = ? and notice_kind like 'email_change_%'
                    """, Integer.class, owner)).isZero();
            assertThat(jdbc.queryForObject("""
                    select count(*) from identity.security_audit_fact
                    where target_user_id = ? and event_category = 'email_change'
                      and outcome_code = 'completed'
                    """, Integer.class, owner)).isZero();
        }
    }

    @Test void concurrentEmailChangeRequestsLeaveOneCurrentCapability() throws Exception {
        UUID owner = account(true);
        Browser browser = csrf(cookie(session(owner, "ROLE_USER", "password")));
        CountDownLatch go = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> {
                go.await();
                return emailRequest(browser, "parallel-a-" + UUID.randomUUID()
                        + "@example.test").andReturn().getResponse().getStatus();
            });
            var b = pool.submit(() -> {
                go.await();
                return emailRequest(browser, "parallel-b-" + UUID.randomUUID()
                        + "@example.test").andReturn().getResponse().getStatus();
            });
            go.countDown();
            assertThat(a.get(20, TimeUnit.SECONDS)).isEqualTo(202);
            assertThat(b.get(20, TimeUnit.SECONDS)).isEqualTo(202);
        }
        assertThat(jdbc.queryForObject("""
                select count(*) from identity.identity_capability
                where user_id = ? and purpose = 'email_change'
                  and consumed_at is null and superseded_at is null and revoked_at is null
                """, Integer.class, owner)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select count(*) from identity.security_email_delivery d
                join identity.identity_capability c on c.capability_id = d.capability_id
                where c.user_id = ? and c.purpose = 'email_change' and d.state = 'queued'
                """, Integer.class, owner)).isEqualTo(1);
    }

    @Test void emailChangeNoticeLeaseFencingAndTerminalClearing() throws Exception {
        UUID owner = account(true);
        Browser current = csrf(cookie(session(owner, "ROLE_USER", "password")));
        emailRequest(current, "notices-" + UUID.randomUUID() + "@example.test")
                .andExpect(status().isAccepted());
        emailConfirm(current, emailChangeToken(currentEmailChange(owner)))
                .andExpect(status().isNoContent());
        var ready = delivery.claimReady(clock.instant(), new LeaseOwner("notice_first"),
                new LeasePolicy(Duration.ofSeconds(1), 100), 100).stream()
                .filter(c -> c.securityEventId() != null && owner.equals(c.subjectUserId()))
                .toList();
        assertThat(ready).hasSize(2);
        Instant expired = clock.instant().plusSeconds(2);
        var reclaimed = delivery.reclaimExpired(expired, new LeaseOwner("notice_reclaim"),
                new LeasePolicy(Duration.ofMinutes(2), 100), 100).stream()
                .filter(c -> owner.equals(c.subjectUserId())).toList();
        assertThat(reclaimed).hasSize(2);
        for (int index = 0; index < ready.size(); index++) {
            var stale = ready.get(index);
            var currentClaim = reclaimed.stream().filter(c -> c.id().equals(stale.id()))
                    .findFirst().orElseThrow();
            assertThat(currentClaim.token().value()).isNotEqualTo(stale.token().value());
            assertThat(delivery.submitted(stale, clock.instant())).isFalse();
            if (index == 0) {
                deliveryWorker.process(currentClaim);
                assertThat(jdbc.queryForObject("""
                        select state from identity.security_email_delivery
                        where security_email_delivery_id = ?
                        """, String.class, currentClaim.id())).isEqualTo("submitted");
            } else {
                assertThat(delivery.failed(currentClaim, clock.instant(), "synthetic_failure"))
                        .isTrue();
            }
            assertThat(jdbc.queryForObject("""
                    select sealed_recipient_ciphertext is null
                        and sealed_recipient_nonce is null
                        and sealed_recipient_tag is null
                        and recipient_key_version is null
                    from identity.security_email_delivery
                    where security_email_delivery_id = ?
                    """, Boolean.class, currentClaim.id())).isTrue();
        }
    }

    @Test void concurrentEmailChangeConfirmationConsumesOnce() throws Exception {
        UUID owner = account(true);
        Browser current = csrf(cookie(session(owner, "ROLE_USER", "password")));
        String candidate = "single-use-" + UUID.randomUUID() + "@example.test";
        emailRequest(current, candidate).andExpect(status().isAccepted());
        String token = emailChangeToken(currentEmailChange(owner));
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger denied = new AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> {
                concurrentEmailConfirm(current, token, go, success, denied); return null;
            });
            var second = pool.submit(() -> {
                concurrentEmailConfirm(current, token, go, success, denied); return null;
            });
            go.countDown();
            first.get(20, TimeUnit.SECONDS);
            second.get(20, TimeUnit.SECONDS);
        }
        assertThat(success).hasValue(1);
        assertThat(denied).hasValue(1);
        assertThat(accountEmail(owner)).isEqualTo(candidate);
        assertThat(jdbc.queryForObject("""
                select count(*) from identity.security_email_delivery
                where subject_user_id = ? and notice_kind like 'email_change_%'
                """, Integer.class, owner)).isEqualTo(2);
    }

    private void concurrentEmailConfirm(Browser browser, String token, CountDownLatch go,
            AtomicInteger success, AtomicInteger denied) throws Exception {
        go.await();
        int status = emailConfirm(browser, token).andReturn().getResponse().getStatus();
        if (status == 204) success.incrementAndGet();
        else if (status == 401 || status == 403 || status == 409) denied.incrementAndGet();
        else throw new AssertionError("Unexpected confirmation status: " + status);
    }

    private org.springframework.test.web.servlet.ResultActions emailRequest(Browser browser,
            String candidate) throws Exception {
        return mvc.perform(post("/api/me/security/email-change/requests")
                .cookie(browser.cookie()).header("X-CSRF-TOKEN", browser.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"newEmail\":\"" + candidate + "\"}"));
    }

    private org.springframework.test.web.servlet.ResultActions emailConfirm(Browser browser,
            String token) throws Exception {
        var result = mvc.perform(post("/api/me/security/email-change/confirmations")
                .cookie(browser.cookie()).header("X-CSRF-TOKEN", browser.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\"}"));
        if (result.andReturn().getResponse().getStatus() == 500) {
            throw new AssertionError("Email confirmation failed", result.andReturn().getResolvedException());
        }
        return result;
    }

    private UUID currentEmailChange(UUID userId) {
        return jdbc.queryForObject("""
                select capability_id from identity.identity_capability
                where user_id = ? and purpose = 'email_change'
                  and consumed_at is null and superseded_at is null and revoked_at is null
                """, UUID.class, userId);
    }

    private String emailChangeToken(UUID id) {
        return deliveryCipher.open("email_change", id,
                new SecurityEmailMaterialCipher.Envelope(
                        jdbc.queryForObject("""
                                select sealed_token_ciphertext from identity.security_email_delivery
                                where capability_id = ?
                                """, byte[].class, id),
                        jdbc.queryForObject("""
                                select sealed_token_nonce from identity.security_email_delivery
                                where capability_id = ?
                                """, byte[].class, id),
                        jdbc.queryForObject("""
                                select sealed_token_tag from identity.security_email_delivery
                                where capability_id = ?
                                """, byte[].class, id),
                        jdbc.queryForObject("""
                                select token_key_version from identity.security_email_delivery
                                where capability_id = ?
                                """, String.class, id)));
    }

    private String accountEmail(UUID userId) {
        return jdbc.queryForObject("select canonical_email from identity.account where user_id = ?",
                String.class, userId);
    }

    private void addRecent(Cookie cookie, UUID userId) {
        Session persisted = repository().findById(raw(cookie));
        persisted.setAttribute(IdentitySessionState.RECENT_ATTRIBUTE,
                new IdentitySessionState.RecentAuthentication(userId, clock.instant(), "password"));
        repository().save(persisted);
    }

    private void assertEventRecipients(UUID owner, UUID event, String oldAddress,
            String newAddress) {
        for (String kind : List.of("email_change_old_address", "email_change_new_address")) {
            var row = jdbc.queryForMap("""
                    select sealed_recipient_ciphertext, sealed_recipient_nonce,
                           sealed_recipient_tag, recipient_key_version,
                           sealed_token_ciphertext
                    from identity.security_email_delivery
                    where subject_user_id = ? and security_event_id = ? and notice_kind = ?
                    """, owner, event, kind);
            assertThat(row.get("sealed_token_ciphertext")).isNull();
            var envelope = new SecurityEmailMaterialCipher.Envelope(
                    (byte[]) row.get("sealed_recipient_ciphertext"),
                    (byte[]) row.get("sealed_recipient_nonce"),
                    (byte[]) row.get("sealed_recipient_tag"),
                    (String) row.get("recipient_key_version"));
            assertThat(deliveryCipher.openRecipient(event, kind, envelope)).isEqualTo(
                    "email_change_old_address".equals(kind) ? oldAddress : newAddress);
            assertThat(new String(envelope.ciphertext(), StandardCharsets.ISO_8859_1))
                    .doesNotContain(oldAddress, newAddress);
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    deliveryCipher.openRecipient(event,
                            "email_change_old_address".equals(kind)
                                    ? "email_change_new_address" : "email_change_old_address",
                            envelope)).isInstanceOf(IllegalStateException.class);
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    deliveryCipher.openRecipient(UUID.randomUUID(), kind, envelope))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test void summaryIsOwnerScopedCurrentAndSecretFree(CapturedOutput output) throws Exception {
        UUID owner = account(true);
        UUID other = account(true);
        UUID googleOnly = account(false);
        String subject = "synthetic-subject-" + UUID.randomUUID();
        oidc.createLink(owner, "https://accounts.google.com", subject, clock.instant());
        oidc.createLink(other, "https://accounts.google.com", "other-" + UUID.randomUUID(),
                clock.instant());
        oidc.createLink(googleOnly, "https://accounts.google.com", "google-" + UUID.randomUUID(),
                clock.instant());
        UUID linkId = jdbc.queryForObject("""
                select external_identity_link_id from identity.external_identity_link
                where user_id = ? and revoked_at is null
                """, UUID.class, owner);
        Browser browser = csrf(cookie(session(owner, "ROLE_USER", null)));
        var response = mvc.perform(get("/api/me/security").cookie(browser.cookie()))
                .andExpect(status().isOk()).andReturn().getResponse();
        String body = response.getContentAsString();
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(body).contains(email(owner), "\"passwordConfigured\":true",
                "\"mfaState\":\"disabled\"", linkId.toString(), "\"provider\":\"google\"")
                .doesNotContain(owner.toString(), other.toString(), subject,
                        browser.cookie().getValue(), browser.csrf(), "password_verifier", "issuer");
        assertThat(output.getAll()).doesNotContain(subject, browser.csrf());

        var seed = mfaCipher.seal(owner, new byte[20]);
        mfa.begin(owner, seed, clock.instant());
        assertThat(mvc.perform(get("/api/me/security").cookie(browser.cookie()))
                .andReturn().getResponse().getContentAsString())
                .contains("\"mfaState\":\"enrollmentPending\"");
        mfa.activate(owner, seed.nonce(), 0, clock.instant());
        assertThat(mvc.perform(get("/api/me/security").cookie(browser.cookie()))
                .andReturn().getResponse().getContentAsString())
                .contains("\"mfaState\":\"active\"");
        jdbc.update("update identity.external_identity_link set revoked_at = ? where user_id = ?",
                Timestamp.from(clock.instant()), owner);
        assertThat(mvc.perform(get("/api/me/security").cookie(browser.cookie()))
                .andReturn().getResponse().getContentAsString())
                .contains("\"oidcLinks\":[]");
        Browser google = csrf(cookie(session(googleOnly, "ROLE_USER", null)));
        assertThat(mvc.perform(get("/api/me/security").cookie(google.cookie()))
                .andReturn().getResponse().getContentAsString())
                .contains("\"passwordConfigured\":false");
        jdbc.update("update identity.account set password_verifier = ? where user_id = ?",
                passwords.encode(NEW), googleOnly);
        assertThat(mvc.perform(get("/api/me/security").cookie(google.cookie()))
                .andReturn().getResponse().getContentAsString())
                .contains("\"passwordConfigured\":true");
        mvc.perform(get("/api/me/security")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/me/security").cookie(cookie(session(owner, "ROLE_MFA_PENDING", null))))
                .andExpect(status().isForbidden());
        jdbc.update("update identity.account set account_state = 'suspended' where user_id = ?", owner);
        mvc.perform(get("/api/me/security").cookie(browser.cookie()))
                .andExpect(status().isUnauthorized());
    }

    @Test void disablingMfaRemovesAuthorityRotatesSessionAndQueuesCurrentRecipientNotice(
            CapturedOutput output) throws Exception {
        UUID owner = account(true);
        UUID unrelated = account(true);
        oidc.createLink(owner, "https://accounts.google.com", "subject-" + UUID.randomUUID(),
                clock.instant());
        activateMfa(owner);
        String oldCode = recoveryCodes.issue().getFirst();
        mfa.insertRecovery(owner, 1, recoveryCodes.digest(oldCode), clock.instant());
        Browser current = csrf(cookie(session(owner, "ROLE_USER", "password")));
        String other = session(owner, "ROLE_USER", null);
        String preMfa = session(owner, "ROLE_MFA_PENDING", null);
        String unrelatedSession = session(unrelated, "ROLE_USER", null);
        var response = disable(current).andExpect(status().isNoContent())
                .andReturn().getResponse();
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        Cookie rotated = response.getCookie("SESSION");
        assertThat(rotated).isNotNull();
        assertThat(rotated.getValue()).isNotEqualTo(current.cookie().getValue());
        assertThat(mfa.configuration(owner)).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from identity.mfa_recovery_code where user_id = ?",
                Integer.class, owner)).isZero();
        assertThat(sessions.findById(raw(current.cookie()))).isNull();
        assertThat(sessions.findById(other)).isNull();
        assertThat(sessions.findById(preMfa)).isNull();
        assertThat(sessions.findById(unrelatedSession)).isNotNull();
        assertThat((Object) repository().findById(raw(rotated)).getAttribute(
                IdentitySessionState.RECENT_ATTRIBUTE)).isNull();
        assertThat(mvc.perform(get("/api/me/security").cookie(rotated))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .contains("\"mfaState\":\"disabled\"");
        Browser oldCsrf = new Browser(rotated, current.csrf());
        disable(oldCsrf).andExpect(status().isForbidden());
        Browser fresh = csrf(rotated);
        assertThat(fresh.csrf()).isNotEqualTo(current.csrf());
        assertThat(passwords.matches(OLD, verifier(owner))).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from identity.external_identity_link where user_id = ? and revoked_at is null",
                Integer.class, owner)).isEqualTo(1);
        assertThat(email(owner)).isEqualTo(accountEmail(owner));
        assertThat(mfaAudits(owner, "mfa", "disabled")).isEqualTo(1);
        assertThat(notices(owner, "mfa_disabled")).isEqualTo(1);
        assertThat(mfaNoticeCorrelatesAudit(owner, "mfa_disabled", "mfa", "disabled"))
                .isTrue();
        var claim = claimNotice(owner, "mfa_disabled");
        assertThat(claim.capabilityId()).isNull();
        assertThat(claim.recipientEnvelope().ciphertext()).isNull();
        assertThat(claim.envelope().ciphertext()).isNull();
        deliveryWorker.process(claim);
        assertThat(mail.lastRecipient).isEqualTo(email(owner));
        assertThat(mail.lastMessage.body()).doesNotContain(oldCode);
        assertThat(output.getAll()).doesNotContain(oldCode, current.csrf(),
                current.cookie().getValue());
        Browser anonymous = csrf(null);
        mvc.perform(post("/api/auth/login/password").cookie(anonymous.cookie())
                .header("X-CSRF-TOKEN", anonymous.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email(owner) + "\",\"password\":\"" + OLD + "\"}"))
                .andExpect(status().isOk());
    }

    @Test void regeneratingRecoveryCodesAdvancesGenerationAndKeepsTotpActive(
            CapturedOutput output) throws Exception {
        UUID owner = account(true);
        activateMfa(owner);
        byte[] originalSeed = mfa.configuration(owner).orElseThrow().seed().ciphertext();
        String oldCode = recoveryCodes.issue().getFirst();
        mfa.insertRecovery(owner, 1, recoveryCodes.digest(oldCode), clock.instant());
        String consumedOld = recoveryCodes.issue().getFirst();
        mfa.insertRecovery(owner, 1, recoveryCodes.digest(consumedOld), clock.instant());
        assertThat(mfa.consumeRecovery(owner, recoveryCodes.digest(consumedOld), clock.instant()))
                .isTrue();
        Browser current = csrf(cookie(session(owner, "ROLE_USER", "password")));
        String other = session(owner, "ROLE_USER", null);
        var response = regenerate(current).andExpect(status().isOk())
                .andReturn().getResponse();
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        String json = response.getContentAsString();
        assertThat(json).contains("\"recoveryCodes\"").doesNotContain(owner.toString());
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("[A-Za-z0-9_-]{43}")
                .matcher(json);
        List<String> codes = new java.util.ArrayList<>();
        while (matcher.find()) codes.add(matcher.group());
        assertThat(codes).hasSize(mfaPolicy.recoveryCodeCount());
        assertThat(mfa.configuration(owner).orElseThrow().recoveryGeneration()).isEqualTo(2);
        assertThat(mfa.configuration(owner).orElseThrow().seed().ciphertext())
                .containsExactly(originalSeed);
        assertThat(jdbc.queryForObject("select count(*) from identity.mfa_recovery_code where user_id = ? and set_generation = 2 and revoked_at is null",
                Integer.class, owner)).isEqualTo(codes.size());
        assertThat(jdbc.queryForObject("select count(*) from identity.mfa_recovery_code where user_id = ? and set_generation = 1 and revoked_at is not null",
                Integer.class, owner)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from identity.mfa_recovery_code where user_id = ? and set_generation = 1 and consumed_at is not null and revoked_at is null",
                Integer.class, owner)).isEqualTo(1);
        assertThat(mfa.consumeRecovery(owner, recoveryCodes.digest(oldCode), clock.instant()))
                .isFalse();
        assertThat(mfa.consumeRecovery(owner, recoveryCodes.digest(consumedOld), clock.instant()))
                .isFalse();
        assertThat(mfa.consumeRecovery(owner, recoveryCodes.digest(codes.getFirst()), clock.instant()))
                .isTrue();
        assertThat(mfa.consumeRecovery(owner, recoveryCodes.digest(codes.getFirst()), clock.instant()))
                .isFalse();
        for (String code : codes) {
            assertThat(output.getAll()).doesNotContain(code);
            assertThat(jdbc.queryForObject("select count(*) from identity.mfa_recovery_code where verifier_digest = ?",
                    Integer.class, code.getBytes(StandardCharsets.US_ASCII))).isZero();
        }
        assertThat(mvc.perform(get("/api/me/security").cookie(response.getCookie("SESSION")))
                .andReturn().getResponse().getContentAsString())
                .contains("\"mfaState\":\"active\"");
        assertThat(sessions.findById(raw(current.cookie()))).isNull();
        assertThat(sessions.findById(other)).isNull();
        assertThat(mfaAudits(owner, "mfa_recovery", "regenerated")).isEqualTo(1);
        assertThat(notices(owner, "mfa_reset")).isEqualTo(1);
        assertThat(mfaNoticeCorrelatesAudit(owner, "mfa_reset", "mfa_recovery",
                "regenerated")).isTrue();
        var claim = claimNotice(owner, "mfa_reset");
        deliveryWorker.process(claim);
        assertThat(mail.lastRecipient).isEqualTo(email(owner));
        for (String code : codes) assertThat(mail.lastMessage.body()).doesNotContain(code);
    }

    @Test void mfaManagementRejectsMissingAuthorityAndInactiveLifecycle() throws Exception {
        UUID owner = account(true);
        Browser anonymous = csrf(null);
        disable(anonymous).andExpect(status().isUnauthorized());
        regenerate(anonymous).andExpect(status().isUnauthorized());
        Browser pending = csrf(cookie(session(owner, "ROLE_MFA_PENDING", "password")));
        disable(pending).andExpect(status().isForbidden());
        regenerate(pending).andExpect(status().isForbidden());
        Browser current = csrf(cookie(session(owner, "ROLE_USER", "password")));
        disable(current).andExpect(status().isConflict());
        regenerate(current).andExpect(status().isConflict());
        var seed = mfaCipher.seal(owner, new byte[20]);
        mfa.begin(owner, seed, clock.instant());
        disable(current).andExpect(status().isConflict());
        regenerate(current).andExpect(status().isConflict());
        mfa.activate(owner, seed.nonce(), 0, clock.instant());
        Browser noRecent = csrf(cookie(session(owner, "ROLE_USER", null)));
        disable(noRecent).andExpect(status().isForbidden());
        regenerate(noRecent).andExpect(status().isForbidden());
        Browser expired = csrf(cookie(session(owner, "ROLE_USER", "expired")));
        disable(expired).andExpect(status().isForbidden());
        regenerate(expired).andExpect(status().isForbidden());
        Browser bogusMethod = csrf(cookie(session(owner, "ROLE_USER", "email")));
        disable(bogusMethod).andExpect(status().isForbidden());
        regenerate(bogusMethod).andExpect(status().isForbidden());
        mvc.perform(delete("/api/me/security/mfa/totp").cookie(current.cookie()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/me/security/mfa/recovery-codes").cookie(current.cookie()))
                .andExpect(status().isForbidden());
        rates.unavailable = true;
        try {
            disable(current).andExpect(status().isServiceUnavailable());
            regenerate(current).andExpect(status().isServiceUnavailable());
        } finally { rates.unavailable = false; }
        rates.throttled = true;
        try {
            disable(current).andExpect(status().isTooManyRequests());
            regenerate(current).andExpect(status().isTooManyRequests());
        } finally { rates.throttled = false; }
        jdbc.update("update identity.account set account_state = 'suspended' where user_id = ?", owner);
        assertThat(disable(current).andReturn().getResponse().getStatus()).isIn(401, 403);
        assertThat(regenerate(current).andReturn().getResponse().getStatus()).isIn(401, 403);
    }

    @Test void mfaManagementFaultsRollBackEveryAuthoritativeParticipant() throws Exception {
        for (boolean regeneration : List.of(false, true)) {
            for (int point = 1; point <= 5; point++) {
                UUID owner = account(true);
                activateMfa(owner);
                String oldCode = recoveryCodes.issue().getFirst();
                mfa.insertRecovery(owner, 1, recoveryCodes.digest(oldCode), clock.instant());
                Browser current = csrf(cookie(session(owner, "ROLE_USER", "password")));
                String other = session(owner, "ROLE_USER", null);
                faults.failAt = point;
                try {
                    if (regeneration) regenerate(current).andExpect(status().is5xxServerError());
                    else disable(current).andExpect(status().is5xxServerError());
                } finally { faults.failAt = 0; }
                assertThat(mfa.configuration(owner).orElseThrow().state()).isEqualTo("active");
                assertThat(mfa.configuration(owner).orElseThrow().recoveryGeneration()).isEqualTo(1);
                assertThat(mfa.consumeRecovery(owner, recoveryCodes.digest(oldCode),
                        clock.instant())).isTrue();
                assertThat(sessions.findById(raw(current.cookie()))).isNotNull();
                assertThat(sessions.findById(other)).isNotNull();
                assertThat(mfaAudits(owner, regeneration ? "mfa_recovery" : "mfa",
                        regeneration ? "regenerated" : "disabled")).isZero();
                assertThat(notices(owner, regeneration ? "mfa_reset" : "mfa_disabled"))
                        .isZero();
            }
        }
    }

    @Test void concurrentRegenerationConsumesOnlyOneCurrentSessionAuthority() throws Exception {
        UUID owner = account(true);
        activateMfa(owner);
        Browser current = csrf(cookie(session(owner, "ROLE_USER", "password")));
        int[] outcomes = race(current, true);
        assertThat(outcomes).containsExactlyInAnyOrder(200, 401);
        assertThat(mfa.configuration(owner).orElseThrow().recoveryGeneration()).isEqualTo(2);
        assertThat(mfaAudits(owner, "mfa_recovery", "regenerated")).isEqualTo(1);
        assertThat(notices(owner, "mfa_reset")).isEqualTo(1);
    }

    @Test void disablingAndRegeneratingCannotCommitIncompatibleAuthority() throws Exception {
        UUID owner = account(true);
        activateMfa(owner);
        Browser current = csrf(cookie(session(owner, "ROLE_USER", "password")));
        int[] outcomes = race(current, false);
        assertThat(outcomes).contains(401);
        assertThat(outcomes[0] == 204 || outcomes[1] == 204
                || outcomes[0] == 200 || outcomes[1] == 200).isTrue();
        if (mfa.configuration(owner).isEmpty()) {
            assertThat(jdbc.queryForObject("select count(*) from identity.mfa_recovery_code where user_id = ?",
                    Integer.class, owner)).isZero();
            assertThat(mfaAudits(owner, "mfa", "disabled")).isEqualTo(1);
            assertThat(notices(owner, "mfa_disabled")).isEqualTo(1);
            assertThat(notices(owner, "mfa_reset")).isZero();
        } else {
            assertThat(mfa.configuration(owner).orElseThrow().recoveryGeneration()).isEqualTo(2);
            assertThat(mfaAudits(owner, "mfa_recovery", "regenerated")).isEqualTo(1);
            assertThat(notices(owner, "mfa_reset")).isEqualTo(1);
            assertThat(notices(owner, "mfa_disabled")).isZero();
        }
    }

    @Test void staleInFlightSessionCannotResurrectAfterMfaDisable() throws Exception {
        UUID owner = account(true);
        activateMfa(owner);
        Browser current = csrf(cookie(session(owner, "ROLE_USER", "password")));
        String staleId = session(owner, "ROLE_USER", null);
        CountDownLatch loaded = new CountDownLatch(1);
        CountDownLatch disabled = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var inFlight = pool.submit(() -> {
                Session stale = repository().findById(staleId);
                loaded.countDown();
                if (!disabled.await(15, TimeUnit.SECONDS)) throw new IllegalStateException();
                stale.setLastAccessedTime(clock.instant().plusSeconds(1));
                try { repository().save(stale); }
                catch (DataAccessException rejected) { /* Deleted authority remains absent. */ }
                return null;
            });
            assertThat(loaded.await(5, TimeUnit.SECONDS)).isTrue();
            try { disable(current).andExpect(status().isNoContent()); }
            finally { disabled.countDown(); }
            inFlight.get(5, TimeUnit.SECONDS);
        }
        assertThat(repository().findById(staleId)).isNull();
        mvc.perform(get("/api/me/security").cookie(cookie(staleId)))
                .andExpect(status().isUnauthorized());
    }

    @Test void oidcRecentRequiresCurrentLinkForMfaSecurityChanges() throws Exception {
        UUID owner = account(false);
        activateMfa(owner);
        Browser unlinked = csrf(cookie(session(owner, "ROLE_USER", "oidc")));
        disable(unlinked).andExpect(status().isForbidden());
        regenerate(unlinked).andExpect(status().isForbidden());
        oidc.createLink(owner, "https://accounts.google.com", "subject-" + UUID.randomUUID(),
                clock.instant());
        regenerate(unlinked).andExpect(status().isOk());
        Browser fresh = csrf(cookie(session(owner, "ROLE_USER", "oidc")));
        disable(fresh).andExpect(status().isNoContent());
    }

    @Test void mfaNoticesUseCurrentRecipientAndExistingRetryFencing() throws Exception {
        UUID owner = account(true);
        activateMfa(owner);
        Browser current = csrf(cookie(session(owner, "ROLE_USER", "password")));
        disable(current).andExpect(status().isNoContent());
        String newer = "new-mfa-" + UUID.randomUUID() + "@example.test";
        jdbc.update("update identity.account set canonical_email = ?, display_email = ? where user_id = ?",
                newer, newer, owner);
        var claim = claimNotice(owner, "mfa_disabled");
        mail.nextOutcome = SecurityEmailProviderPort.Outcome.RETRYABLE;
        try { deliveryWorker.process(claim); }
        finally { mail.nextOutcome = SecurityEmailProviderPort.Outcome.SUBMITTED; }
        assertThat(mail.lastRecipient).isEqualTo(newer);
        assertThat(jdbc.queryForObject("select state from identity.security_email_delivery where security_email_delivery_id = ?",
                String.class, claim.id())).isEqualTo("retry_wait");
        assertThat(delivery.submitted(claim, clock.instant())).isFalse();
        Instant due = jdbc.queryForObject("select next_attempt_at from identity.security_email_delivery where security_email_delivery_id = ?",
                Timestamp.class, claim.id()).toInstant();
        var retried = delivery.claimReady(due, new LeaseOwner("mfa_notice_retry"),
                new LeasePolicy(Duration.ofMinutes(2), 100), 100).stream()
                .filter(c -> claim.id().equals(c.id())).findFirst().orElseThrow();
        assertThat(retried.token()).isNotEqualTo(claim.token());
        assertThat(delivery.submitted(claim, due)).isFalse();
        assertThat(delivery.submitted(retried, due.plusSeconds(1))).isTrue();
        assertThat(jdbc.queryForObject("select state from identity.security_email_delivery where security_email_delivery_id = ?",
                String.class, claim.id())).isEqualTo("submitted");
        assertThat(jdbc.queryForObject("select count(*) from identity.security_email_delivery where security_email_delivery_id = ? and sealed_token_ciphertext is null and sealed_recipient_ciphertext is null",
                Integer.class, claim.id())).isEqualTo(1);
    }

    private int[] race(Browser browser, boolean bothRegenerate) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> {
                start.await();
                return (bothRegenerate ? regenerate(browser) : disable(browser))
                        .andReturn().getResponse().getStatus();
            });
            var second = pool.submit(() -> {
                start.await();
                return regenerate(browser).andReturn().getResponse().getStatus();
            });
            start.countDown();
            return new int[]{first.get(30, TimeUnit.SECONDS),
                    second.get(30, TimeUnit.SECONDS)};
        }
    }

    private void activateMfa(UUID owner) {
        var seed = mfaCipher.seal(owner, new byte[20]);
        assertThat(mfa.begin(owner, seed, clock.instant())).isEqualTo(1);
        assertThat(mfa.activate(owner, seed.nonce(), 0, clock.instant())).isEqualTo(1);
    }

    private org.springframework.test.web.servlet.ResultActions disable(Browser browser)
            throws Exception {
        return mvc.perform(delete("/api/me/security/mfa/totp")
                .cookie(browser.cookie()).header("X-CSRF-TOKEN", browser.csrf()));
    }

    private org.springframework.test.web.servlet.ResultActions regenerate(Browser browser)
            throws Exception {
        return mvc.perform(post("/api/me/security/mfa/recovery-codes")
                .cookie(browser.cookie()).header("X-CSRF-TOKEN", browser.csrf()));
    }

    private int mfaAudits(UUID user, String category, String outcome) {
        return jdbc.queryForObject("select count(*) from identity.security_audit_fact where target_user_id = ? and event_category = ? and outcome_code = ?",
                Integer.class, user, category, outcome);
    }

    private int notices(UUID user, String kind) {
        return jdbc.queryForObject("select count(*) from identity.security_email_delivery where subject_user_id = ? and notice_kind = ?",
                Integer.class, user, kind);
    }

    private boolean mfaNoticeCorrelatesAudit(UUID user, String notice, String category,
            String outcome) {
        return jdbc.queryForObject("""
                select exists(select 1 from identity.security_email_delivery d
                join identity.security_audit_fact a on a.correlation_id = d.security_event_id
                where d.subject_user_id = ? and a.target_user_id = ?
                  and d.notice_kind = ? and a.event_category = ? and a.outcome_code = ?)
                """, Boolean.class, user, user, notice, category, outcome);
    }

    private SecurityEmailDeliveryRepository.Claim claimNotice(UUID user, String kind) {
        return delivery.claimReady(clock.instant(), new LeaseOwner("mfa_notice_test"),
                new LeasePolicy(Duration.ofMinutes(2), 100), 100).stream()
                .filter(c -> user.equals(c.subjectUserId()) && kind.equals(c.noticeKind()))
                .findFirst().orElseThrow();
    }

    @Test void passwordChangeRotatesCurrentAndRevokesOnlyOwnerSessions(CapturedOutput output)
            throws Exception {
        UUID owner = account(true);
        UUID other = account(true);
        Browser current = csrf(cookie(session(owner, "ROLE_USER", "password")));
        String otherFull = session(owner, "ROLE_USER", null);
        String otherPreMfa = session(owner, "ROLE_MFA_PENDING", null);
        String otherRecent = session(owner, "ROLE_USER", "oidc");
        String unrelated = session(other, "ROLE_USER", null);
        var mutation = change(current, NEW).andReturn();
        if (mutation.getResponse().getStatus() == 500) {
            throw new AssertionError("Password mutation failed", mutation.getResolvedException());
        }
        var response = mutation.getResponse();
        assertThat(response.getStatus()).isEqualTo(204);
        Cookie rotated = response.getCookie("SESSION");
        assertThat(rotated).isNotNull();
        assertThat(rotated.getValue()).isNotEqualTo(current.cookie().getValue());
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        String verifier = verifier(owner);
        assertThat(passwords.matches(NEW, verifier)).isTrue();
        assertThat(passwords.matches(OLD, verifier)).isFalse();
        Browser oldAttempt = csrf(null);
        mvc.perform(post("/api/auth/login/password").cookie(oldAttempt.cookie())
                .header("X-CSRF-TOKEN", oldAttempt.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email(owner) + "\",\"password\":\"" + OLD + "\"}"))
                .andExpect(status().isUnauthorized());
        Browser newAttempt = csrf(null);
        mvc.perform(post("/api/auth/login/password").cookie(newAttempt.cookie())
                .header("X-CSRF-TOKEN", newAttempt.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email(owner) + "\",\"password\":\"" + NEW + "\"}"))
                .andExpect(status().isOk());
        assertThat(sessions.findById(raw(current.cookie()))).isNull();
        assertThat(sessions.findById(otherFull)).isNull();
        assertThat(sessions.findById(otherPreMfa)).isNull();
        assertThat(sessions.findById(otherRecent)).isNull();
        assertThat(sessions.findById(unrelated)).isNotNull();
        mvc.perform(get("/api/me/security").cookie(rotated)).andExpect(status().isOk());
        assertThat((Object) repository().findById(raw(rotated)).getAttribute(
                IdentitySessionState.RECENT_ATTRIBUTE)).isNull();
        change(new Browser(rotated, current.csrf()), "AnotherSyntheticPassword-2026!")
                .andExpect(status().isForbidden());
        Browser fresh = csrf(rotated);
        assertThat(fresh.csrf()).isNotEqualTo(current.csrf());
        assertThat(audits(owner)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select count(*) from identity.security_email_delivery
                where subject_user_id = ? and notice_kind = 'password_changed'
                """, Integer.class, owner)).isZero();
        assertThat(output.getAll()).doesNotContain(OLD, NEW, verifier, current.csrf(),
                current.cookie().getValue());
    }

    @Test void oidcRecentCanSetFirstPasswordWithoutChangingLinkOrMfa() throws Exception {
        UUID owner = account(false);
        oidc.createLink(owner, "https://accounts.google.com", "subject-" + UUID.randomUUID(),
                clock.instant());
        var seed = mfaCipher.seal(owner, new byte[20]);
        mfa.begin(owner, seed, clock.instant());
        mfa.activate(owner, seed.nonce(), 0, clock.instant());
        Browser current = csrf(cookie(session(owner, "ROLE_USER", "oidc")));
        change(current, NEW).andExpect(status().isNoContent());
        assertThat(passwords.matches(NEW, verifier(owner))).isTrue();
        assertThat(mfa.configuration(owner).orElseThrow().state()).isEqualTo("active");
        assertThat(jdbc.queryForObject("""
                select count(*) from identity.external_identity_link
                where user_id = ? and revoked_at is null
                """, Integer.class, owner)).isEqualTo(1);
        Browser anonymous = csrf(null);
        mvc.perform(post("/api/auth/login/password").cookie(anonymous.cookie())
                .header("X-CSRF-TOKEN", anonymous.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email(owner) + "\",\"password\":\"" + NEW + "\"}"))
                .andExpect(status().isAccepted());
    }

    @Test void missingAuthoritiesProofAndRateControlFailClosed() throws Exception {
        UUID owner = account(true);
        Browser current = csrf(cookie(session(owner, "ROLE_USER", null)));
        mvc.perform(put("/api/me/security/password").cookie(current.cookie())
                .contentType(MediaType.APPLICATION_JSON).content(body(NEW)))
                .andExpect(status().isForbidden());
        change(current, NEW).andExpect(status().isForbidden());
        Browser pre = csrf(cookie(session(owner, "ROLE_MFA_PENDING", "password")));
        change(pre, NEW).andExpect(status().isForbidden());
        Browser wrongSubject = csrf(cookie(session(owner, "ROLE_USER", "password")));
        // The repository must persist the changed fact to test the operation boundary.
        Session altered = repository().findById(raw(wrongSubject.cookie()));
        altered.setAttribute(IdentitySessionState.RECENT_ATTRIBUTE,
                new IdentitySessionState.RecentAuthentication(UUID.randomUUID(), clock.instant(),
                        "password"));
        repository().save(altered);
        change(wrongSubject, NEW).andExpect(status().isForbidden());
        Browser expired = csrf(cookie(session(owner, "ROLE_USER", "expired")));
        change(expired, NEW).andExpect(status().isForbidden());
        Browser valid = csrf(cookie(session(owner, "ROLE_USER", "oidc")));
        mvc.perform(put("/api/me/security/password").cookie(valid.cookie())
                .header("X-CSRF-TOKEN", valid.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"newPassword\":\"" + NEW + "\",\"email\":\"other@example.test\"}"))
                .andExpect(status().isUnprocessableEntity());
        change(valid, "short").andExpect(status().isUnprocessableEntity());
        rates.unavailable = true;
        change(valid, NEW).andExpect(status().isServiceUnavailable());
        rates.unavailable = false;
        rates.throttled = true;
        change(valid, NEW).andExpect(status().isTooManyRequests());
        rates.throttled = false;
        jdbc.update("update identity.account set account_state = 'suspended' where user_id = ?", owner);
        assertThat(change(valid, NEW).andReturn().getResponse().getStatus())
                .isIn(401, 403);
        assertThat(passwords.matches(OLD, verifier(owner))).isTrue();
        assertThat(audits(owner)).isZero();
    }

    @Test void faultsRollBackPasswordSessionsAndAudit() throws Exception {
        for (int point = 1; point <= 3; point++) {
            UUID owner = account(true);
            Browser current = csrf(cookie(session(owner, "ROLE_USER", "password")));
            String other = session(owner, "ROLE_USER", null);
            faults.failAt = point;
            mvc.perform(put("/api/me/security/password").cookie(current.cookie())
                    .header("X-CSRF-TOKEN", current.csrf())
                    .contentType(MediaType.APPLICATION_JSON).content(body(NEW)))
                    .andExpect(status().is5xxServerError());
            faults.failAt = 0;
            assertThat(passwords.matches(OLD, verifier(owner))).isTrue();
            assertThat(sessions.findById(raw(current.cookie()))).isNotNull();
            assertThat(sessions.findById(other)).isNotNull();
            assertThat(audits(owner)).isZero();
        }
    }

    @Test void concurrentCommandsConsumeOneRecentAuthority() throws Exception {
        UUID owner = account(true);
        Browser current = csrf(cookie(session(owner, "ROLE_USER", "password")));
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger completed = new AtomicInteger();
        AtomicInteger denied = new AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> {
                concurrentChange(current, NEW, start, completed, denied);
                return null;
            });
            var second = pool.submit(() -> {
                concurrentChange(current, "OtherSyntheticPassword-2026!", start, completed, denied);
                return null;
            });
            start.countDown();
            first.get(30, TimeUnit.SECONDS);
            second.get(30, TimeUnit.SECONDS);
        }
        assertThat(completed).hasValue(1);
        assertThat(denied).hasValue(1);
        assertThat(audits(owner)).isEqualTo(1);
        String verifier = verifier(owner);
        assertThat(passwords.matches(NEW, verifier)
                ^ passwords.matches("OtherSyntheticPassword-2026!", verifier)).isTrue();
    }

    @Test void staleInFlightSessionCannotResurrectRevokedAuthority() throws Exception {
        UUID owner = account(true);
        Browser current = csrf(cookie(session(owner, "ROLE_USER", "password")));
        String oldSession = session(owner, "ROLE_USER", "oidc");
        CountDownLatch staleLoaded = new CountDownLatch(1);
        CountDownLatch changed = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var inFlight = pool.submit(() -> {
                Session stale = repository().findById(oldSession);
                staleLoaded.countDown();
                if (!changed.await(15, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Synthetic change did not commit");
                }
                stale.setLastAccessedTime(clock.instant().plusSeconds(1));
                try { repository().save(stale); }
                catch (DataAccessException rejected) { /* Deleted authority is still absent. */ }
                return null;
            });
            assertThat(staleLoaded.await(5, TimeUnit.SECONDS)).isTrue();
            try { change(current, NEW).andExpect(status().isNoContent()); }
            finally { changed.countDown(); }
            inFlight.get(5, TimeUnit.SECONDS);
        }
        assertThat(repository().findById(oldSession)).isNull();
        mvc.perform(get("/api/me/security").cookie(cookie(oldSession)))
                .andExpect(status().isUnauthorized());
    }

    @Test void unrelatedLegacySessionLockDoesNotBlockOwnerChange() throws Exception {
        UUID owner = account(true);
        UUID unrelated = account(true);
        Browser current = csrf(cookie(session(owner, "ROLE_USER", "password")));
        String ownerLegacy = session(owner, "ROLE_USER", null);
        String unrelatedLegacy = session(unrelated, "ROLE_USER", null);
        for (String id : List.of(ownerLegacy, unrelatedLegacy)) {
            jdbc.update("update identity.spring_session set principal_name = ? where session_id = ?",
                    "IdentitySessionPrincipal[REDACTED]", id);
        }
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var holder = pool.submit(() -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    jdbc.queryForObject("""
                            select primary_id from identity.spring_session
                            where session_id = ? for update
                            """, String.class, unrelatedLegacy);
                    locked.countDown();
                    try {
                        if (!release.await(15, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Synthetic lock release timed out");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    }
                });
                return null;
            });
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            try {
                var changed = pool.submit(() -> {
                    change(current, NEW).andExpect(status().isNoContent());
                    return null;
                });
                changed.get(8, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
            holder.get(5, TimeUnit.SECONDS);
        }
        assertThat(repository().findById(ownerLegacy)).isNull();
        assertThat(repository().findById(unrelatedLegacy)).isNotNull();
    }

    @Test void oldCsrfFailsEvenWhenRotatedSessionHasFreshRecentAuthority() throws Exception {
        UUID owner = account(true);
        Browser current = csrf(cookie(session(owner, "ROLE_USER", "password")));
        Cookie rotated = change(current, NEW).andExpect(status().isNoContent())
                .andReturn().getResponse().getCookie("SESSION");
        assertThat(rotated).isNotNull();
        Session persisted = repository().findById(raw(rotated));
        persisted.setAttribute(IdentitySessionState.RECENT_ATTRIBUTE,
                new IdentitySessionState.RecentAuthentication(owner, clock.instant(), "oidc"));
        repository().save(persisted);
        change(new Browser(rotated, current.csrf()), "AnotherSyntheticPassword-2026!")
                .andExpect(status().isForbidden());
        Browser fresh = csrf(rotated);
        change(fresh, "AnotherSyntheticPassword-2026!")
                .andExpect(status().isNoContent());
        assertThat(audits(owner)).isEqualTo(2);
    }

    private void concurrentChange(Browser current, String password, CountDownLatch start,
            AtomicInteger completed, AtomicInteger denied) throws Exception {
        start.await();
        int status = change(current, password).andReturn().getResponse().getStatus();
        if (status == 204) completed.incrementAndGet();
        else if (status == 401 || status == 403) denied.incrementAndGet();
        else throw new AssertionError("Unexpected password-change status: " + status);
    }

    private UUID account(boolean password) {
        UUID id = jdbc.queryForObject("select uuidv7()", UUID.class);
        Instant now = clock.instant();
        jdbc.update("""
                insert into identity.account
                  (user_id, canonical_email, display_email, email_verified_at,
                   password_verifier, account_state, created_at, updated_at)
                values (?, ?, ?, ?, ?, 'active', ?, ?)
                """, id, email(id), email(id), Timestamp.from(now),
                password ? passwords.encode(OLD) : null, Timestamp.from(now), Timestamp.from(now));
        return id;
    }

    private String email(UUID id) {
        return "security-" + id.toString().substring(24) + "@example.test";
    }

    private String verifier(UUID id) {
        return jdbc.queryForObject("select password_verifier from identity.account where user_id = ?",
                String.class, id);
    }

    private int audits(UUID id) {
        return jdbc.queryForObject("""
                select count(*) from identity.security_audit_fact
                where target_user_id = ? and event_category = 'password_change'
                  and outcome_code = 'completed'
                """, Integer.class, id);
    }

    private String session(UUID id, String role, String recentMethod) {
        SessionRepository<Session> repository = repository();
        Session session = repository.createSession();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new IdentitySessionPrincipal(id), null,
                List.of(new SimpleGrantedAuthority(role))));
        session.setAttribute("SPRING_SECURITY_CONTEXT", context);
        if (recentMethod != null) {
            Instant at = "expired".equals(recentMethod) ? clock.instant().minusSeconds(3600)
                    : clock.instant();
            session.setAttribute(IdentitySessionState.RECENT_ATTRIBUTE,
                    new IdentitySessionState.RecentAuthentication(id, at, recentMethod));
        }
        repository.save(session);
        return session.getId();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private SessionRepository<Session> repository() {
        return (SessionRepository) sessions;
    }

    private Cookie cookie(String id) {
        return new Cookie("SESSION", Base64.getEncoder().encodeToString(
                id.getBytes(StandardCharsets.UTF_8)));
    }

    private String raw(Cookie cookie) {
        return new String(Base64.getDecoder().decode(cookie.getValue()), StandardCharsets.UTF_8);
    }

    private Browser csrf(Cookie cookie) throws Exception {
        var request = get("/api/auth/csrf");
        if (cookie != null) request.cookie(cookie);
        var result = mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse();
        String json = result.getContentAsString();
        return new Browser(result.getCookie("SESSION") == null ? cookie : result.getCookie("SESSION"),
                json.substring(json.indexOf(":\"") + 2, json.lastIndexOf('"')));
    }

    private org.springframework.test.web.servlet.ResultActions change(Browser browser, String value)
            throws Exception {
        return mvc.perform(put("/api/me/security/password").cookie(browser.cookie())
                .header("X-CSRF-TOKEN", browser.csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body(value)));
    }

    private String body(String value) { return "{\"newPassword\":\"" + value + "\"}"; }

    private record Browser(Cookie cookie, String csrf) { }

    static final class SyntheticRates implements RateLimitPort {
        volatile boolean unavailable;
        volatile boolean throttled;
        @Override public Decision evaluate(Request request) {
            if (unavailable) return new ControlUnavailable();
            if (throttled) return new Throttled(60);
            return new Allowed();
        }
    }

    static final class FaultCheckpoint extends IdentitySessionTransitionCheckpoint {
        volatile int failAt;
        @Override void afterPasswordMutation(jakarta.servlet.http.HttpServletRequest request) {
            if (failAt == 1) throw new IllegalStateException("synthetic_password_fault");
        }
        @Override void afterMfaMutation(jakarta.servlet.http.HttpServletRequest request) {
            if (failAt == 1) throw new IllegalStateException("synthetic_mfa_mutation_fault");
        }
        @Override void afterMfaAudit(jakarta.servlet.http.HttpServletRequest request) {
            if (failAt == 4) throw new IllegalStateException("synthetic_mfa_audit_fault");
        }
        @Override void afterMfaNoticeIntent(jakarta.servlet.http.HttpServletRequest request) {
            if (failAt == 5) throw new IllegalStateException("synthetic_mfa_notice_fault");
        }
        @Override void afterEmailMutation(jakarta.servlet.http.HttpServletRequest request) {
            if (failAt == 1) throw new IllegalStateException("synthetic_email_fault");
        }
        @Override void afterEmailNoticeIntents(jakarta.servlet.http.HttpServletRequest request) {
            if (failAt == 4) throw new IllegalStateException("synthetic_notice_fault");
        }
        @Override void afterOtherSessionRevocation(jakarta.servlet.http.HttpServletRequest request) {
            if (failAt == 2) throw new IllegalStateException("synthetic_revocation_fault");
        }
        @Override void afterSessionMutation(jakarta.servlet.http.HttpServletRequest request) {
            if (failAt == 3) throw new IllegalStateException("synthetic_session_fault");
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Doubles {
        @Bean @Primary SyntheticRates syntheticRates() { return new SyntheticRates(); }
        @Bean @Primary FaultCheckpoint faultCheckpoint() { return new FaultCheckpoint(); }
        @Bean IdentityCoreIntegrationTest.CapturingProvider emailChangeProvider() {
            return new IdentityCoreIntegrationTest.CapturingProvider();
        }
    }
}
