package org.notesknowledge.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.notesknowledge.identity.spi.AccountDeletionProfileConsequence;
import org.notesknowledge.identity.spi.AccountDeletionPublishingConsequence;
import org.notesknowledge.LeaseOwner;
import org.notesknowledge.LeasePolicy;
import org.notesknowledge.profile.infrastructure.identity.AccountDeletionProfileAdapter;
import org.notesknowledge.publishing.infrastructure.identity.AccountDeletionPublishingAdapter;
import org.notesknowledge.security.RateLimitPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("DATABASE") @Tag("API") @Tag("SECURITY")
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@Import(AccountDeletionIntegrationTest.Doubles.class)
class AccountDeletionIntegrationTest {
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            "pgvector/pgvector:0.8.6-pg18-trixie")
            .withDatabaseName("account_deletion").withUsername("deletion_migrator")
            .withPassword("synthetic-deletion-migrator-password");

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("identity.session-handle.key-base64", () ->
                "BAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQ=");
        registry.add("identity.rate.key-base64", () ->
                "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=");
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JdbcIndexedSessionRepository sessions;
    @Autowired PasswordEncoder passwords;
    @Autowired Clock clock;
    @Autowired SyntheticRates rates;
    @Autowired DeletionCheckpoint checkpoint;
    @Autowired ProfileProbe profile;
    @Autowired PublishingProbe publishing;
    @Autowired AccountDeletionProfileAdapter actualProfile;
    @Autowired AccountDeletionPublishingAdapter actualPublishing;
    @Autowired OidcIdentityRepository oidcLinks;
    @Autowired SecurityEmailDeliveryRepository emailWork;
    @Autowired SecurityEmailWorker emailWorker;
    @Autowired SyntheticOidc oidc;

    @Test void deletesAllAuthorityWithNoBodyAndNoReplacementSession() throws Exception {
        UUID owner = account("active");
        jdbc.update("update identity.account set password_verifier = ? where user_id = ?",
                passwords.encode("SyntheticPassword-2026!"), owner);
        UUID unrelated = account("active");
        Browser current = csrf(cookie(session(owner, "ROLE_USER", "password", clock.instant())));
        String other = session(owner, "ROLE_USER", "password", clock.instant());
        String pending = session(owner, "ROLE_MFA_PENDING", null, clock.instant());
        String foreign = session(unrelated, "ROLE_USER", null, clock.instant());
        Session stale = repository().findById(raw(current.cookie()));
        UUID capability = capability(owner);
        UUID delivery = delivery(capability);
        var result = performDelete(current, "{\"confirmAccountDeletion\":true}")
                .andExpect(status().isNoContent()).andReturn().getResponse();
        assertThat(result.getContentAsString()).isEmpty();
        assertThat(result.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(result.getCookie("SESSION")).isNull();
        assertThat(state(owner)).isEqualTo("logically_deleted");
        assertThat(profile.calls).isGreaterThan(0);
        assertThat(publishing.calls).isGreaterThan(0);
        assertThat(profile.inTransaction && publishing.inTransaction).isTrue();
        assertThat(sessions.findById(raw(current.cookie()))).isNull();
        assertThat(sessions.findById(other)).isNull();
        assertThat(sessions.findById(pending)).isNull();
        assertThat(sessions.findById(foreign)).isNotNull();
        assertThat(jdbc.queryForObject("select count(*) from identity.application_session_descriptor "
                + "where user_id = ? and revoked_at is null", Integer.class, owner)).isZero();
        assertThat(jdbc.queryForObject("select revoked_at is not null from identity.identity_capability "
                + "where capability_id = ?", Boolean.class, capability)).isTrue();
        assertThat(jdbc.queryForObject("select state from identity.security_email_delivery "
                + "where security_email_delivery_id = ?", String.class, delivery)).isEqualTo("obsolete");
        assertThat(jdbc.queryForObject("select sealed_token_ciphertext is null and lease_token is null "
                + "from identity.security_email_delivery where security_email_delivery_id = ?",
                Boolean.class, delivery)).isTrue();
        assertThat(auditCount(owner)).isEqualTo(1);
        mvc.perform(get("/api/me/security/sessions").cookie(current.cookie()))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/me/security/sessions").cookie(cookie(foreign)))
                .andExpect(status().isOk());
        Browser anonymous = csrf(null);
        mvc.perform(post("/api/auth/login/password").cookie(anonymous.cookie())
                .header("X-CSRF-TOKEN", anonymous.csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email(owner)
                        + "\",\"password\":\"SyntheticPassword-2026!\"}"))
                .andExpect(status().isUnauthorized());
        assertThat(oidcLinks.markAuthenticatedIfEligible(owner, clock.instant())).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from identity.identity_capability "
                + "where capability_id = ? and revoked_at is null", Integer.class, capability))
                .isZero();
        performDelete(current, "{\"confirmAccountDeletion\":true}")
                .andExpect(status().isForbidden()); // The old CSRF proof dies with its session.
        stale.setLastAccessedTime(clock.instant());
        try { repository().save(stale); }
        catch (org.springframework.dao.DataAccessException rejected) { /* deleted row stays absent */ }
        assertThat(sessions.findById(raw(current.cookie()))).isNull();
    }

    @Test void authorityCsrfConfirmationAndRateControlsFailClosed() throws Exception {
        UUID owner = account("active");
        Browser good = csrf(cookie(session(owner, "ROLE_USER", "password", clock.instant())));
        Browser noRecent = csrf(cookie(session(owner, "ROLE_USER", null, clock.instant())));
        Browser expired = csrf(cookie(session(owner, "ROLE_USER", "password",
                clock.instant().minusSeconds(3600))));
        Browser pending = csrf(cookie(session(owner, "ROLE_MFA_PENDING", "password", clock.instant())));
        UUID other = account("active");
        Browser wrongRecent = csrf(cookie(sessionWithRecentOwner(owner, other)));
        Browser suspended = csrf(cookie(session(account("suspended"), "ROLE_USER", "password",
                clock.instant())));
        Browser anonymous = csrf(null);
        mvc.perform(delete("/api/me/account").cookie(anonymous.cookie())
                .header("X-CSRF-TOKEN", anonymous.csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"confirmAccountDeletion\":true}"))
                .andExpect(status().isUnauthorized());
        performDelete(pending, "{\"confirmAccountDeletion\":true}")
                .andExpect(status().isForbidden());
        performDelete(suspended, "{\"confirmAccountDeletion\":true}")
                .andExpect(status().isUnauthorized());
        performDelete(noRecent, "{\"confirmAccountDeletion\":true}")
                .andExpect(status().isForbidden());
        performDelete(expired, "{\"confirmAccountDeletion\":true}")
                .andExpect(status().isForbidden());
        performDelete(wrongRecent, "{\"confirmAccountDeletion\":true}")
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/me/account").cookie(good.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"confirmAccountDeletion\":true}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/me/account").cookie(good.cookie())
                .header("X-CSRF-TOKEN", "synthetic-wrong-csrf")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"confirmAccountDeletion\":true}"))
                .andExpect(status().isForbidden());
        performDelete(good, "{\"confirmAccountDeletion\":false}")
                .andExpect(status().isUnprocessableEntity());
        performDelete(good, "{}").andExpect(status().isUnprocessableEntity());
        performDelete(good, "{\"confirmAccountDeletion\":true,\"unknown\":1}")
                .andExpect(status().isBadRequest());
        rates.throttled = true;
        performDelete(good, "{\"confirmAccountDeletion\":true}")
                .andExpect(status().isTooManyRequests());
        rates.throttled = false;
        rates.unavailable = true;
        performDelete(good, "{\"confirmAccountDeletion\":true}")
                .andExpect(status().isServiceUnavailable());
        rates.unavailable = false;
        assertThat(state(owner)).isEqualTo("active");
        assertThat(auditCount(owner)).isZero();
    }

    @Test void everyInTransactionFailureRollsBackPostgresAccountSessionsAndWork() throws Exception {
        for (String stage : List.of("profile", "publishing", "capability", "sessions",
                "account", "audit")) {
            UUID owner = account("active");
            Browser browser = csrf(cookie(session(owner, "ROLE_USER", "password", clock.instant())));
            String other = session(owner, "ROLE_USER", null, clock.instant());
            UUID capability = capability(owner);
            UUID delivery = delivery(capability);
            checkpoint.failAt = stage;
            try {
                performDelete(browser, "{\"confirmAccountDeletion\":true}")
                        .andExpect(status().isInternalServerError());
            } finally { checkpoint.failAt = null; }
            assertThat(state(owner)).as(stage).isEqualTo("active");
            assertThat(sessions.findById(raw(browser.cookie()))).as(stage).isNotNull();
            assertThat(sessions.findById(other)).as(stage).isNotNull();
            assertThat(jdbc.queryForObject("select revoked_at from identity.identity_capability "
                    + "where capability_id = ?", Timestamp.class, capability)).as(stage).isNull();
            assertThat(jdbc.queryForObject("select state from identity.security_email_delivery "
                    + "where security_email_delivery_id = ?", String.class, delivery))
                    .as(stage).isEqualTo("queued");
            assertThat(auditCount(owner)).as(stage).isZero();
        }
    }

    @Test void providerFailuresAndMandatoryTransactionBoundaryAreReal() throws Exception {
        assertThatThrownBy(() -> actualProfile.makeIneligible(UUID.randomUUID()))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertThatThrownBy(() -> actualPublishing.makeIneligible(UUID.randomUUID()))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        for (String failing : List.of("profile", "publishing")) {
            UUID owner = account("active");
            Browser browser = csrf(cookie(session(owner, "ROLE_USER", "password", clock.instant())));
            profile.fail = failing.equals("profile");
            publishing.fail = failing.equals("publishing");
            try {
                performDelete(browser, "{\"confirmAccountDeletion\":true}")
                        .andExpect(status().isInternalServerError());
            } finally { profile.fail = false; publishing.fail = false; }
            assertThat(state(owner)).isEqualTo("active");
            assertThat(sessions.findById(raw(browser.cookie()))).isNotNull();
            assertThat(auditCount(owner)).isZero();
        }
    }

    @Test void claimedEmailWorkCannotStartAfterDeletionAndRetainsNoTokenMaterial()
            throws Exception {
        UUID owner = account("active");
        Browser browser = csrf(cookie(session(owner, "ROLE_USER", "password", clock.instant())));
        UUID capability = capability(owner);
        UUID work = delivery(capability);
        jdbc.update("update identity.security_email_delivery set next_attempt_at = ? "
                + "where security_email_delivery_id = ?",
                Timestamp.from(clock.instant().minusSeconds(3600)), work);
        var claims = emailWork.claimReady(clock.instant(), new LeaseOwner("DeletionTest"),
                new LeasePolicy(Duration.ofMinutes(20), 4), 1);
        assertThat(claims).hasSize(1);
        assertThat(claims.getFirst().id()).isEqualTo(work);
        assertThat(emailWork.ownsUsableClaim(claims.getFirst(), clock.instant())).isTrue();
        performDelete(browser, "{\"confirmAccountDeletion\":true}")
                .andExpect(status().isNoContent());
        assertThat(emailWork.ownsUsableClaim(claims.getFirst(), clock.instant())).isFalse();
        emailWorker.process(claims.getFirst());
        assertThat(jdbc.queryForObject("select state from identity.security_email_delivery "
                + "where security_email_delivery_id = ?", String.class, work)).isEqualTo("obsolete");
        assertThat(jdbc.queryForObject("select sealed_token_ciphertext is null and "
                + "lease_token is null and terminal_at is not null "
                + "from identity.security_email_delivery where security_email_delivery_id = ?",
                Boolean.class, work)).isTrue();
    }

    @Test void linkedGooglePrincipalCannotReauthenticateDeletedAccount() throws Exception {
        UUID owner = account("active");
        String subject = "synthetic-deleted-" + UUID.randomUUID();
        jdbc.update("""
                insert into identity.external_identity_link
                    (external_identity_link_id, user_id, issuer, subject, linked_at)
                values (uuidv7(), ?, 'https://accounts.google.com', ?, ?)
                """, owner, subject, Timestamp.from(clock.instant()));
        Browser deleting = csrf(cookie(session(owner, "ROLE_USER", "password", clock.instant())));
        performDelete(deleting, "{\"confirmAccountDeletion\":true}")
                .andExpect(status().isNoContent());
        Browser anonymous = csrf(null);
        var start = mvc.perform(post("/api/auth/oidc/google/authorizations")
                .cookie(anonymous.cookie()).header("X-CSRF-TOKEN", anonymous.csrf()))
                .andExpect(status().isOk()).andReturn().getResponse();
        String authorization = new tools.jackson.databind.ObjectMapper().readTree(
                start.getContentAsString()).get("authorizationUrl").asText();
        String state = UriComponentsBuilder.fromUri(URI.create(authorization)).build()
                .getQueryParams().getFirst("state");
        String code = oidc.accept(subject, email(owner));
        mvc.perform(get("/api/auth/oidc/google/callback")
                .cookie(anonymous.cookie()).param("state", state).param("code", code)
                .param("iss", "https://accounts.google.com"))
                .andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("select count(*) from identity.spring_session "
                + "where principal_name = ?", Integer.class, owner.toString())).isZero();
    }

    @Test void deletionWinningAccountLockDeniesRacingPasswordLogin() throws Exception {
        UUID owner = account("active");
        jdbc.update("update identity.account set password_verifier = ? where user_id = ?",
                passwords.encode("SyntheticPassword-2026!"), owner);
        Browser current = csrf(cookie(session(owner, "ROLE_USER", "password", clock.instant())));
        Browser anonymous = csrf(null);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch loginStarted = new CountDownLatch(1);
        checkpoint.configure(locked, release, loginStarted);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var deletion = pool.submit(() -> performDelete(current,
                    "{\"confirmAccountDeletion\":true}").andReturn().getResponse().getStatus());
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            var login = pool.submit(() -> mvc.perform(post("/api/auth/login/password")
                    .cookie(anonymous.cookie()).header("X-CSRF-TOKEN", anonymous.csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"" + email(owner)
                            + "\",\"password\":\"SyntheticPassword-2026!\"}"))
                    .andReturn().getResponse().getStatus());
            try { assertThat(loginStarted.await(10, TimeUnit.SECONDS)).isTrue(); }
            finally { release.countDown(); }
            assertThat(deletion.get(20, TimeUnit.SECONDS)).isEqualTo(204);
            assertThat(login.get(20, TimeUnit.SECONDS)).isEqualTo(401);
        } finally { checkpoint.clear(); }
        assertThat(jdbc.queryForObject("select count(*) from identity.spring_session "
                + "where principal_name = ?", Integer.class, owner.toString())).isZero();
    }

    @Test void deletionWinningAccountLockDeniesRacingPasswordMutation() throws Exception {
        UUID owner = account("active");
        Browser deleting = csrf(cookie(session(owner, "ROLE_USER", "password", clock.instant())));
        Browser mutating = csrf(cookie(session(owner, "ROLE_USER", "password", clock.instant())));
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch mutationStarted = new CountDownLatch(1);
        checkpoint.configureMutation(locked, release, mutationStarted);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var deletion = pool.submit(() -> performDelete(deleting,
                    "{\"confirmAccountDeletion\":true}").andReturn().getResponse().getStatus());
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            var mutation = pool.submit(() -> mvc.perform(
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .put("/api/me/security/password")
                    .cookie(mutating.cookie()).header("X-CSRF-TOKEN", mutating.csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"newPassword\":\"SyntheticNewPassword-2026!\"}"))
                    .andReturn().getResponse().getStatus());
            try { assertThat(mutationStarted.await(10, TimeUnit.SECONDS)).isTrue(); }
            finally { release.countDown(); }
            assertThat(deletion.get(20, TimeUnit.SECONDS)).isEqualTo(204);
            assertThat(mutation.get(20, TimeUnit.SECONDS)).isEqualTo(401);
        } finally { checkpoint.clear(); }
        assertThat(state(owner)).isEqualTo("logically_deleted");
        assertThat(jdbc.queryForObject("select count(*) from identity.security_audit_fact "
                + "where actor_user_id = ? and event_category = 'password_change'",
                Integer.class, owner)).isZero();
    }

    private org.springframework.test.web.servlet.ResultActions performDelete(Browser browser,
            String body) throws Exception {
        return mvc.perform(delete("/api/me/account").cookie(browser.cookie())
                .header("X-CSRF-TOKEN", browser.csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private UUID account(String state) {
        UUID id = jdbc.queryForObject("select uuidv7()", UUID.class);
        Instant now = clock.instant();
        jdbc.update("""
                insert into identity.account (user_id, canonical_email, display_email,
                    email_verified_at, account_state, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?)
                """, id, email(id), email(id), Timestamp.from(now), state,
                Timestamp.from(now), Timestamp.from(now));
        return id;
    }

    private String email(UUID id) { return "deletion-" + id + "@example.test"; }

    private String session(UUID id, String role, String recentMethod, Instant recentAt) {
        Session session = repository().createSession();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new IdentitySessionPrincipal(id), null, List.of(new SimpleGrantedAuthority(role))));
        session.setAttribute("SPRING_SECURITY_CONTEXT", context);
        if (recentMethod != null) session.setAttribute(IdentitySessionState.RECENT_ATTRIBUTE,
                new IdentitySessionState.RecentAuthentication(id, recentAt, recentMethod));
        repository().save(session);
        return session.getId();
    }

    private String sessionWithRecentOwner(UUID id, UUID recentOwner) {
        String raw = session(id, "ROLE_USER", null, clock.instant());
        Session session = repository().findById(raw);
        session.setAttribute(IdentitySessionState.RECENT_ATTRIBUTE,
                new IdentitySessionState.RecentAuthentication(recentOwner, clock.instant(), "password"));
        repository().save(session);
        return raw;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private SessionRepository<Session> repository() { return (SessionRepository) sessions; }

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
        var response = mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse();
        String body = response.getContentAsString();
        return new Browser(response.getCookie("SESSION") == null ? cookie : response.getCookie("SESSION"),
                body.substring(body.indexOf(":\"") + 2, body.lastIndexOf('"')));
    }

    private UUID capability(UUID owner) {
        UUID id = jdbc.queryForObject("select uuidv7()", UUID.class);
        Instant now = clock.instant();
        jdbc.update("""
                insert into identity.identity_capability
                    (capability_id, user_id, purpose, verifier_digest, issued_at, expires_at)
                values (?, ?, 'password_reset', ?, ?, ?)
                """, id, owner, new byte[32], Timestamp.from(now),
                Timestamp.from(now.plusSeconds(1800)));
        return id;
    }

    private UUID delivery(UUID capability) {
        UUID id = jdbc.queryForObject("select uuidv7()", UUID.class);
        Instant now = clock.instant();
        jdbc.update("""
                insert into identity.security_email_delivery
                    (security_email_delivery_id, delivery_kind, capability_id, state,
                     next_attempt_at, sealed_token_ciphertext, sealed_token_nonce,
                     sealed_token_tag, token_key_version, created_at, updated_at)
                values (?, 'capability_link', ?, 'queued', ?, ?, ?, ?, 'synthetic', ?, ?)
                """, id, capability, Timestamp.from(now), new byte[] {1}, new byte[12],
                new byte[16], Timestamp.from(now), Timestamp.from(now));
        return id;
    }

    private String state(UUID owner) {
        return jdbc.queryForObject("select account_state from identity.account where user_id = ?",
                String.class, owner);
    }

    private int auditCount(UUID owner) {
        return jdbc.queryForObject("select count(*) from identity.security_audit_fact "
                + "where actor_user_id = ? and event_category = 'account_deletion'",
                Integer.class, owner);
    }

    private record Browser(Cookie cookie, String csrf) { }

    static final class SyntheticRates implements RateLimitPort {
        volatile boolean throttled;
        volatile boolean unavailable;
        @Override public Decision evaluate(Request request) {
            if (unavailable) return new ControlUnavailable();
            if (throttled) return new Throttled(60);
            return new Allowed();
        }
    }

    static final class ProfileProbe implements AccountDeletionProfileConsequence {
        final AccountDeletionProfileAdapter delegate;
        volatile boolean fail;
        volatile boolean inTransaction;
        volatile int calls;
        ProfileProbe(AccountDeletionProfileAdapter delegate) { this.delegate = delegate; }
        @Override public void makeIneligible(UUID userId) {
            calls++;
            inTransaction = TransactionSynchronizationManager.isActualTransactionActive();
            delegate.makeIneligible(userId);
            if (fail) throw new IllegalStateException("synthetic_profile_deletion_fault");
        }
    }

    static final class PublishingProbe implements AccountDeletionPublishingConsequence {
        final AccountDeletionPublishingAdapter delegate;
        volatile boolean fail;
        volatile boolean inTransaction;
        volatile int calls;
        PublishingProbe(AccountDeletionPublishingAdapter delegate) { this.delegate = delegate; }
        @Override public void makeIneligible(UUID userId) {
            calls++;
            inTransaction = TransactionSynchronizationManager.isActualTransactionActive();
            delegate.makeIneligible(userId);
            if (fail) throw new IllegalStateException("synthetic_publishing_deletion_fault");
        }
    }

    static final class DeletionCheckpoint extends IdentitySessionTransitionCheckpoint {
        volatile String failAt;
        volatile CountDownLatch locked;
        volatile CountDownLatch release;
        volatile CountDownLatch loginStarted;
        volatile CountDownLatch mutationStarted;
        void configure(CountDownLatch locked, CountDownLatch release, CountDownLatch loginStarted) {
            this.locked = locked; this.release = release; this.loginStarted = loginStarted;
        }
        void configureMutation(CountDownLatch locked, CountDownLatch release,
                CountDownLatch mutationStarted) {
            this.locked = locked; this.release = release; this.mutationStarted = mutationStarted;
        }
        void clear() {
            locked = null; release = null; loginStarted = null; mutationStarted = null;
            failAt = null;
        }
        private void fail(String at) {
            if (at.equals(failAt)) throw new IllegalStateException("synthetic_deletion_" + at);
        }
        @Override void afterProfileDeletionConsequence(HttpServletRequest request) {
            fail("profile");
            if (locked != null) {
                locked.countDown();
                try {
                    if (!release.await(12, TimeUnit.SECONDS))
                        throw new IllegalStateException("synthetic_deletion_barrier_timeout");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("synthetic_deletion_interrupted", e);
                }
            }
        }
        @Override void afterPublishingDeletionConsequence(HttpServletRequest request) { fail("publishing"); }
        @Override void afterDeletionCapabilityInvalidation(HttpServletRequest request) { fail("capability"); }
        @Override void afterDeletionSessionRevocation(HttpServletRequest request) { fail("sessions"); }
        @Override void afterAccountDeletionMutation(HttpServletRequest request) { fail("account"); }
        @Override void afterAccountDeletionAudit(HttpServletRequest request) { fail("audit"); }
        @Override void beforePasswordLoginCommit(HttpServletRequest request) {
            if (loginStarted != null) loginStarted.countDown();
        }
        @Override void beforePasswordMutationLock(HttpServletRequest request) {
            if (mutationStarted != null) mutationStarted.countDown();
        }
    }

    static final class SyntheticOidc implements OidcProtocolPort {
        private final SecureRandom random = new SecureRandom();
        private final Map<String, ValidatedPrincipal> accepted = new ConcurrentHashMap<>();
        @Autowired Clock clock;

        String accept(String subject, String email) {
            String code = "synthetic-" + UUID.randomUUID();
            accepted.put(code, new ValidatedPrincipal("https://accounts.google.com", subject,
                    email, true, null, clock.instant()));
            return code;
        }

        @Override public OAuth2AuthorizationRequest begin(Action action) {
            byte[] bytes = new byte[32];
            random.nextBytes(bytes);
            String state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            random.nextBytes(bytes);
            String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            var builder = OAuth2AuthorizationRequest.authorizationCode()
                    .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                    .clientId("synthetic-client-id")
                    .redirectUri("https://example.test/api/auth/oidc/google/callback")
                    .scope("openid", "email").state(state)
                    .attributes(attributes -> attributes.put("nonce", nonce))
                    .additionalParameters(parameters -> parameters.put("nonce", nonce));
            OAuth2AuthorizationRequestCustomizers.withPkce().accept(builder);
            return builder.build();
        }

        @Override public ValidatedPrincipal verify(Action action,
                OAuth2AuthorizationRequest authorization, String code, String returnedState) {
            return accepted.get(code);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Doubles {
        @Bean @Primary SyntheticRates syntheticRates() { return new SyntheticRates(); }
        @Bean @Primary DeletionCheckpoint deletionCheckpoint() { return new DeletionCheckpoint(); }
        @Bean @Primary ProfileProbe profileProbe(AccountDeletionProfileAdapter delegate) {
            return new ProfileProbe(delegate);
        }
        @Bean @Primary PublishingProbe publishingProbe(AccountDeletionPublishingAdapter delegate) {
            return new PublishingProbe(delegate);
        }
        @Bean @Primary SyntheticOidc syntheticOidc() { return new SyntheticOidc(); }
    }
}
