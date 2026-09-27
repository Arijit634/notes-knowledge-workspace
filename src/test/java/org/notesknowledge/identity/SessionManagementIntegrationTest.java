package org.notesknowledge.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import java.util.stream.Stream;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@Tag("DATABASE") @Tag("API") @Tag("SECURITY")
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@Import(SessionManagementIntegrationTest.Doubles.class)
@ExtendWith(OutputCaptureExtension.class)
class SessionManagementIntegrationTest {
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            "pgvector/pgvector:0.8.6-pg18-trixie")
            .withDatabaseName("session_management").withUsername("session_migrator")
            .withPassword("synthetic-session-migrator-password");

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
    @Autowired ObjectMapper json;
    @Autowired Clock clock;
    @Autowired SyntheticRates rates;
    @Autowired PasswordEncoder passwords;
    @Autowired SessionRaceCheckpoint race;
    @Autowired FaultyDescriptors faultyDescriptors;

    @Test void listReconcilesOnlyActiveFullOwnerSessionsAndDoesNotLeakRawMetadata(
            CapturedOutput output) throws Exception {
        UUID owner = account();
        UUID unrelated = account();
        String first = session(owner, "ROLE_USER", null);
        String second = session(owner, "ROLE_USER", null);
        String pending = session(owner, "ROLE_MFA_PENDING", null);
        String other = session(unrelated, "ROLE_USER", null);
        jdbc.update("update identity.spring_session set principal_name = ? where session_id = ?",
                "IdentitySessionPrincipal[REDACTED]", second);
        Browser browser = csrf(cookie(first));
        var response = mvc.perform(get("/api/me/security/sessions").cookie(browser.cookie())
                .header("User-Agent", "Firefox/1 Windows private-synthetic-marker"))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        var items = json.readTree(response.getContentAsString()).get("sessions");
        assertThat(items.size()).isEqualTo(2);
        assertThat(items.get(0).get("current").asBoolean()).isTrue();
        assertThat(items.get(1).get("current").asBoolean()).isFalse();
        String serialized = response.getContentAsString();
        assertThat(serialized).doesNotContain(first, second, other, owner.toString(),
                "private-synthetic-marker", "SPRING_SECURITY_CONTEXT");
        assertThat(jdbc.queryForList("select client_label from identity.application_session_descriptor",
                String.class)).containsOnly("Existing session");
        assertThat(jdbc.queryForObject("""
                select count(*) from identity.application_session_descriptor d
                join identity.spring_session s on s.primary_id = d.session_primary_id
                where s.session_id = ?
                """, Integer.class, pending)).isZero();
        assertThat(output.getAll()).doesNotContain(items.get(0).get("sessionHandle").asText());
        mvc.perform(get("/api/me/security/sessions")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/me/security/sessions")
                .cookie(cookie(session(owner, "ROLE_MFA_PENDING", null))))
                .andExpect(status().isForbidden());
    }

    @Test void revokeOneOtherConsumesRecentAndCannotBeReplayed() throws Exception {
        UUID owner = account();
        Browser current = csrf(cookie(session(owner, "ROLE_USER", "password")));
        String other = session(owner, "ROLE_USER", null);
        String handle = handleFor(current, false);
        var result = mvc.perform(delete("/api/me/security/sessions/{handle}", handle)
                .cookie(current.cookie()).header("X-CSRF-TOKEN", current.csrf()))
                .andExpect(status().isNoContent()).andReturn().getResponse();
        assertThat(result.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(sessions.findById(other)).isNull();
        mvc.perform(get("/api/me/security/sessions").cookie(current.cookie()))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/me/security/sessions/{handle}", handle)
                .cookie(current.cookie()).header("X-CSRF-TOKEN", current.csrf()))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("""
                select count(*) from identity.security_audit_fact
                where actor_user_id = ? and event_category = 'session_revocation'
                  and outcome_code = 'one'
                """, Integer.class, owner)).isEqualTo(1);
    }

    @Test void revokeCurrentAndAllKillOldCookiesAndProofs() throws Exception {
        UUID owner = account();
        Browser current = csrf(cookie(session(owner, "ROLE_USER", "password")));
        String currentHandle = handleFor(current, true);
        Session staleCurrent = repository().findById(raw(current.cookie()));
        mvc.perform(delete("/api/me/security/sessions/{handle}", currentHandle)
                .cookie(current.cookie()).header("X-CSRF-TOKEN", current.csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/me/security/sessions").cookie(current.cookie()))
                .andExpect(status().isUnauthorized());
        staleCurrent.setLastAccessedTime(clock.instant());
        try { repository().save(staleCurrent); }
        catch (org.springframework.dao.DataAccessException rejected) { /* no resurrection */ }
        assertThat(sessions.findById(raw(current.cookie()))).isNull();
        Browser next = csrf(cookie(session(owner, "ROLE_USER", "password")));
        String other = session(owner, "ROLE_USER", null);
        String pending = session(owner, "ROLE_MFA_PENDING", null);
        mvc.perform(post("/api/me/security/sessions/revoke-all")
                .cookie(next.cookie()).header("X-CSRF-TOKEN", next.csrf()))
                .andExpect(status().isNoContent());
        assertThat(sessions.findById(other)).isNull();
        assertThat(sessions.findById(pending)).isNull();
        mvc.perform(get("/api/me/security/sessions").cookie(next.cookie()))
                .andExpect(status().isUnauthorized());
        Browser anonymous = csrf(null);
        assertThat(anonymous.csrf()).isNotEqualTo(next.csrf());
    }

    @Test void revokeOthersPreservesCurrentAndUnrelatedWhileStaleSaveCannotResurrect()
            throws Exception {
        UUID owner = account();
        UUID unrelated = account();
        Browser current = csrf(cookie(session(owner, "ROLE_USER", "password")));
        String other = session(owner, "ROLE_USER", null);
        String pending = session(owner, "ROLE_MFA_PENDING", null);
        String foreign = session(unrelated, "ROLE_USER", null);
        Session stale = repository().findById(other);
        mvc.perform(post("/api/me/security/sessions/revoke-others")
                .cookie(current.cookie()).header("X-CSRF-TOKEN", current.csrf()))
                .andExpect(status().isNoContent());
        assertThat(sessions.findById(other)).isNull();
        assertThat(sessions.findById(pending)).isNull();
        assertThat(sessions.findById(foreign)).isNotNull();
        assertThat(mvc.perform(get("/api/me/security/sessions").cookie(current.cookie()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .contains("\"current\":true");
        stale.setLastAccessedTime(clock.instant());
        try { repository().save(stale); }
        catch (org.springframework.dao.DataAccessException rejected) { /* deleted row stays absent */ }
        assertThat(sessions.findById(other)).isNull();
    }

    @Test void forgedForeignAndMissingRecentAreSafe(CapturedOutput output) throws Exception {
        UUID owner = account();
        UUID otherOwner = account();
        Browser current = csrf(cookie(session(owner, "ROLE_USER", "password")));
        Browser foreign = csrf(cookie(session(otherOwner, "ROLE_USER", "password")));
        String foreignHandle = handleFor(foreign, true);
        mvc.perform(delete("/api/me/security/sessions/{handle}", foreignHandle)
                .cookie(current.cookie()).header("X-CSRF-TOKEN", current.csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/me/security/sessions/{handle}", "forged")
                .cookie(current.cookie()).header("X-CSRF-TOKEN", current.csrf()))
                .andExpect(status().isNotFound());
        Browser noRecent = csrf(cookie(session(owner, "ROLE_USER", null)));
        String validOther = handleFor(noRecent, false);
        mvc.perform(delete("/api/me/security/sessions/{handle}", validOther)
                .cookie(noRecent.cookie()).header("X-CSRF-TOKEN", noRecent.csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/me/security/sessions/revoke-others").cookie(current.cookie()))
                .andExpect(status().isForbidden());
        rates.throttled = true;
        mvc.perform(post("/api/me/security/sessions/revoke-others")
                .cookie(current.cookie()).header("X-CSRF-TOKEN", current.csrf()))
                .andExpect(status().isTooManyRequests());
        rates.throttled = false;
        rates.unavailable = true;
        mvc.perform(post("/api/me/security/sessions/revoke-others")
                .cookie(current.cookie()).header("X-CSRF-TOKEN", current.csrf()))
                .andExpect(status().isServiceUnavailable());
        rates.unavailable = false;
        assertThat(output.getAll()).doesNotContain(foreignHandle);
    }

    @Test void doubleRevokeSerializesAndDoesNotDuplicateAudit() throws Exception {
        UUID owner = account();
        Browser current = csrf(cookie(session(owner, "ROLE_USER", "password")));
        String target = session(owner, "ROLE_USER", null);
        String handle = handleFor(current, false);
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> revokeConcurrent(current, handle, start));
            var second = pool.submit(() -> revokeConcurrent(current, handle, start));
            start.countDown();
            assertThat(Stream.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS))
                    .filter(code -> code == 204).count()).isEqualTo(1);
        }
        assertThat(sessions.findById(target)).isNull();
        assertThat(jdbc.queryForObject("""
                select count(*) from identity.security_audit_fact
                where actor_user_id = ? and event_category = 'session_revocation'
                  and outcome_code = 'one'
                """, Integer.class, owner)).isEqualTo(1);
    }

    @Test void expiredOrRevokedFrameworkRowsAreAbsentDespiteDescriptorHistory()
            throws Exception {
        UUID owner = account();
        Browser current = csrf(cookie(session(owner, "ROLE_USER", null)));
        String other = session(owner, "ROLE_USER", null);
        handleFor(current, false); // Reconciles a pre-V005 full session.
        jdbc.update("update identity.spring_session set expiry_time = 0 where session_id = ?", other);
        var items = json.readTree(mvc.perform(get("/api/me/security/sessions")
                .cookie(current.cookie())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("sessions");
        assertThat(items.size()).isEqualTo(1);
        assertThat(items.get(0).get("current").asBoolean()).isTrue();
    }

    @Test void loginWaitingOnRevokeAllCanEstablishOnlyAfterRevocationCommit()
            throws Exception {
        UUID owner = account();
        String email = email(owner);
        jdbc.update("update identity.account set password_verifier = ? where user_id = ?",
                passwords.encode("SyntheticPassword-2026!"), owner);
        Browser current = csrf(cookie(session(owner, "ROLE_USER", "password")));
        Browser anonymous = csrf(null);
        CountDownLatch revokeLocked = new CountDownLatch(1);
        CountDownLatch releaseRevoke = new CountDownLatch(1);
        CountDownLatch loginStarted = new CountDownLatch(1);
        race.configure(revokeLocked, releaseRevoke, loginStarted);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var revoke = pool.submit(() -> mvc.perform(post("/api/me/security/sessions/revoke-all")
                    .cookie(current.cookie()).header("X-CSRF-TOKEN", current.csrf()))
                    .andReturn().getResponse().getStatus());
            assertThat(revokeLocked.await(10, TimeUnit.SECONDS)).isTrue();
            var login = pool.submit(() -> mvc.perform(post("/api/auth/login/password")
                    .cookie(anonymous.cookie()).header("X-CSRF-TOKEN", anonymous.csrf())
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"" + email + "\",\"password\":\"SyntheticPassword-2026!\"}"))
                    .andReturn().getResponse());
            try {
                assertThat(loginStarted.await(10, TimeUnit.SECONDS)).isTrue();
            } finally { releaseRevoke.countDown(); }
            assertThat(revoke.get(15, TimeUnit.SECONDS)).isEqualTo(204);
            var loginResponse = login.get(15, TimeUnit.SECONDS);
            assertThat(loginResponse.getStatus()).isEqualTo(200);
            Cookie newCookie = loginResponse.getCookie("SESSION");
            assertThat(newCookie).isNotNull();
            assertThat(sessions.findById(raw(current.cookie()))).isNull();
            assertThat(sessions.findById(raw(newCookie))).isNotNull();
            assertThat(jdbc.queryForObject("""
                    select count(*) from identity.application_session_descriptor d
                    join identity.spring_session s on s.primary_id = d.session_primary_id
                    where s.session_id = ? and d.user_id = ? and d.revoked_at is null
                    """, Integer.class, raw(newCookie), owner)).isEqualTo(1);
        } finally { race.clear(); }
    }

    @Test void descriptorWriteFailureCannotCommitFullLogin() throws Exception {
        UUID owner = account();
        jdbc.update("update identity.account set password_verifier = ? where user_id = ?",
                passwords.encode("SyntheticPassword-2026!"), owner);
        Browser anonymous = csrf(null);
        faultyDescriptors.setFailure(true);
        try {
            mvc.perform(post("/api/auth/login/password")
                    .cookie(anonymous.cookie()).header("X-CSRF-TOKEN", anonymous.csrf())
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"" + email(owner)
                            + "\",\"password\":\"SyntheticPassword-2026!\"}"))
                    .andExpect(status().isInternalServerError());
        } finally { faultyDescriptors.setFailure(false); }
        assertThat(jdbc.queryForObject("""
                select count(*) from identity.application_session_descriptor where user_id = ?
                """, Integer.class, owner)).isZero();
        assertThat(jdbc.queryForObject("""
                select count(*) from identity.spring_session where principal_name = ?
                """, Integer.class, owner.toString())).isZero();
    }

    @Test void logoutTerminalizesExistingDescriptor() throws Exception {
        UUID owner = account();
        String id = session(owner, "ROLE_USER", null);
        Browser browser = csrf(cookie(id));
        handleFor(browser, true);
        mvc.perform(post("/api/auth/logout").cookie(browser.cookie())
                .header("X-CSRF-TOKEN", browser.csrf()))
                .andExpect(status().isNoContent());
        assertThat(sessions.findById(id)).isNull();
        assertThat(jdbc.queryForObject("""
                select count(*) from identity.application_session_descriptor
                where user_id = ? and revoked_at is not null
                """, Integer.class, owner)).isEqualTo(1);
    }

    private int revokeConcurrent(Browser browser, String handle, CountDownLatch start)
            throws Exception {
        assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
        return mvc.perform(delete("/api/me/security/sessions/{handle}", handle)
                .cookie(browser.cookie()).header("X-CSRF-TOKEN", browser.csrf()))
                .andReturn().getResponse().getStatus();
    }

    private UUID account() {
        UUID id = jdbc.queryForObject("select uuidv7()", UUID.class);
        Instant now = clock.instant();
        String email = email(id);
        jdbc.update("""
                insert into identity.account (user_id, canonical_email, display_email,
                    email_verified_at, account_state, created_at, updated_at)
                values (?, ?, ?, ?, 'active', ?, ?)
                """, id, email, email, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        return id;
    }

    private String email(UUID id) {
        return "session-" + id.toString().substring(24) + "@example.test";
    }

    private String session(UUID id, String role, String recentMethod) {
        Session session = repository().createSession();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new IdentitySessionPrincipal(id), null, List.of(new SimpleGrantedAuthority(role))));
        session.setAttribute("SPRING_SECURITY_CONTEXT", context);
        if (recentMethod != null) session.setAttribute(IdentitySessionState.RECENT_ATTRIBUTE,
                new IdentitySessionState.RecentAuthentication(id, clock.instant(), recentMethod));
        repository().save(session);
        return session.getId();
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
        var response = mvc.perform(request)
                .andExpect(status().isOk()).andReturn().getResponse();
        String body = response.getContentAsString();
        return new Browser(response.getCookie("SESSION") == null ? cookie : response.getCookie("SESSION"),
                body.substring(body.indexOf(":\"") + 2, body.lastIndexOf('"')));
    }

    private String handleFor(Browser browser, boolean current) throws Exception {
        var items = json.readTree(mvc.perform(get("/api/me/security/sessions")
                .cookie(browser.cookie())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("sessions");
        for (var item : items) if (item.get("current").asBoolean() == current)
            return item.get("sessionHandle").asText();
        throw new AssertionError("Expected synthetic session not listed");
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

    static final class SessionRaceCheckpoint extends IdentitySessionTransitionCheckpoint {
        volatile CountDownLatch revokeLocked;
        volatile CountDownLatch releaseRevoke;
        volatile CountDownLatch loginStarted;

        void configure(CountDownLatch locked, CountDownLatch release, CountDownLatch login) {
            revokeLocked = locked;
            releaseRevoke = release;
            loginStarted = login;
        }

        void clear() {
            revokeLocked = null;
            releaseRevoke = null;
            loginStarted = null;
        }

        @Override void afterSessionManagementLock(jakarta.servlet.http.HttpServletRequest request) {
            if (revokeLocked == null) return;
            revokeLocked.countDown();
            try {
                if (!releaseRevoke.await(12, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("synthetic_revoke_barrier_timeout");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("synthetic_revoke_interrupted", interrupted);
            }
        }

        @Override void beforePasswordLoginCommit(jakarta.servlet.http.HttpServletRequest request) {
            if (loginStarted != null) loginStarted.countDown();
        }
    }

    static class FaultyDescriptors extends ApplicationSessionDescriptorRepository {
        volatile boolean failEstablishment;

        FaultyDescriptors(JdbcClient jdbc) { super(jdbc); }

        void setFailure(boolean fail) { failEstablishment = fail; }

        @Override void recordFull(String primaryId, UUID userId, String client,
                Instant created, Instant seen, Instant expiry) {
            if (failEstablishment) throw new IllegalStateException("synthetic_descriptor_write_fault");
            super.recordFull(primaryId, userId, client, created, seen, expiry);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Doubles {
        @Bean @Primary SyntheticRates syntheticRates() { return new SyntheticRates(); }
        @Bean @Primary SessionRaceCheckpoint sessionRaceCheckpoint() {
            return new SessionRaceCheckpoint();
        }
        @Bean @Primary FaultyDescriptors faultyDescriptors(JdbcClient jdbc) {
            return new FaultyDescriptors(jdbc);
        }
    }
}
