package org.notesknowledge.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
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
    @Autowired FaultCheckpoint faults;
    @Autowired SyntheticRates rates;
    @Autowired PlatformTransactionManager transactionManager;

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
    }
}
