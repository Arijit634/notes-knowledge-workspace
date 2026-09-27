package org.notesknowledge.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.notesknowledge.security.RateLimitPort;
import org.notesknowledge.LeaseOwner;
import org.notesknowledge.LeasePolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import java.util.List;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponentsBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@Tag("DATABASE") @Tag("API") @Tag("SECURITY")
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest
@AutoConfigureMockMvc
@Import(OidcCoreIntegrationTest.Doubles.class)
class OidcCoreIntegrationTest {
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            "pgvector/pgvector:0.8.6-pg18-trixie")
            .withDatabaseName("oidc_core").withUsername("oidc_migrator")
            .withPassword("synthetic-oidc-migrator-password");

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("identity.mfa.key-base64", () ->
                "AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=");
        registry.add("identity.mfa.handle-key-base64", () ->
                "AwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwM=");
        registry.add("identity.rate.key-base64", () ->
                "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired SyntheticProtocol protocol;
    @Autowired MutableClock clock;
    @Autowired SyntheticRates rates;
    @Autowired OidcCheckpoint checkpoint;
    @Autowired MfaSecretCipher cipher;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired TotpEngine totp;
    @Autowired org.springframework.session.jdbc.JdbcIndexedSessionRepository sessionRepository;
    @Autowired org.springframework.session.web.http.DefaultCookieSerializer cookieSerializer;
    @Autowired SecurityEmailDeliveryRepository delivery;
    @Autowired SecurityEmailWorker emailWorker;
    @Autowired CapturingMail mail;

    @Test void linkedAndUnlinkedNoticesResolveCurrentVerifiedDestinationAtDispatch()
            throws Exception {
        UUID owner = account();
        String subject = "notice-" + UUID.randomUUID();
        Browser current = fullBrowser(owner, "password");
        linkCallback(current, startLink(current), protocol.accept(subject, email(owner), true), 204);
        UUID linkId = linkId(owner, subject);
        jdbc.update("update identity.account set password_verifier = ? where user_id = ?",
                passwordEncoder.encode("SyntheticPassword-2026!"), owner);
        unlink(fullBrowser(owner, "password"), linkId, 204);
        String changed = "current-" + UUID.randomUUID() + "@example.test";
        jdbc.update("update identity.account set canonical_email = ?, display_email = ? where user_id = ?",
                changed, changed, owner);
        var claims = delivery.claimReady(clock.instant(), new LeaseOwner("oidc_notice_test"),
                new LeasePolicy(Duration.ofMinutes(2), 100), 100);
        var relevant = claims.stream().filter(c -> owner.equals(c.subjectUserId())
                && ("google_oidc_linked".equals(c.noticeKind())
                    || "google_oidc_unlinked".equals(c.noticeKind()))).toList();
        assertThat(relevant).hasSize(2);
        for (var claim : relevant) {
            assertThat(claim.capabilityId()).isNull();
            assertThat(claim.envelope().ciphertext()).isNull();
            assertThat(claim.recipientEnvelope().ciphertext()).isNull();
            emailWorker.process(claim);
            assertThat(jdbc.queryForObject("""
                    select state from identity.security_email_delivery
                    where security_email_delivery_id = ?
                    """, String.class, claim.id())).isEqualTo("submitted");
        }
        assertThat(mail.recipients).containsExactly(changed, changed);
        assertThat(mail.bodies).allMatch(body -> !body.contains(subject)
                && !body.contains(linkId.toString()) && !body.contains(owner.toString()));
    }

    @Test void explicitLinkUsesCurrentAccountAndRotatesSessionWithOneNotice() throws Exception {
        UUID owner = account();
        UUID sameEmailAccount = account();
        Browser current = fullBrowser(owner, "password");
        Browser otherOwnerSession = fullBrowser(owner, null);
        Browser unrelated = fullBrowser(sameEmailAccount, null);
        String subject = "linked-" + UUID.randomUUID();
        String state = startLink(current);
        Session pending = sessionRepository.findById(cookieId(current));
        OidcSessionTransaction transaction = pending.getAttribute(OidcSessionTransaction.ATTRIBUTE);
        assertThat(transaction.action()).isEqualTo(OidcProtocolPort.Action.LINK);
        assertThat(transaction.userId()).isEqualTo(owner);
        assertThat(transaction.sessionId()).isEqualTo(cookieId(current));
        assertThat(transaction.authorization().getAdditionalParameters().get("code_challenge_method"))
                .isEqualTo("S256");
        assertThat((Object) transaction.authorization().getAttribute("nonce")).isNotNull();
        MvcResult result = linkCallback(current, state,
                protocol.accept(subject, email(sameEmailAccount), true), 204);
        Cookie rotated = result.getResponse().getCookie("SESSION");
        assertThat(rotated).isNotNull();
        assertThat(rotated.getValue()).isNotEqualTo(current.cookie().getValue());
        assertThat(sessionState(current)).isEqualTo("anonymous");
        assertThat(sessionState(otherOwnerSession)).isEqualTo("anonymous");
        assertThat(sessionState(unrelated)).isEqualTo("authenticated");
        Browser fresh = csrf(rotated);
        assertThat(sessionState(fresh)).isEqualTo("authenticated");
        mvc.perform(post("/api/auth/logout").cookie(fresh.cookie())
                .header("X-CSRF-TOKEN", current.csrf())).andExpect(status().isForbidden());
        Session linkedSession = sessionRepository.findById(cookieId(fresh));
        assertThat((Object) linkedSession.getAttribute(IdentitySessionState.RECENT_ATTRIBUTE)).isNull();
        assertThat(jdbc.queryForObject("""
                select count(*) from identity.external_identity_link
                where user_id = ? and subject = ? and revoked_at is null
                """, Integer.class, owner, subject)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select count(*) from identity.external_identity_link
                where user_id = ? and subject = ?
                """, Integer.class, sameEmailAccount, subject)).isZero();
        var notice = jdbc.queryForMap("""
                select d.security_event_id, d.sealed_recipient_ciphertext,
                       d.sealed_token_ciphertext, a.correlation_id
                from identity.security_email_delivery d
                join identity.security_audit_fact a on a.correlation_id = d.security_event_id
                where d.subject_user_id = ? and d.notice_kind = 'google_oidc_linked'
                """, owner);
        assertThat(notice.get("correlation_id")).isEqualTo(notice.get("security_event_id"));
        assertThat(notice.get("sealed_recipient_ciphertext")).isNull();
        assertThat(notice.get("sealed_token_ciphertext")).isNull();
        linkCallback(current, state, protocol.accept(subject, email(owner), true), 401);
    }

    @Test void linkConflictsAndSameOwnerReactivationPreserveStableLocator(CapturedOutput output)
            throws Exception {
        UUID owner = account();
        UUID other = account();
        String active = "active-" + UUID.randomUUID();
        String revoked = "revoked-" + UUID.randomUUID();
        link(owner, active);
        link(owner, revoked);
        UUID original = jdbc.queryForObject("""
                select external_identity_link_id from identity.external_identity_link
                where user_id = ? and subject = ?
                """, UUID.class, owner, revoked);
        jdbc.update("update identity.external_identity_link set revoked_at = now() where external_identity_link_id = ?",
                original);
        Browser first = fullBrowser(owner, "password");
        linkCallback(first, startLink(first), protocol.accept(active, email(owner), true), 409);
        Browser second = fullBrowser(other, "password");
        linkCallback(second, startLink(second), protocol.accept(active, email(other), true), 409);
        String otherRevoked = "other-revoked-" + UUID.randomUUID();
        link(other, otherRevoked);
        jdbc.update("update identity.external_identity_link set revoked_at = now() where subject = ?",
                otherRevoked);
        Browser third = fullBrowser(owner, "password");
        linkCallback(third, startLink(third), protocol.accept(otherRevoked, email(owner), true), 409);
        Browser fourth = fullBrowser(owner, "password");
        linkCallback(fourth, startLink(fourth), protocol.accept(revoked, email(owner), true), 204);
        assertThat(jdbc.queryForObject("""
                select external_identity_link_id from identity.external_identity_link
                where user_id = ? and subject = ? and revoked_at is null
                """, UUID.class, owner, revoked)).isEqualTo(original);
        assertThat(jdbc.queryForObject("""
                select count(*) from identity.external_identity_link where subject = ?
                """, Integer.class, revoked)).isEqualTo(1);
        assertThat(output.getAll()).doesNotContain(active, revoked, otherRevoked,
                first.cookie().getValue(), first.csrf());
    }

    @Test void unlinkIsOwnerScopedAndCannotRemoveLastPrimaryMethod() throws Exception {
        UUID owner = account();
        UUID other = account();
        String first = "first-" + UUID.randomUUID();
        String second = "second-" + UUID.randomUUID();
        link(owner, first);
        link(owner, second);
        link(other, "other-" + UUID.randomUUID());
        UUID firstId = linkId(owner, first);
        UUID secondId = linkId(owner, second);
        UUID otherId = jdbc.queryForObject("""
                select external_identity_link_id from identity.external_identity_link
                where user_id = ?
                """, UUID.class, other);
        Browser current = fullBrowser(owner, "password");
        Browser otherOwnerSession = fullBrowser(owner, null);
        Browser unrelated = fullBrowser(other, null);
        unlink(current, otherId, 404);
        unlink(current, UUID.randomUUID(), 404);
        MvcResult removed = unlink(current, firstId, 204);
        Cookie rotated = removed.getResponse().getCookie("SESSION");
        assertThat(rotated).isNotNull();
        assertThat(rotated.getValue()).isNotEqualTo(current.cookie().getValue());
        assertThat(sessionState(current)).isEqualTo("anonymous");
        assertThat(sessionState(otherOwnerSession)).isEqualTo("anonymous");
        assertThat(sessionState(unrelated)).isEqualTo("authenticated");
        Browser fresh = csrf(rotated);
        assertThat(sessionState(fresh)).isEqualTo("authenticated");
        mvc.perform(post("/api/auth/logout").cookie(fresh.cookie())
                .header("X-CSRF-TOKEN", current.csrf())).andExpect(status().isForbidden());
        Session unlinkedSession = sessionRepository.findById(cookieId(fresh));
        assertThat((Object) unlinkedSession.getAttribute(IdentitySessionState.RECENT_ATTRIBUTE)).isNull();
        assertThat(mvc.perform(get("/api/me/security").cookie(fresh.cookie()))
                .andReturn().getResponse().getContentAsString())
                .contains(secondId.toString()).doesNotContain(firstId.toString(), first, second);
        unlink(fullBrowser(owner, "password"), firstId, 404);
        unlink(fullBrowser(owner, "password"), secondId, 409);
        assertThat(jdbc.queryForObject("""
                select count(*) from identity.external_identity_link
                where user_id = ? and revoked_at is null
                """, Integer.class, owner)).isEqualTo(1);
        jdbc.update("update identity.account set password_verifier = ? where user_id = ?",
                passwordEncoder.encode("SyntheticPassword-2026!"), owner);
        unlink(fullBrowser(owner, "password"), secondId, 204);
        assertThat(jdbc.queryForObject("""
                select count(*) from identity.external_identity_link
                where user_id = ? and revoked_at is null
                """, Integer.class, owner)).isZero();
        Browser anonymous = csrf(null);
        callback(anonymous, start(anonymous, false),
                protocol.accept(second, email(owner), true), false, 401);
    }

    @Test void linkStartAndCallbackRejectMissingAuthorityAndStaleProof() throws Exception {
        UUID owner = account();
        Browser anonymous = csrf(null);
        mvc.perform(post("/api/me/security/oidc/google/link-authorizations")
                .cookie(anonymous.cookie()).header("X-CSRF-TOKEN", anonymous.csrf()))
                .andExpect(status().isUnauthorized());
        Browser preMfa = browser(owner, "ROLE_MFA_PENDING", "password");
        mvc.perform(post("/api/me/security/oidc/google/link-authorizations")
                .cookie(preMfa.cookie()).header("X-CSRF-TOKEN", preMfa.csrf()))
                .andExpect(status().isForbidden());
        Browser noRecent = fullBrowser(owner, null);
        mvc.perform(post("/api/me/security/oidc/google/link-authorizations")
                .cookie(noRecent.cookie()).header("X-CSRF-TOKEN", noRecent.csrf()))
                .andExpect(status().isForbidden());
        Browser expired = fullBrowser(owner, "expired");
        mvc.perform(post("/api/me/security/oidc/google/link-authorizations")
                .cookie(expired.cookie()).header("X-CSRF-TOKEN", expired.csrf()))
                .andExpect(status().isForbidden());
        Browser current = fullBrowser(owner, "password");
        mvc.perform(post("/api/me/security/oidc/google/link-authorizations")
                .cookie(current.cookie())).andExpect(status().isForbidden());
        String state = startLink(current);
        String subject = "negative-" + UUID.randomUUID();
        String code = protocol.accept(subject, email(owner), true);
        int beforeIssuer = protocol.verificationCalls.get();
        mvc.perform(get("/api/auth/oidc/google/link-callback")
                .cookie(current.cookie()).param("state", state).param("code", code))
                .andExpect(status().isUnauthorized());
        assertThat(protocol.verificationCalls.get()).isEqualTo(beforeIssuer);
        mvc.perform(get("/api/auth/oidc/google/link-callback")
                .cookie(current.cookie()).param("state", state).param("code", code)
                .param("iss", "https://untrusted.example.test"))
                .andExpect(status().isUnauthorized());
        assertThat(protocol.verificationCalls.get()).isEqualTo(beforeIssuer);
        linkCallback(current, "wrong-state", code, 401);
        var otherSession = fullBrowser(owner, "password");
        linkCallback(otherSession, state, code, 401);
        Session persisted = sessionRepository.findById(cookieId(current));
        persisted.setAttribute(IdentitySessionState.RECENT_ATTRIBUTE,
                new IdentitySessionState.RecentAuthentication(owner,
                        clock.instant().minusSeconds(3600), "password"));
        saveSession(persisted);
        linkCallback(current, state, code, 403);
        Browser wrongAction = fullBrowser(owner, "password");
        String recentState = start(wrongAction, true);
        linkCallback(wrongAction, recentState, code, 401);
        Browser expiredTransaction = fullBrowser(owner, "password");
        String expiringState = startLink(expiredTransaction);
        clock.advanceSeconds(301);
        linkCallback(expiredTransaction, expiringState, code, 401);
        assertThat(jdbc.queryForObject("""
                select count(*) from identity.external_identity_link where subject = ?
                """, Integer.class, subject)).isZero();
        rates.unavailable = true;
        try {
            mvc.perform(post("/api/me/security/oidc/google/link-authorizations")
                    .cookie(current.cookie()).header("X-CSRF-TOKEN", current.csrf()))
                    .andExpect(status().isServiceUnavailable());
            mvc.perform(delete("/api/me/security/oidc-links/" + UUID.randomUUID())
                    .cookie(current.cookie()).header("X-CSRF-TOKEN", current.csrf()))
                    .andExpect(status().isServiceUnavailable());
        } finally {
            rates.unavailable = false;
        }
    }

    @Test void unlinkRequiresFullRecentAuthorityCsrfAndEligibleAccount() throws Exception {
        UUID owner = account();
        String subject = "unlink-guard-" + UUID.randomUUID();
        link(owner, subject);
        UUID locator = linkId(owner, subject);
        Browser noRecent = fullBrowser(owner, null);
        unlink(noRecent, locator, 403);
        unlink(fullBrowser(owner, "expired"), locator, 403);
        Browser preMfa = browser(owner, "ROLE_MFA_PENDING", "password");
        unlink(preMfa, locator, 403);
        Browser current = fullBrowser(owner, "password");
        mvc.perform(delete("/api/me/security/oidc-links/" + locator)
                .cookie(current.cookie())).andExpect(status().isForbidden());
        mvc.perform(delete("/api/me/security/oidc-links/" + locator)
                .cookie(current.cookie()).header("X-CSRF-TOKEN", "wrong"))
                .andExpect(status().isForbidden());
        jdbc.update("update identity.account set account_state = 'suspended' where user_id = ?",
                owner);
        int denied = unlink(current, locator, -1).getResponse().getStatus();
        assertThat(denied).isIn(401, 403);
        assertThat(jdbc.queryForObject("""
                select revoked_at is null from identity.external_identity_link
                where external_identity_link_id = ?
                """, Boolean.class, locator)).isTrue();
    }

    @Test void concurrentLinksToSamePrincipalHaveOneWinner() throws Exception {
        UUID firstOwner = account();
        UUID secondOwner = account();
        Browser first = fullBrowser(firstOwner, "password");
        Browser second = fullBrowser(secondOwner, "password");
        String firstState = startLink(first);
        String secondState = startLink(second);
        String subject = "shared-link-" + UUID.randomUUID();
        String firstCode = protocol.accept(subject, email(firstOwner), true);
        String secondCode = protocol.accept(subject, email(secondOwner), true);
        checkpoint.barrier.set(new CountDownLatch(2));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = executor.submit(() -> linkCallback(first, firstState, firstCode, -1));
            var b = executor.submit(() -> linkCallback(second, secondState, secondCode, -1));
            assertThat(List.of(a.get(30, TimeUnit.SECONDS).getResponse().getStatus(),
                    b.get(30, TimeUnit.SECONDS).getResponse().getStatus()))
                    .containsExactlyInAnyOrder(204, 409);
        } finally {
            checkpoint.barrier.set(null);
        }
        assertThat(jdbc.queryForObject("""
                select count(*) from identity.external_identity_link where subject = ?
                """, Integer.class, subject)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select count(*) from identity.security_email_delivery d
                join identity.security_audit_fact a on a.correlation_id = d.security_event_id
                where d.notice_kind = 'google_oidc_linked' and a.outcome_code = 'linked'
                  and d.subject_user_id in (?, ?)
                """, Integer.class, firstOwner, secondOwner)).isEqualTo(1);
    }

    @Test void concurrentSameLinkCallbackConsumesOnce() throws Exception {
        UUID owner = account();
        Browser browser = fullBrowser(owner, "password");
        String state = startLink(browser);
        String subject = "replay-" + UUID.randomUUID();
        String code = protocol.accept(subject, email(owner), true);
        checkpoint.barrier.set(new CountDownLatch(2));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = executor.submit(() -> linkCallback(browser, state, code, -1));
            var b = executor.submit(() -> linkCallback(browser, state, code, -1));
            assertThat(List.of(a.get(30, TimeUnit.SECONDS).getResponse().getStatus(),
                    b.get(30, TimeUnit.SECONDS).getResponse().getStatus()))
                    .containsExactlyInAnyOrder(204, 401);
        } finally {
            checkpoint.barrier.set(null);
        }
        assertThat(jdbc.queryForObject("""
                select count(*) from identity.external_identity_link where subject = ?
                """, Integer.class, subject)).isEqualTo(1);
    }

    @Test void concurrentUnlinkKeepsOnePasswordlessPrimaryMethod() throws Exception {
        UUID owner = account();
        String first = "unlink-first-" + UUID.randomUUID();
        String second = "unlink-second-" + UUID.randomUUID();
        link(owner, first);
        link(owner, second);
        Browser firstSession = fullBrowser(owner, "password");
        Browser secondSession = fullBrowser(owner, "password");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = executor.submit(() -> {
                ready.countDown(); go.await();
                return unlink(firstSession, linkId(owner, first), -1).getResponse().getStatus();
            });
            var b = executor.submit(() -> {
                ready.countDown(); go.await();
                return unlink(secondSession, linkId(owner, second), -1).getResponse().getStatus();
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<Integer> statuses = List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
            assertThat(statuses).contains(204);
            assertThat(statuses.stream().filter(code -> code != 204).count()).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("""
                select count(*) from identity.external_identity_link
                where user_id = ? and revoked_at is null
                """, Integer.class, owner)).isEqualTo(1);
    }

    @Test void linkAndUnlinkFaultsRollbackAuthoritySessionsAuditAndNotice() throws Exception {
        for (int stage = 1; stage <= 5; stage++) {
            UUID owner = account();
            String subject = "fault-" + UUID.randomUUID();
            Browser linkBrowser = fullBrowser(owner, "password");
            Browser otherSession = fullBrowser(owner, "password");
            String state = startLink(linkBrowser);
            checkpoint.failAt = stage;
            try {
                var failed = mvc.perform(get("/api/auth/oidc/google/link-callback")
                        .cookie(linkBrowser.cookie()).param("state", state)
                        .param("code", protocol.accept(subject, email(owner), true))
                        .param("iss", "https://accounts.google.com")).andReturn();
                assertThat(failed.getResponse().getStatus()).isEqualTo(500);
            } finally {
                checkpoint.failAt = 0;
            }
            assertThat(sessionState(linkBrowser)).isEqualTo("authenticated");
            assertThat(sessionState(otherSession)).isEqualTo("authenticated");
            assertThat(jdbc.queryForObject("""
                    select count(*) from identity.external_identity_link where subject = ?
                    """, Integer.class, subject)).isZero();
            assertThat(jdbc.queryForObject("""
                    select count(*) from identity.security_audit_fact
                    where target_user_id = ? and event_category = 'oidc_link'
                      and outcome_code = 'linked'
                    """, Integer.class, owner)).isZero();
            assertThat(jdbc.queryForObject("""
                    select count(*) from identity.security_email_delivery
                    where subject_user_id = ? and notice_kind = 'google_oidc_linked'
                    """, Integer.class, owner)).isZero();

            link(owner, subject);
            UUID linkId = linkId(owner, subject);
            Browser unlinkBrowser = fullBrowser(owner, "password");
            jdbc.update("update identity.account set password_verifier = ? where user_id = ?",
                    passwordEncoder.encode("SyntheticPassword-2026!"), owner);
            checkpoint.failAt = stage;
            try {
                var failed = mvc.perform(delete("/api/me/security/oidc-links/" + linkId)
                        .cookie(unlinkBrowser.cookie())
                        .header("X-CSRF-TOKEN", unlinkBrowser.csrf())).andReturn();
                assertThat(failed.getResponse().getStatus()).isEqualTo(500);
            } finally {
                checkpoint.failAt = 0;
            }
            assertThat(sessionState(unlinkBrowser)).isEqualTo("authenticated");
            assertThat(sessionState(otherSession)).isEqualTo("authenticated");
            assertThat(jdbc.queryForObject("""
                    select revoked_at is null from identity.external_identity_link
                    where external_identity_link_id = ?
                    """, Boolean.class, linkId)).isTrue();
            assertThat(jdbc.queryForObject("""
                    select count(*) from identity.security_audit_fact
                    where target_user_id = ? and event_category = 'oidc_unlink'
                      and outcome_code = 'unlinked'
                    """, Integer.class, owner)).isZero();
            assertThat(jdbc.queryForObject("""
                    select count(*) from identity.security_email_delivery
                    where subject_user_id = ? and notice_kind = 'google_oidc_unlinked'
                    """, Integer.class, owner)).isZero();
        }
    }

    private UUID linkId(UUID user, String subject) {
        return jdbc.queryForObject("""
                select external_identity_link_id from identity.external_identity_link
                where user_id = ? and subject = ?
                """, UUID.class, user, subject);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void saveSession(Session session) {
        ((SessionRepository) sessionRepository).save(session);
    }

    @Test void verifiedNewPrincipalCreatesOnePasswordlessAccountAndRotatesSession() throws Exception {
        Browser anonymous = csrf(null);
        String subject = "sub-" + UUID.randomUUID();
        String email = "oidc-" + UUID.randomUUID() + "@gmail.com";
        String code = protocol.accept(subject, email, true);
        String state = start(anonymous, false);
        MvcResult result = callback(anonymous, state, code, false, 200);
        Cookie rotated = result.getResponse().getCookie("SESSION");
        assertThat(rotated).isNotNull();
        assertThat(rotated.getValue()).isNotEqualTo(anonymous.cookie().getValue());
        assertThat(sessionState(csrf(rotated))).isEqualTo("authenticated");
        assertThat(sessionState(anonymous)).isEqualTo("anonymous");
        assertThat(jdbc.queryForObject("""
                select count(*) from identity.account a
                join identity.external_identity_link l on l.user_id = a.user_id
                where l.issuer = 'https://accounts.google.com' and l.subject = ?
                  and a.canonical_email = ? and a.account_state = 'active'
                  and a.email_verified_at is not null and a.password_verifier is null
                """, Integer.class, subject, email)).isEqualTo(1);
        callback(anonymous, state, code, false, 401);
        assertThat(jdbc.queryForObject("""
                select count(*) from identity.security_audit_fact
                where event_category = 'oidc_bootstrap' and outcome_code = 'success'
                """, Integer.class)).isGreaterThanOrEqualTo(1);
    }

    @Test void emailCollisionNeverLinksAndUnverifiedBootstrapFails() throws Exception {
        UUID existing = account();
        String email = email(existing);
        Browser anonymous = csrf(null);
        String collisionSubject = "sub-" + UUID.randomUUID();
        String state = start(anonymous, false);
        MvcResult collision = callback(anonymous, state,
                protocol.accept(collisionSubject, email, true,
                        "workspace.example.test", clock.instant()), false, 409);
        assertThat(collision.getResponse().getContentAsString())
                .contains("oidc_account_action_required")
                .doesNotContain(email, existing.toString(), collisionSubject);
        assertThat(jdbc.queryForObject("""
                select count(*) from identity.external_identity_link where subject = ?
                """, Integer.class, collisionSubject)).isZero();
        assertThat(sessionState(anonymous)).isEqualTo("anonymous");
        Browser another = csrf(null);
        callback(another, start(another, false), protocol.accept("sub-" + UUID.randomUUID(),
                "unverified-" + UUID.randomUUID() + "@example.test", false), false, 409);
    }

    @Test void linkedLoginRequiresApplicationMfaAndOldCsrfIsNotAuthority() throws Exception {
        UUID user = account();
        String subject = "sub-" + UUID.randomUUID();
        link(user, subject);
        byte[] seed = new byte[20];
        java.util.Arrays.fill(seed, (byte) 7);
        var sealed = cipher.seal(user, seed);
        jdbc.update("""
                insert into identity.mfa_configuration
                    (user_id, state, seed_ciphertext, seed_nonce, seed_tag, key_version,
                     last_accepted_timestep, current_recovery_generation, enrolled_at, activated_at)
                values (?, 'active', ?, ?, ?, ?, 0, 1, ?, ?)
                """, user, sealed.ciphertext(), sealed.nonce(), sealed.tag(), sealed.keyVersion(),
                java.sql.Timestamp.from(clock.instant()), java.sql.Timestamp.from(clock.instant()));
        Browser anonymous = csrf(null);
        MvcResult primary = callback(anonymous, start(anonymous, false),
                protocol.accept(subject, email(user), true), false, 202);
        Cookie preCookie = primary.getResponse().getCookie("SESSION");
        assertThat(preCookie).isNotNull();
        Browser pre = csrf(preCookie);
        assertThat(sessionState(pre)).isEqualTo("mfaRequired");
        org.springframework.session.Session prePersisted = sessionRepository.findById(cookieId(pre));
        IdentitySessionState.Challenge preChallenge =
                prePersisted.getAttribute(IdentitySessionState.CHALLENGE_ATTRIBUTE);
        assertThat(preChallenge.primaryMethod()).isEqualTo("oidc");
        mvc.perform(get("/api/me/security").cookie(pre.cookie()))
                .andExpect(status().isForbidden());
        String challenge = json(primary, "challengeId");
        String code = totp.codeAt(seed, clock.instant().getEpochSecond() / 30);
        MvcResult elevated = mvc.perform(post("/api/auth/mfa/challenges/" + challenge + "/totp")
                .cookie(pre.cookie()).header("X-CSRF-TOKEN", pre.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk()).andReturn();
        assertThat(sessionState(csrf(elevated.getResponse().getCookie("SESSION"))))
                .isEqualTo("authenticated");
        assertThat(sessionState(pre)).isEqualTo("anonymous");
    }

    @Test void recentAuthRequiresCurrentLinkedPrincipalAndNeverChangesAccount() throws Exception {
        UUID user = account();
        String subject = "sub-" + UUID.randomUUID();
        link(user, subject);
        Browser anonymous = csrf(null);
        MvcResult primary = callback(anonymous, start(anonymous, false),
                protocol.accept(subject, email(user), true), false, 200);
        Browser full = csrf(primary.getResponse().getCookie("SESSION"));
        String state = start(full, true);
        callback(full, state, protocol.accept(subject, email(user), true), true, 204);
        assertThat(sessionState(full)).isEqualTo("authenticated");
        org.springframework.session.Session persisted =
                sessionRepository.findById(cookieId(full));
        assertThat(persisted).isNotNull();
        Object recent = persisted.getAttribute(IdentitySessionState.RECENT_ATTRIBUTE);
        assertThat(recent).isInstanceOf(IdentitySessionState.RecentAuthentication.class);
        assertThat(((IdentitySessionState.RecentAuthentication) recent).userId()).isEqualTo(user);
        assertThat(((IdentitySessionState.RecentAuthentication) recent).method()).isEqualTo("oidc");
        callback(full, state, protocol.accept(subject, email(user), true), true, 401);

        String wrongState = start(full, true);
        callback(full, wrongState,
                protocol.accept("unlinked-" + UUID.randomUUID(), email(user), true), true, 401);
        assertThat(sessionState(full)).isEqualTo("authenticated");
        assertThat(jdbc.queryForObject("""
                select count(*) from identity.security_audit_fact
                where target_user_id = ? and event_category = 'oidc_recent_auth'
                  and outcome_code = 'denied'
                  and reason_code = 'recent_auth_identity_mismatch'
                """, Integer.class, user)).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from identity.external_identity_link",
                Integer.class)).isGreaterThanOrEqualTo(1);
    }

    @Test void stateSessionActionExpiryProviderFailureAndRateFailuresDenySafely() throws Exception {
        Browser browser = csrf(null);
        mvc.perform(post("/api/auth/oidc/google/authorizations")
                .cookie(browser.cookie())).andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/reauth/oidc/google/authorizations")
                .cookie(browser.cookie()).header("X-CSRF-TOKEN", browser.csrf()))
                .andExpect(status().isUnauthorized());
        String state = start(browser, false);
        String superseding = start(browser, false);
        String code = protocol.accept("sub-" + UUID.randomUUID(),
                "negative-" + UUID.randomUUID() + "@example.test", true);
        callback(browser, "wrong", code, false, 401);
        callback(browser, state, code, false, 401);
        callback(csrf(null), state, code, false, 401);
        mvc.perform(get("/api/auth/oidc/google/callback").cookie(browser.cookie())
                .param("code", code)).andExpect(status().isUnauthorized());
        clock.advanceSeconds(301);
        callback(browser, superseding, code, false, 401);
        Browser next = csrf(null);
        callback(next, start(next, false), "provider-down", false, 503);
        rates.unavailable = true;
        mvc.perform(post("/api/auth/oidc/google/authorizations")
                .cookie(next.cookie()).header("X-CSRF-TOKEN", next.csrf()))
                .andExpect(status().isServiceUnavailable());
        rates.unavailable = false;
    }

    @Test void suspendedAccountAndWrongIssuerNeverGainAuthority() throws Exception {
        UUID user = account();
        String subject = "sub-" + UUID.randomUUID();
        link(user, subject);
        Browser browser = csrf(null);
        String state = start(browser, false);
        checkpoint.suspendBeforeCommit.set(user);
        callback(browser, state, protocol.accept(subject, email(user), true), false, 401);
        assertThat(sessionState(browser)).isEqualTo("anonymous");

        Browser next = csrf(null);
        String wrongState = start(next, false);
        String code = protocol.accept("sub-" + UUID.randomUUID(),
                "issuer-" + UUID.randomUUID() + "@example.test", true);
        protocol.overrideIssuer(code, "https://untrusted.example.test");
        callback(next, wrongState, code, false, 401);
        assertThat(sessionState(next)).isEqualTo("anonymous");
    }

    @Test void concurrentFirstBootstrapOfSamePrincipalCreatesOnlyOneAccountAndLink()
            throws Exception {
        Browser first = csrf(null);
        Browser second = csrf(null);
        String firstState = start(first, false);
        String secondState = start(second, false);
        String subject = "same-" + UUID.randomUUID();
        String email = "same-" + UUID.randomUUID() + "@example.test";
        String firstCode = protocol.accept(subject, email, true,
                "workspace.example.test", clock.instant());
        String secondCode = protocol.accept(subject, email, true,
                "workspace.example.test", clock.instant());
        checkpoint.barrier.set(new CountDownLatch(2));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = executor.submit(() -> callback(first, firstState, firstCode, false, -1));
            var b = executor.submit(() -> callback(second, secondState, secondCode, false, -1));
            assertThat(java.util.List.of(a.get(30, TimeUnit.SECONDS).getResponse().getStatus(),
                    b.get(30, TimeUnit.SECONDS).getResponse().getStatus()))
                    .containsExactlyInAnyOrder(200, 409);
        } finally {
            checkpoint.barrier.set(null);
        }
        assertThat(jdbc.queryForObject("""
                select count(*) from identity.external_identity_link where subject = ?
                """, Integer.class, subject)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select count(*) from identity.account where canonical_email = ?
                """, Integer.class, email)).isEqualTo(1);
    }

    @Test void sameTransactionConcurrentCallbacksApplyOneAuthorityTransition() throws Exception {
        Browser browser = csrf(null);
        String state = start(browser, false);
        String subject = "sub-" + UUID.randomUUID();
        String email = "race-" + UUID.randomUUID() + "@example.test";
        String code = protocol.accept(subject, email, true,
                "workspace.example.test", clock.instant());
        checkpoint.barrier.set(new CountDownLatch(2));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = executor.submit(() -> callback(browser, state, code, false, -1));
            var b = executor.submit(() -> callback(browser, state, code, false, -1));
            int first = a.get(30, TimeUnit.SECONDS).getResponse().getStatus();
            int second = b.get(30, TimeUnit.SECONDS).getResponse().getStatus();
            assertThat(java.util.List.of(first, second)).containsExactlyInAnyOrder(200, 401);
        } finally {
            checkpoint.barrier.set(null);
        }
        assertThat(jdbc.queryForObject("""
                select count(*) from identity.external_identity_link where subject = ?
                """, Integer.class, subject)).isEqualTo(1);
    }

    @Test void concurrentRecentCallbacksEstablishExactlyOneRecentFact() throws Exception {
        UUID user = account();
        String subject = "sub-" + UUID.randomUUID();
        link(user, subject);
        Browser anonymous = csrf(null);
        MvcResult primary = callback(anonymous, start(anonymous, false),
                protocol.accept(subject, email(user), true), false, 200);
        Browser full = csrf(primary.getResponse().getCookie("SESSION"));
        String state = start(full, true);
        String code = protocol.accept(subject, email(user), true);
        checkpoint.barrier.set(new CountDownLatch(2));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = executor.submit(() -> callback(full, state, code, true, -1));
            var b = executor.submit(() -> callback(full, state, code, true, -1));
            assertThat(java.util.List.of(a.get(30, TimeUnit.SECONDS).getResponse().getStatus(),
                    b.get(30, TimeUnit.SECONDS).getResponse().getStatus()))
                    .containsExactlyInAnyOrder(204, 401);
        } finally {
            checkpoint.barrier.set(null);
        }
        assertThat(sessionState(full)).isEqualTo("authenticated");
        assertThat(jdbc.queryForObject("""
                select count(*) from identity.security_audit_fact
                where target_user_id = ? and event_category = 'oidc_recent_auth'
                  and outcome_code = 'success'
                """, Integer.class, user)).isEqualTo(1);
    }

    @Test void verifiedWorkspaceHostedDomainBootstrapsButThirdPartyMailDoesNot()
            throws Exception {
        String workspaceSubject = "workspace-" + UUID.randomUUID();
        String workspaceEmail = "member-" + UUID.randomUUID() + "@external.test";
        Browser workspace = csrf(null);
        callback(workspace, start(workspace, false), protocol.accept(workspaceSubject,
                workspaceEmail, true, "workspace.example.test", clock.instant()), false, 200);
        assertThat(jdbc.queryForObject("""
                select count(*) from identity.account a
                join identity.external_identity_link l on l.user_id = a.user_id
                where l.subject = ? and a.canonical_email = ?
                  and a.email_verified_at is not null and a.account_state = 'active'
                """, Integer.class, workspaceSubject, workspaceEmail)).isEqualTo(1);

        assertRejectedBootstrap("third-party-" + UUID.randomUUID(),
                "person@external.test", true, null);
        assertRejectedBootstrap("fake-workspace-" + UUID.randomUUID(),
                "person@workspace.example.test", true, null);
        assertRejectedBootstrap("malformed-hosted-" + UUID.randomUUID(),
                "person@external.test", true, "not a domain");
        assertRejectedBootstrap("unverified-" + UUID.randomUUID(),
                "person@external.test", false, "workspace.example.test");
    }

    @Test void existingLinkUsesIssuerAndSubjectEvenWhenEmailChangedOrUnverified()
            throws Exception {
        UUID user = account();
        String subject = "linked-" + UUID.randomUUID();
        link(user, subject);
        Browser browser = csrf(null);
        callback(browser, start(browser, false), protocol.accept(subject,
                "changed@external.test", false), false, 200);
        assertThat(jdbc.queryForObject("""
                select count(*) from identity.external_identity_link
                where subject = ? and user_id = ?
                """, Integer.class, subject, user)).isEqualTo(1);
    }

    @Test void recentAuthenticationRejectsMissingStaleAndFutureProviderAuthTime()
            throws Exception {
        UUID user = account();
        String subject = "recent-" + UUID.randomUUID();
        link(user, subject);
        Browser anonymous = csrf(null);
        MvcResult primary = callback(anonymous, start(anonymous, false),
                protocol.accept(subject, email(user), true), false, 200);
        Browser full = csrf(primary.getResponse().getCookie("SESSION"));
        int linksBefore = jdbc.queryForObject(
                "select count(*) from identity.external_identity_link", Integer.class);
        for (Instant rejected : new Instant[] {null, clock.instant().minusSeconds(301),
                clock.instant().plusSeconds(61)}) {
            callback(full, start(full, true), protocol.accept(subject, email(user), true,
                    null, rejected), true, 401);
        }
        org.springframework.session.Session persisted = sessionRepository.findById(cookieId(full));
        Object recentFact = persisted.getAttribute(IdentitySessionState.RECENT_ATTRIBUTE);
        assertThat(recentFact).isNull();
        assertThat(jdbc.queryForObject(
                "select count(*) from identity.external_identity_link", Integer.class))
                .isEqualTo(linksBefore);
        assertThat(jdbc.queryForObject("""
                select count(*) from identity.security_audit_fact
                where target_user_id = ? and event_category = 'oidc_recent_auth'
                  and outcome_code = 'denied' and reason_code = 'recent_auth_stale'
                """, Integer.class, user)).isGreaterThanOrEqualTo(3);
        // The protocol double represents an ID token that may have an iat but no auth_time.
        callback(full, start(full, true), protocol.accept(subject, email(user), true,
                null, clock.instant().minusSeconds(20)), true, 204);
        org.springframework.session.Session refreshed = sessionRepository.findById(cookieId(full));
        assertThat(((IdentitySessionState.RecentAuthentication) refreshed
                .getAttribute(IdentitySessionState.RECENT_ATTRIBUTE))
                .userId()).isEqualTo(user);
    }

    @Test void authorizationResponseIssuerIsRequiredBeforeExchangeOnBothCallbacks()
            throws Exception {
        Browser anonymous = csrf(null);
        String loginState = start(anonymous, false);
        String loginCode = protocol.accept("issuer-" + UUID.randomUUID(),
                "issuer-" + UUID.randomUUID() + "@gmail.com", true);
        int calls = protocol.verificationCalls.get();
        callbackWithIssuer(anonymous, loginState, loginCode, false, 401, null);
        callbackWithIssuer(anonymous, loginState, loginCode, false, 401,
                "https://wrong.example.test");
        callbackWithIssuer(anonymous, loginState, loginCode, false, 401,
                "x".repeat(300));
        assertThat(protocol.verificationCalls.get()).isEqualTo(calls);
        MvcResult primary = callback(anonymous, loginState, loginCode, false, 200);
        assertThat(protocol.verificationCalls.get()).isEqualTo(calls + 1);

        Browser full = csrf(primary.getResponse().getCookie("SESSION"));
        String recentState = start(full, true);
        String recentCode = protocol.accept("unlinked-" + UUID.randomUUID(),
                "changed@external.test", true);
        calls = protocol.verificationCalls.get();
        callbackWithIssuer(full, recentState, recentCode, true, 401, null);
        callbackWithIssuer(full, recentState, recentCode, true, 401,
                "https://wrong.example.test");
        assertThat(protocol.verificationCalls.get()).isEqualTo(calls);
        callback(full, recentState, recentCode, true, 401);
        assertThat(protocol.verificationCalls.get()).isEqualTo(calls + 1);
        org.springframework.session.Session persisted = sessionRepository.findById(cookieId(full));
        Object recentFact = persisted.getAttribute(IdentitySessionState.RECENT_ATTRIBUTE);
        assertThat(recentFact).isNull();
    }

    @Test void recentAuthIsRecheckedAfterProtocolWorkBeforeSessionCommit() throws Exception {
        UUID user = account();
        String subject = "delayed-" + UUID.randomUUID();
        link(user, subject);
        Browser anonymous = csrf(null);
        MvcResult primary = callback(anonymous, start(anonymous, false),
                protocol.accept(subject, email(user), true), false, 200);
        Browser full = csrf(primary.getResponse().getCookie("SESSION"));
        String state = start(full, true);
        String code = protocol.accept(subject, email(user), true);
        checkpoint.advanceBeforeCommitSeconds.set(301L);
        callback(full, state, code, true, 401);
        org.springframework.session.Session persisted = sessionRepository.findById(cookieId(full));
        Object recentFact = persisted.getAttribute(IdentitySessionState.RECENT_ATTRIBUTE);
        assertThat(recentFact).isNull();
    }

    @Test void oidcFailureAuditUsesOnlyBoundedReasonsAndNoCanaries(CapturedOutput output)
            throws Exception {
        Browser browser = csrf(null);
        String state = start(browser, false);
        String canary = "synthetic-oidc-canary-" + UUID.randomUUID();
        String code = protocol.accept("subject-" + UUID.randomUUID(),
                "person@gmail.com", true);
        callback(browser, "wrong-state", code, false, 401);
        callback(browser, state, canary, false, 401);
        callback(browser, state, "provider-down", false, 503);
        assertThat(jdbc.queryForObject("""
                select count(*) from identity.security_audit_fact
                where event_category = 'oidc_login' and outcome_code = 'denied'
                  and reason_code in ('protocol_state_invalid',
                                      'provider_validation_failed', 'provider_unavailable')
                """, Integer.class)).isGreaterThanOrEqualTo(3);
        assertThat(jdbc.queryForObject("""
                select count(*) from identity.security_audit_fact
                where event_category like 'oidc%' and
                  (event_category || outcome_code || coalesce(reason_code, '')) like ?
                """, Integer.class, "%" + canary + "%")).isZero();
        assertThat(output.getAll()).doesNotContain(canary);
    }

    private void assertRejectedBootstrap(String subject, String email, boolean verified,
            String hostedDomain) throws Exception {
        Browser browser = csrf(null);
        MvcResult rejected = callback(browser, start(browser, false),
                protocol.accept(subject, email, verified, hostedDomain, clock.instant()),
                false, 409);
        assertThat(rejected.getResponse().getContentAsString())
                .contains("oidc_account_action_required").doesNotContain(email, subject);
        assertThat(jdbc.queryForObject(
                "select count(*) from identity.external_identity_link where subject = ?",
                Integer.class, subject)).isZero();
        assertThat(jdbc.queryForObject(
                "select count(*) from identity.account where canonical_email = ?",
                Integer.class, email)).isZero();
    }

    private UUID account() {
        UUID id = jdbc.queryForObject("select uuidv7()", UUID.class);
        jdbc.update("""
                insert into identity.account (user_id, canonical_email, display_email,
                    email_verified_at, account_state, created_at, updated_at)
                values (?, ?, ?, now(), 'active', now(), now())
                """, id, email(id), email(id));
        return id;
    }

    private String email(UUID id) { return "oidc-" + id + "@example.test"; }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Browser fullBrowser(UUID userId, String recentMethod) throws Exception {
        return browser(userId, "ROLE_USER", recentMethod);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Browser browser(UUID userId, String role, String recentMethod) throws Exception {
        SessionRepository<Session> repository = (SessionRepository) sessionRepository;
        Session session = repository.createSession();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new IdentitySessionPrincipal(userId), null,
                List.of(new SimpleGrantedAuthority(role))));
        session.setAttribute("SPRING_SECURITY_CONTEXT", context);
        if (recentMethod != null) {
            session.setAttribute(IdentitySessionState.RECENT_ATTRIBUTE,
                    new IdentitySessionState.RecentAuthentication(userId,
                            "expired".equals(recentMethod) ? clock.instant().minusSeconds(3600)
                                    : clock.instant(), "password"));
        }
        repository.save(session);
        return csrf(new Cookie("SESSION", Base64.getEncoder().encodeToString(
                session.getId().getBytes(StandardCharsets.UTF_8))));
    }

    private String startLink(Browser browser) throws Exception {
        MvcResult result = mvc.perform(post("/api/me/security/oidc/google/link-authorizations")
                .cookie(browser.cookie()).header("X-CSRF-TOKEN", browser.csrf()))
                .andExpect(status().isOk()).andReturn();
        assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
        return UriComponentsBuilder.fromUri(URI.create(json(result, "authorizationUrl")))
                .build().getQueryParams().getFirst("state");
    }

    private MvcResult linkCallback(Browser browser, String state, String code, int expected)
            throws Exception {
        MvcResult result = mvc.perform(get("/api/auth/oidc/google/link-callback")
                .cookie(browser.cookie()).param("state", state).param("code", code)
                .param("iss", "https://accounts.google.com")).andReturn();
        if (result.getResponse().getStatus() == 500) {
            throw new AssertionError("Link callback failed", result.getResolvedException());
        }
        if (expected >= 0) assertThat(result.getResponse().getStatus()).isEqualTo(expected);
        return result;
    }

    private MvcResult unlink(Browser browser, UUID linkId, int expected) throws Exception {
        MvcResult result = mvc.perform(delete("/api/me/security/oidc-links/" + linkId)
                .cookie(browser.cookie()).header("X-CSRF-TOKEN", browser.csrf())).andReturn();
        if (result.getResponse().getStatus() == 500) {
            throw new AssertionError("Unlink failed", result.getResolvedException());
        }
        if (expected >= 0) assertThat(result.getResponse().getStatus()).isEqualTo(expected);
        return result;
    }

    private void link(UUID user, String subject) {
        jdbc.update("""
                insert into identity.external_identity_link
                    (external_identity_link_id, user_id, issuer, subject, linked_at)
                values (uuidv7(), ?, 'https://accounts.google.com', ?, ?)
                """, user, subject, java.sql.Timestamp.from(clock.instant()));
    }

    private Browser csrf(Cookie cookie) throws Exception {
        var builder = get("/api/auth/csrf");
        if (cookie != null) builder.cookie(cookie);
        MvcResult result = mvc.perform(builder).andExpect(status().isOk()).andReturn();
        Cookie current = result.getResponse().getCookie("SESSION");
        return new Browser(current == null ? cookie : current, json(result, "csrfToken"));
    }

    private String start(Browser browser, boolean recent) throws Exception {
        String path = recent ? "/api/auth/reauth/oidc/google/authorizations"
                : "/api/auth/oidc/google/authorizations";
        MvcResult result = mvc.perform(post(path).cookie(browser.cookie())
                .header("X-CSRF-TOKEN", browser.csrf())).andExpect(status().isOk()).andReturn();
        assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
        URI authorization = URI.create(json(result, "authorizationUrl"));
        return UriComponentsBuilder.fromUri(authorization).build()
                .getQueryParams().getFirst("state");
    }

    private MvcResult callback(Browser browser, String state, String code,
            boolean recent, int expected) throws Exception {
        return callbackWithIssuer(browser, state, code, recent, expected,
                "https://accounts.google.com");
    }

    private MvcResult callbackWithIssuer(Browser browser, String state, String code,
            boolean recent, int expected, String issuer) throws Exception {
        String path = recent ? "/api/auth/reauth/oidc/google/callback"
                : "/api/auth/oidc/google/callback";
        var request = get(path).cookie(browser.cookie()).param("state", state).param("code", code);
        if (issuer != null) request.param("iss", issuer);
        MvcResult result = mvc.perform(request).andReturn();
        if (expected >= 0) assertThat(result.getResponse().getStatus()).isEqualTo(expected);
        return result;
    }

    private String sessionState(Browser browser) throws Exception {
        return json(mvc.perform(get("/api/auth/session").cookie(browser.cookie()))
                .andExpect(status().isOk()).andReturn(), "state");
    }

    private String json(MvcResult result, String field) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString()).get(field).asText();
    }

    private String cookieId(Browser browser) {
        var raw = new org.springframework.mock.web.MockHttpServletRequest();
        raw.setCookies(browser.cookie());
        return cookieSerializer.readCookieValues(raw).get(0);
    }

    record Browser(Cookie cookie, String csrf) { }

    static final class MutableClock extends Clock {
        private volatile Instant at = Instant.parse("2026-09-25T12:00:00Z");
        void advanceSeconds(long seconds) { at = at.plusSeconds(seconds); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return at; }
    }

    static final class SyntheticRates implements RateLimitPort {
        volatile boolean unavailable;
        @Override public Decision evaluate(Request request) {
            return unavailable ? new ControlUnavailable() : new Allowed();
        }
    }

    static final class CapturingMail implements SecurityEmailProviderPort {
        final java.util.List<String> recipients = new java.util.concurrent.CopyOnWriteArrayList<>();
        final java.util.List<String> bodies = new java.util.concurrent.CopyOnWriteArrayList<>();
        @Override public Outcome submit(String recipient,
                SecurityEmailMessageRenderer.Message message) {
            recipients.add(recipient);
            bodies.add(message.body());
            return Outcome.SUBMITTED;
        }
    }

    static final class OidcCheckpoint extends IdentitySessionTransitionCheckpoint {
        @Autowired JdbcTemplate jdbc;
        @Autowired MutableClock clock;
        final AtomicReference<CountDownLatch> barrier = new AtomicReference<>();
        final AtomicReference<UUID> suspendBeforeCommit = new AtomicReference<>();
        final AtomicReference<Long> advanceBeforeCommitSeconds = new AtomicReference<>();
        volatile int failAt;
        @Override void afterOidcLinkMutation(jakarta.servlet.http.HttpServletRequest request) {
            if (failAt == 1) throw new IllegalStateException("synthetic_link_mutation_fault");
        }
        @Override void afterOtherSessionRevocation(jakarta.servlet.http.HttpServletRequest request) {
            if (failAt == 2) throw new IllegalStateException("synthetic_link_revocation_fault");
        }
        @Override void afterSessionMutation(jakarta.servlet.http.HttpServletRequest request) {
            if (failAt == 3) throw new IllegalStateException("synthetic_link_session_fault");
        }
        @Override void afterOidcLinkAudit(jakarta.servlet.http.HttpServletRequest request) {
            if (failAt == 4) throw new IllegalStateException("synthetic_link_audit_fault");
        }
        @Override void afterOidcLinkNotice(jakarta.servlet.http.HttpServletRequest request) {
            if (failAt == 5) throw new IllegalStateException("synthetic_link_notice_fault");
        }
        @Override void beforeOidcLock(jakarta.servlet.http.HttpServletRequest request) {
            Long advance = advanceBeforeCommitSeconds.getAndSet(null);
            if (advance != null) clock.advanceSeconds(advance);
            UUID user = suspendBeforeCommit.getAndSet(null);
            if (user != null) jdbc.update("""
                    update identity.account set account_state = 'suspended' where user_id = ?
                    """, user);
            CountDownLatch latch = barrier.get();
            if (latch == null) return;
            latch.countDown();
            try {
                if (!latch.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("race_timeout");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("race_interrupted", interrupted);
            }
        }
    }

    static final class SyntheticProtocol implements OidcProtocolPort {
        private final SecureRandom random = new SecureRandom();
        private final Map<String, ValidatedPrincipal> accepted = new ConcurrentHashMap<>();
        private final AtomicInteger verificationCalls = new AtomicInteger();
        @Autowired MutableClock clock;

        String accept(String subject, String email, boolean verified) {
            return accept(subject, email, verified, null, clock.instant());
        }

        String accept(String subject, String email, boolean verified, String hostedDomain,
                Instant authTime) {
            String code = "synthetic-" + UUID.randomUUID();
            accepted.put(code, new ValidatedPrincipal("https://accounts.google.com",
                    subject, email, verified, hostedDomain, authTime));
            return code;
        }

        void overrideIssuer(String code, String issuer) {
            ValidatedPrincipal existing = accepted.get(code);
            accepted.put(code, new ValidatedPrincipal(issuer, existing.subject(),
                    existing.email(), existing.emailVerified(), existing.hostedDomain(),
                    existing.authTime()));
        }

        @Override public OAuth2AuthorizationRequest begin(Action action) {
            String state = randomValue();
            String nonce = randomValue();
            String redirect = switch (action) {
                case LOGIN -> "https://example.test/api/auth/oidc/google/callback";
                case RECENT_AUTH -> "https://example.test/api/auth/reauth/oidc/google/callback";
                case LINK -> "https://example.test/api/auth/oidc/google/link-callback";
            };
            var builder = OAuth2AuthorizationRequest.authorizationCode()
                    .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                    .clientId("synthetic-client-id").redirectUri(redirect)
                    .scope("openid", "email").state(state)
                    .attributes(attributes -> attributes.put("nonce", nonce))
                    .additionalParameters(parameters -> parameters.put("nonce", nonce));
            OAuth2AuthorizationRequestCustomizers.withPkce().accept(builder);
            return builder.build();
        }

        @Override public ValidatedPrincipal verify(Action action,
                OAuth2AuthorizationRequest authorization, String code, String returnedState) {
            verificationCalls.incrementAndGet();
            if ("provider-down".equals(code)) throw org.notesknowledge.websupport.ApiFailureException
                    .of(org.notesknowledge.websupport.ApiFailureException.Kind.SERVICE_UNAVAILABLE);
            ValidatedPrincipal principal = accepted.get(code);
            if (principal == null) throw org.notesknowledge.websupport.ApiFailureException
                    .of(org.notesknowledge.websupport.ApiFailureException.Kind.INVALID_CREDENTIALS);
            return principal;
        }

        private String randomValue() {
            byte[] bytes = new byte[32];
            random.nextBytes(bytes);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Doubles {
        @Bean @Primary MutableClock oidcClock() { return new MutableClock(); }
        @Bean @Primary SyntheticRates oidcRates() { return new SyntheticRates(); }
        @Bean @Primary SyntheticProtocol oidcProtocol() { return new SyntheticProtocol(); }
        @Bean @Primary OidcCheckpoint oidcCheckpoint() { return new OidcCheckpoint(); }
        @Bean @Primary CapturingMail oidcMail() { return new CapturingMail(); }
    }
}
