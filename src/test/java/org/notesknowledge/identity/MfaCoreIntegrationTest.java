package org.notesknowledge.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@Tag("DATABASE") @Tag("API") @Tag("SECURITY")
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@Import(MfaCoreIntegrationTest.Doubles.class)
@ExtendWith(OutputCaptureExtension.class)
class MfaCoreIntegrationTest {
    private static final String PASSWORD = "SyntheticMfaPassword-2026!";
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            "pgvector/pgvector:0.8.6-pg18-trixie")
            .withDatabaseName("mfa_core").withUsername("mfa_migrator")
            .withPassword("synthetic-mfa-migrator-password");

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
    @Autowired PasswordEncoder passwords;
    @Autowired TotpEngine totp;
    @Autowired MutableClock clock;
    @Autowired MfaRepository repository;
    @Autowired MfaManagementService management;
    @Autowired MfaChallengeService challenges;
    @Autowired SyntheticRates rates;

    @Test void passwordLoginEnrollmentTotpAndRecoveryAreSessionAndReplaySafe(
            CapturedOutput output) throws Exception {
        UUID user = account();
        String email = email(user);
        Browser anonymous = csrf(null);
        Browser full = login(anonymous, email, 200);
        assertThat(state(full)).isEqualTo("authenticated");
        mvc.perform(post("/api/me/security/mfa/totp/enrollments")
                .cookie(full.cookie()).header("X-CSRF-TOKEN", full.csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/reauth/password").cookie(full.cookie())
                .header("X-CSRF-TOKEN", full.csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/reauth/password").cookie(full.cookie())
                .header("X-CSRF-TOKEN", full.csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isNoContent());
        MvcResult begin = mvc.perform(post("/api/me/security/mfa/totp/enrollments")
                .cookie(full.cookie()).header("X-CSRF-TOKEN", full.csrf()))
                .andExpect(status().isCreated()).andReturn();
        String handle = json(begin, "enrollmentId");
        String manual = json(begin, "manualSecret");
        assertThat(handle).hasSize(43).doesNotContain(user.toString());
        assertThat(begin.getResponse().getContentAsString()).doesNotContain(user.toString(),
                "keyVersion", "recoveryCodes");
        assertThat(jdbc.queryForObject("""
                select count(*) from identity.mfa_configuration
                where user_id = ? and state = 'enrollment_pending'
                  and octet_length(seed_ciphertext) = 20
                """, Integer.class, user)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select encode(seed_ciphertext, 'hex') from identity.mfa_configuration
                where user_id = ?
                """, String.class, user)).doesNotContain(manual);
        String enrollmentCode = totp.codeAt(decodeBase32(manual),
                clock.instant().getEpochSecond() / 30);
        mvc.perform(post("/api/me/security/mfa/totp/enrollments/wrong/confirmation")
                .cookie(full.cookie()).header("X-CSRF-TOKEN", full.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + enrollmentCode + "\"}"))
                .andExpect(status().isNotFound());
        MvcResult activation = mvc.perform(post("/api/me/security/mfa/totp/enrollments/"
                + handle + "/confirmation")
                .cookie(full.cookie()).header("X-CSRF-TOKEN", full.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + enrollmentCode + "\"}"))
                .andExpect(status().isOk()).andReturn();
        String firstRecovery = mapper.readTree(activation.getResponse().getContentAsString())
                .get("recoveryCodes").get(0).asText();
        assertThat(firstRecovery).hasSize(43);
        assertThat(jdbc.queryForObject("""
                select count(*) from identity.mfa_recovery_code where user_id = ?
                """, Integer.class, user)).isEqualTo(8);
        assertThat(jdbc.queryForObject("""
                select encode(verifier_digest, 'hex') from identity.mfa_recovery_code
                where user_id = ? limit 1
                """, String.class, user)).doesNotContain(firstRecovery);
        mvc.perform(post("/api/me/security/mfa/totp/enrollments/" + handle + "/confirmation")
                .cookie(full.cookie()).header("X-CSRF-TOKEN", full.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + enrollmentCode + "\"}"))
                .andExpect(status().isForbidden());
        Browser rotatedFull = csrf(activation.getResponse().getCookie("SESSION"));
        assertThat(state(rotatedFull)).isEqualTo("authenticated");

        Browser pre = login(csrf(null), email, 202);
        assertThat(state(pre)).isEqualTo("mfaRequired");
        mvc.perform(get("/api/me/security").cookie(pre.cookie()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/reauth/password").cookie(pre.cookie())
                .header("X-CSRF-TOKEN", pre.csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isForbidden());
        String challenge = pre.challengeId();
        MvcResult wrongChallenge = mvc.perform(post("/api/auth/mfa/challenges/wrong/totp")
                .cookie(pre.cookie()).header("X-CSRF-TOKEN", pre.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"000000\"}"))
                .andExpect(status().isNotFound()).andReturn();
        assertThat(wrongChallenge.getResponse().getContentAsString())
                .doesNotContain(challenge, "/wrong/");
        pre = login(csrf(null), email, 202);
        challenge = pre.challengeId();
        clock.advanceSeconds(30);
        String secondCode = totp.codeAt(decodeBase32(manual),
                clock.instant().getEpochSecond() / 30);
        MvcResult elevated = mvc.perform(post("/api/auth/mfa/challenges/" + challenge + "/totp")
                .cookie(pre.cookie()).header("X-CSRF-TOKEN", pre.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + secondCode + "\"}"))
                .andExpect(status().isOk()).andReturn();
        Browser totpFull = csrf(elevated.getResponse().getCookie("SESSION"));
        assertThat(state(totpFull)).isEqualTo("authenticated");
        assertThat(state(pre)).isEqualTo("anonymous");
        Browser replay = login(csrf(null), email, 202);
        mvc.perform(post("/api/auth/mfa/challenges/" + replay.challengeId() + "/totp")
                .cookie(replay.cookie()).header("X-CSRF-TOKEN", replay.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + secondCode + "\"}"))
                .andExpect(status().isUnauthorized());
        Browser recoveryPre = login(csrf(null), email, 202);
        MvcResult recovered = mvc.perform(post("/api/auth/mfa/challenges/"
                + recoveryPre.challengeId() + "/recovery-code")
                .cookie(recoveryPre.cookie()).header("X-CSRF-TOKEN", recoveryPre.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + firstRecovery + "\"}"))
                .andExpect(status().isOk()).andReturn();
        Browser recoveredFull = csrf(recovered.getResponse().getCookie("SESSION"));
        assertThat(state(recoveredFull)).isEqualTo("authenticated");
        Browser reused = login(csrf(null), email, 202);
        mvc.perform(post("/api/auth/mfa/challenges/" + reused.challengeId() + "/recovery-code")
                .cookie(reused.cookie()).header("X-CSRF-TOKEN", reused.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + firstRecovery + "\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/logout").cookie(recoveredFull.cookie())
                .header("X-CSRF-TOKEN", recoveredFull.csrf()))
                .andExpect(status().isNoContent());
        assertThat(state(recoveredFull)).isEqualTo("anonymous");
        assertThat(output.getAll()).doesNotContain(manual, enrollmentCode,
                secondCode, firstRecovery, challenge);
    }

    @Test void concurrentTotpAndRecoveryConsumptionEachHaveOneWinner() throws Exception {
        UUID user = account();
        var setup = management.begin(user);
        String enrollmentCode = totp.codeAt(decodeBase32(setup.manualSecret()),
                clock.instant().getEpochSecond() / 30);
        String recoveryCode = management.confirm(user, setup.enrollmentId(), enrollmentCode).get(0);
        clock.advanceSeconds(30);
        String next = totp.codeAt(decodeBase32(setup.manualSecret()),
                clock.instant().getEpochSecond() / 30);
        var config = repository.configuration(user).orElseThrow();
        int totpWins = concurrent(() -> {
            long step = totp.matchingStep(decodeBase32(setup.manualSecret()), next,
                    clock.instant(), config.lastAcceptedStep());
            return repository.advanceStep(user, config.activatedAt(), step) == 1;
        });
        assertThat(totpWins).isEqualTo(1);
        int recoveryWins = concurrent(() -> repository.consumeRecovery(user,
                new RecoveryCodeService(new MfaProperties(java.time.Duration.ofMinutes(5),
                        java.time.Duration.ofMinutes(10), java.time.Duration.ofMinutes(5),
                        30, 6, 1, 8)).digest(recoveryCode), clock.instant()));
        assertThat(recoveryWins).isEqualTo(1);
    }

    @Test void rateControlUnavailableFailsClosedBeforeMfaProof() throws Exception {
        UUID user = account();
        var setup = management.begin(user);
        String code = totp.codeAt(decodeBase32(setup.manualSecret()),
                clock.instant().getEpochSecond() / 30);
        management.confirm(user, setup.enrollmentId(), code);
        Browser pre = login(csrf(null), email(user), 202);
        rates.unavailable = true;
        try {
            mvc.perform(post("/api/auth/mfa/challenges/" + pre.challengeId() + "/totp")
                    .cookie(pre.cookie()).header("X-CSRF-TOKEN", pre.csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"code\":\"123456\"}"))
                    .andExpect(status().isServiceUnavailable());
        } finally { rates.unavailable = false; }
    }

    @Test void pendingReplacementExpiresOldHandleAndChallengeExpiresOrLosesEligibility()
            throws Exception {
        UUID user = account();
        var old = management.begin(user);
        var replacement = management.begin(user);
        assertThat(old.enrollmentId()).isNotEqualTo(replacement.enrollmentId());
        String code = totp.codeAt(decodeBase32(replacement.manualSecret()),
                clock.instant().getEpochSecond() / 30);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                management.confirm(user, old.enrollmentId(), code))
                .isInstanceOf(org.notesknowledge.websupport.ApiFailureException.class);
        management.confirm(user, replacement.enrollmentId(), code);
        Browser pre = login(csrf(null), email(user), 202);
        clock.advanceSeconds(301);
        mvc.perform(post("/api/auth/mfa/challenges/" + pre.challengeId() + "/totp")
                .cookie(pre.cookie()).header("X-CSRF-TOKEN", pre.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"123456\"}"))
                .andExpect(status().isNotFound());
        pre = login(csrf(null), email(user), 202);
        jdbc.update("update identity.account set account_state = 'suspended' where user_id = ?", user);
        mvc.perform(post("/api/auth/mfa/challenges/" + pre.challengeId() + "/totp")
                .cookie(pre.cookie()).header("X-CSRF-TOKEN", pre.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"123456\"}"))
                .andExpect(status().isForbidden());
    }

    @Test void preMfaCannotUseAnonymousCommandsAndLogoutWorksInAllStages() throws Exception {
        UUID user = account();
        var setup = management.begin(user);
        String proof = totp.codeAt(decodeBase32(setup.manualSecret()),
                clock.instant().getEpochSecond() / 30);
        management.confirm(user, setup.enrollmentId(), proof);
        Browser pre = login(csrf(null), email(user), 202);
        mvc.perform(post("/api/auth/registrations").cookie(pre.cookie())
                .header("X-CSRF-TOKEN", pre.csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"blocked@example.test\",\"password\":\"abc\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/notes").cookie(pre.cookie()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/logout").cookie(pre.cookie()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/logout").cookie(pre.cookie())
                .header("X-CSRF-TOKEN", pre.csrf()))
                .andExpect(status().isNoContent());
        assertThat(state(pre)).isEqualTo("anonymous");
        Browser anonymous = csrf(null);
        mvc.perform(post("/api/auth/logout").cookie(anonymous.cookie())
                .header("X-CSRF-TOKEN", anonymous.csrf()))
                .andExpect(status().isNoContent());
        assertThat(state(anonymous)).isEqualTo("anonymous");
    }

    @Test void wrongSessionCannotUseChallengeAndOldRecoveryGenerationFails() throws Exception {
        UUID user = account();
        var setup = management.begin(user);
        String proof = totp.codeAt(decodeBase32(setup.manualSecret()),
                clock.instant().getEpochSecond() / 30);
        String code = management.confirm(user, setup.enrollmentId(), proof).get(0);
        Browser first = login(csrf(null), email(user), 202);
        Browser second = login(csrf(null), email(user), 202);
        mvc.perform(post("/api/auth/mfa/challenges/" + first.challengeId() + "/recovery-code")
                .cookie(second.cookie()).header("X-CSRF-TOKEN", second.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isNotFound());
        jdbc.update("""
                update identity.mfa_configuration
                set current_recovery_generation = 2 where user_id = ?
                """, user);
        Browser third = login(csrf(null), email(user), 202);
        mvc.perform(post("/api/auth/mfa/challenges/" + third.challengeId() + "/recovery-code")
                .cookie(third.cookie()).header("X-CSRF-TOKEN", third.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    private int concurrent(java.util.concurrent.Callable<Boolean> operation) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger wins = new AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var tasks = java.util.stream.IntStream.range(0, 2).mapToObj(i ->
                    pool.submit(() -> {
                        ready.countDown();
                        start.await();
                        if (operation.call()) wins.incrementAndGet();
                        return null;
                    })).toList();
            assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (var task : tasks) task.get();
        }
        return wins.get();
    }

    private UUID account() {
        UUID id = jdbc.queryForObject("select uuidv7()", UUID.class);
        jdbc.update("""
                insert into identity.account
                    (user_id, canonical_email, display_email, email_verified_at,
                     password_verifier, account_state, created_at, updated_at)
                values (?, ?, ?, ?, ?, 'active', ?, ?)
                """, id, email(id), email(id), java.sql.Timestamp.from(clock.instant()),
                passwords.encode(PASSWORD), java.sql.Timestamp.from(clock.instant()),
                java.sql.Timestamp.from(clock.instant()));
        return id;
    }

    private String email(UUID id) { return "mfa-" + id + "@example.test"; }

    private Browser csrf(Cookie cookie) throws Exception {
        var request = get("/api/auth/csrf");
        if (cookie != null) request.cookie(cookie);
        MvcResult result = mvc.perform(request).andExpect(status().isOk()).andReturn();
        Cookie current = result.getResponse().getCookie("SESSION");
        return new Browser(current == null ? cookie : current,
                json(result, "csrfToken"), null);
    }

    private Browser login(Browser browser, String email, int expected) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login/password")
                .cookie(browser.cookie()).header("X-CSRF-TOKEN", browser.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().is(expected)).andReturn();
        Browser refreshed = csrf(result.getResponse().getCookie("SESSION"));
        return new Browser(refreshed.cookie(), refreshed.csrf(),
                expected == 202 ? json(result, "challengeId") : null);
    }

    private String state(Browser browser) throws Exception {
        return json(mvc.perform(get("/api/auth/session").cookie(browser.cookie()))
                .andExpect(status().isOk()).andReturn(), "state");
    }

    private String json(MvcResult result, String field) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString()).get(field).asText();
    }

    private byte[] decodeBase32(String encoded) {
        byte[] bytes = new byte[20];
        int value = 0, bits = 0, offset = 0;
        for (char c : encoded.toCharArray()) {
            value = (value << 5) | "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".indexOf(c);
            bits += 5;
            if (bits >= 8) {
                bytes[offset++] = (byte) (value >>> (bits - 8));
                bits -= 8;
            }
        }
        return bytes;
    }

    record Browser(Cookie cookie, String csrf, String challengeId) { }

    static final class MutableClock extends Clock {
        private volatile Instant at = Instant.parse("2026-09-24T16:20:00Z");
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

    @TestConfiguration(proxyBeanMethods = false)
    static class Doubles {
        @Bean @Primary MutableClock syntheticClock() { return new MutableClock(); }
        @Bean @Primary SyntheticRates syntheticRates() { return new SyntheticRates(); }
    }
}
