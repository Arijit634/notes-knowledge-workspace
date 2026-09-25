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
import java.util.concurrent.atomic.AtomicReference;

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
    private static final java.util.concurrent.atomic.AtomicBoolean FAIL_LOGOUT_AUDIT =
            new java.util.concurrent.atomic.AtomicBoolean();
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
    @Autowired FaultyCompletionCheckpoint completionFault;
    @Autowired org.springframework.session.jdbc.JdbcIndexedSessionRepository sessionRepository;
    @Autowired org.springframework.session.web.http.DefaultCookieSerializer cookieSerializer;

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
        assertThat(auditCount(user, "mfa_challenge", "totp_accepted")).isEqualTo(1);
        assertThat((Object) ((org.springframework.session.Session) sessionRepository
                .findById(cookieId(totpFull)))
                .getAttribute(IdentitySessionState.CHALLENGE_ATTRIBUTE)).isNull();
        mvc.perform(post("/api/auth/logout").cookie(totpFull.cookie())
                .header("X-CSRF-TOKEN", pre.csrf()))
                .andExpect(status().isForbidden());
        assertThat(state(totpFull)).isEqualTo("authenticated");
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
        assertThat(auditCount(user, "mfa_challenge", "recovery_accepted")).isEqualTo(1);
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
                        30, 6, 1, 8, 5)).digest(recoveryCode), clock.instant()));
        assertThat(recoveryWins).isEqualTo(1);
    }

    @Test void fiveConcurrentInvalidProofsExhaustOnePersistedChallenge() throws Exception {
        UUID user = account();
        var setup = management.begin(user);
        String enrollmentCode = totp.codeAt(decodeBase32(setup.manualSecret()),
                clock.instant().getEpochSecond() / 30);
        management.confirm(user, setup.enrollmentId(), enrollmentCode);
        Browser pre = login(csrf(null), email(user), 202);
        String path = "/api/auth/mfa/challenges/" + pre.challengeId() + "/totp";
        var results = raceMfa(pre, java.util.Collections.nCopies(5, path),
                java.util.Collections.nCopies(5, "bad"));
        assertThat(results).allSatisfy(result ->
                assertThat(result.getResponse().getStatus()).isEqualTo(401));
        SessionChallenge current = challenge(pre);
        assertThat(current.existingSession()).isTrue();
        assertThat(current.challenge()).isNull();
        assertThat(auditCount(user, "mfa_challenge", "denied")).isEqualTo(5);
        assertThat(auditCount(user, "mfa_challenge", "totp_accepted")).isZero();
        assertThat(auditCount(user, "mfa_challenge", "recovery_accepted")).isZero();
        assertThat(fullSessionCount(user)).isZero();
        assertThat(state(pre)).isEqualTo("mfaRequired");
        mvc.perform(post(path).cookie(pre.cookie()).header("X-CSRF-TOKEN", pre.csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"bad\"}"))
                .andExpect(status().isNotFound());
    }

    @Test void totpAndRecoveryRacingOnOneChallengeHaveOneWinner() throws Exception {
        UUID user = account();
        var setup = management.begin(user);
        String enrollmentCode = totp.codeAt(decodeBase32(setup.manualSecret()),
                clock.instant().getEpochSecond() / 30);
        String recoveryCode = management.confirm(user, setup.enrollmentId(), enrollmentCode).get(0);
        clock.advanceSeconds(30);
        Browser pre = login(csrf(null), email(user), 202);
        String totpCode = totp.codeAt(decodeBase32(setup.manualSecret()),
                clock.instant().getEpochSecond() / 30);
        Long beforeStep = jdbc.queryForObject("select last_accepted_timestep from identity.mfa_configuration where user_id = ?",
                Long.class, user);
        String base = "/api/auth/mfa/challenges/" + pre.challengeId();
        var results = raceMfa(pre, java.util.List.of(base + "/totp", base + "/recovery-code"),
                java.util.List.of(totpCode, recoveryCode));
        assertOneAccepted(results);
        int totpAccepted = auditCount(user, "mfa_challenge", "totp_accepted");
        int recoveryAccepted = auditCount(user, "mfa_challenge", "recovery_accepted");
        assertThat(totpAccepted + recoveryAccepted).isEqualTo(1);
        assertThat(results.get(0).getResponse().getStatus() == 200).isEqualTo(totpAccepted == 1);
        assertThat(results.get(1).getResponse().getStatus() == 200).isEqualTo(recoveryAccepted == 1);
        Long afterStep = jdbc.queryForObject("select last_accepted_timestep from identity.mfa_configuration where user_id = ?",
                Long.class, user);
        int consumed = jdbc.queryForObject("select count(*) from identity.mfa_recovery_code where user_id = ? and consumed_at is not null",
                Integer.class, user);
        if (totpAccepted == 1) {
            assertThat(afterStep).isGreaterThan(beforeStep);
            assertThat(consumed).isZero();
        } else {
            assertThat(afterStep).isEqualTo(beforeStep);
            assertThat(consumed).isEqualTo(1);
        }
        Cookie winningCookie = results.stream().filter(r -> r.getResponse().getStatus() == 200)
                .findFirst().orElseThrow().getResponse().getCookie("SESSION");
        assertThat(winningCookie).isNotNull();
        assertThat(sessionRepository.findById(cookieId(new Browser(winningCookie, "", null))))
                .isNotNull();
        assertThat(fullSessionCount(user)).isEqualTo(1);
        assertThat(state(pre)).isEqualTo("anonymous");
        assertThat(sessionRepository.findById(cookieId(pre))).isNull();
    }

    @Test void sameFactorHttpRacesCannotElevateOneChallengeTwice() throws Exception {
        UUID user = account();
        var setup = management.begin(user);
        String enrollmentCode = totp.codeAt(decodeBase32(setup.manualSecret()),
                clock.instant().getEpochSecond() / 30);
        String recoveryCode = management.confirm(user, setup.enrollmentId(), enrollmentCode).get(0);
        clock.advanceSeconds(30);
        Browser totpPre = login(csrf(null), email(user), 202);
        String totpCode = totp.codeAt(decodeBase32(setup.manualSecret()),
                clock.instant().getEpochSecond() / 30);
        String totpPath = "/api/auth/mfa/challenges/" + totpPre.challengeId() + "/totp";
        assertOneAccepted(raceMfa(totpPre, java.util.List.of(totpPath, totpPath),
                java.util.List.of(totpCode, totpCode)));
        assertThat(auditCount(user, "mfa_challenge", "totp_accepted")).isEqualTo(1);
        Browser recoveryPre = login(csrf(null), email(user), 202);
        String recoveryPath = "/api/auth/mfa/challenges/" + recoveryPre.challengeId() + "/recovery-code";
        assertOneAccepted(raceMfa(recoveryPre,
                java.util.List.of(recoveryPath, recoveryPath),
                java.util.List.of(recoveryCode, recoveryCode)));
        assertThat(auditCount(user, "mfa_challenge", "recovery_accepted")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from identity.mfa_recovery_code where user_id = ? and consumed_at is not null",
                Integer.class, user)).isEqualTo(1);
    }

    private void assertOneAccepted(java.util.List<MvcResult> results) {
        assertThat(results.stream().filter(result -> result.getResponse().getStatus() == 200).count())
                .isEqualTo(1);
        assertThat(results.stream().filter(result -> result.getResponse().getStatus() != 200))
                .allSatisfy(result -> assertThat(result.getResponse().getStatus())
                        .isIn(401, 404));
    }

    private java.util.List<MvcResult> raceMfa(Browser pre, java.util.List<String> paths,
            java.util.List<String> proofs) throws Exception {
        completionFault.challengeBarrier.set(new CountDownLatch(paths.size()));
        try (var pool = Executors.newFixedThreadPool(paths.size())) {
            var tasks = java.util.stream.IntStream.range(0, paths.size()).mapToObj(index ->
                    pool.submit(() -> mvc.perform(post(paths.get(index))
                            .cookie(pre.cookie()).header("X-CSRF-TOKEN", pre.csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"" + proofs.get(index) + "\"}"))
                            .andReturn())).toList();
            var results = new java.util.ArrayList<MvcResult>();
            for (var task : tasks) results.add(task.get(30, java.util.concurrent.TimeUnit.SECONDS));
            return results;
        } finally {
            completionFault.challengeBarrier.set(null);
        }
    }

    private SessionChallenge challenge(Browser browser) {
        var persisted = (org.springframework.session.Session) sessionRepository.findById(cookieId(browser));
        return persisted == null ? new SessionChallenge(false, null)
                : new SessionChallenge(true, persisted.getAttribute(IdentitySessionState.CHALLENGE_ATTRIBUTE));
    }

    record SessionChallenge(boolean existingSession, Object challenge) { }

    @Test void totpSessionFailureRollsBackProofAuditAndFullAuthority() throws Exception {
        UUID user = account();
        var setup = management.begin(user);
        String enrollmentCode = totp.codeAt(decodeBase32(setup.manualSecret()),
                clock.instant().getEpochSecond() / 30);
        management.confirm(user, setup.enrollmentId(), enrollmentCode);
        clock.advanceSeconds(30);
        Browser pre = login(csrf(null), email(user), 202);
        String proof = totp.codeAt(decodeBase32(setup.manualSecret()),
                clock.instant().getEpochSecond() / 30);
        Long before = jdbc.queryForObject("select last_accepted_timestep from identity.mfa_configuration where user_id = ?",
                Long.class, user);
        completionFault.fail = true;
        try {
            mvc.perform(post("/api/auth/mfa/challenges/" + pre.challengeId() + "/totp")
                    .cookie(pre.cookie()).header("X-CSRF-TOKEN", pre.csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"code\":\"" + proof + "\"}"))
                    .andExpect(status().is5xxServerError());
        } finally { completionFault.fail = false; }
        assertThat(completionFault.observedInTransaction).isTrue();
        assertThat(jdbc.queryForObject("select last_accepted_timestep from identity.mfa_configuration where user_id = ?",
                Long.class, user)).isEqualTo(before);
        assertThat(auditCount(user, "mfa_challenge", "totp_accepted")).isZero();
        assertThat(fullSessionCount(user)).isZero();
        assertThat(state(pre)).isNotEqualTo("authenticated");
    }

    @Test void primaryLoginSessionFailureRollsBackVerifierAuditAndAuthority() throws Exception {
        UUID fullUser = account();
        Browser anonymous = csrf(null);
        completionFault.fail = true;
        try {
            mvc.perform(post("/api/auth/login/password")
                    .cookie(anonymous.cookie()).header("X-CSRF-TOKEN", anonymous.csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"" + email(fullUser) + "\",\"password\":\"" + PASSWORD + "\"}"))
                    .andExpect(status().is5xxServerError());
        } finally { completionFault.fail = false; }
        assertThat(completionFault.observedInTransaction).isTrue();
        assertThat(jdbc.queryForObject("select last_authenticated_at from identity.account where user_id = ?",
                java.sql.Timestamp.class, fullUser)).isNull();
        assertThat(auditCount(fullUser, "password_login", "success")).isZero();
        assertThat(fullSessionCount(fullUser)).isZero();
        assertThat(state(anonymous)).isEqualTo("anonymous");

        UUID preUser = account();
        var setup = management.begin(preUser);
        String proof = totp.codeAt(decodeBase32(setup.manualSecret()),
                clock.instant().getEpochSecond() / 30);
        management.confirm(preUser, setup.enrollmentId(), proof);
        Browser secondAnonymous = csrf(null);
        completionFault.observedInTransaction = false;
        completionFault.fail = true;
        try {
            mvc.perform(post("/api/auth/login/password")
                    .cookie(secondAnonymous.cookie()).header("X-CSRF-TOKEN", secondAnonymous.csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"" + email(preUser) + "\",\"password\":\"" + PASSWORD + "\"}"))
                    .andExpect(status().is5xxServerError());
        } finally { completionFault.fail = false; }
        assertThat(completionFault.observedInTransaction).isTrue();
        assertThat(jdbc.queryForObject("select last_authenticated_at from identity.account where user_id = ?",
                java.sql.Timestamp.class, preUser)).isNull();
        assertThat(auditCount(preUser, "password_login", "success")).isZero();
        assertThat(state(secondAnonymous)).isEqualTo("anonymous");
    }

    @Test void recoverySessionFailureRollsBackConsumptionAuditAndFullAuthority() throws Exception {
        UUID user = account();
        var setup = management.begin(user);
        String enrollmentCode = totp.codeAt(decodeBase32(setup.manualSecret()),
                clock.instant().getEpochSecond() / 30);
        String code = management.confirm(user, setup.enrollmentId(), enrollmentCode).get(0);
        Browser pre = login(csrf(null), email(user), 202);
        completionFault.fail = true;
        try {
            mvc.perform(post("/api/auth/mfa/challenges/" + pre.challengeId() + "/recovery-code")
                    .cookie(pre.cookie()).header("X-CSRF-TOKEN", pre.csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"code\":\"" + code + "\"}"))
                    .andExpect(status().is5xxServerError());
        } finally { completionFault.fail = false; }
        assertThat(completionFault.observedInTransaction).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from identity.mfa_recovery_code where user_id = ? and consumed_at is not null",
                Integer.class, user)).isZero();
        assertThat(auditCount(user, "mfa_challenge", "recovery_accepted")).isZero();
        assertThat(fullSessionCount(user)).isZero();
        assertThat(state(pre)).isNotEqualTo("authenticated");
    }

    @Test void invalidProofBudgetIsSessionOnlyAndRestartedByPrimaryLogin() throws Exception {
        UUID user = account();
        var setup = management.begin(user);
        String enrollmentCode = totp.codeAt(decodeBase32(setup.manualSecret()),
                clock.instant().getEpochSecond() / 30);
        management.confirm(user, setup.enrollmentId(), enrollmentCode);
        Browser pre = login(csrf(null), email(user), 202);
        for (int attempt = 1; attempt <= 5; attempt++) {
            MvcResult denied = mvc.perform(post("/api/auth/mfa/challenges/"
                    + pre.challengeId() + "/totp")
                    .cookie(pre.cookie()).header("X-CSRF-TOKEN", pre.csrf())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"bad\"}"))
                    .andExpect(status().isUnauthorized()).andReturn();
            assertThat(denied.getResponse().getContentAsString())
                    .doesNotContain("failedAttempts", "maxChallengeFailures");
            var challenge = ((org.springframework.session.Session) sessionRepository
                    .findById(cookieId(pre)))
                    .getAttribute(IdentitySessionState.CHALLENGE_ATTRIBUTE);
            if (attempt < 5) {
                assertThat(((IdentitySessionState.Challenge) challenge).failedAttempts())
                        .isEqualTo(attempt);
            } else {
                assertThat(challenge).isNull();
            }
        }
        mvc.perform(post("/api/auth/mfa/challenges/" + pre.challengeId() + "/totp")
                .cookie(pre.cookie()).header("X-CSRF-TOKEN", pre.csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"bad\"}"))
                .andExpect(status().isNotFound());
        Browser fresh = login(csrf(null), email(user), 202);
        assertThat(((IdentitySessionState.Challenge) ((org.springframework.session.Session)
                sessionRepository.findById(cookieId(fresh)))
                .getAttribute(IdentitySessionState.CHALLENGE_ATTRIBUTE)).failedAttempts()).isZero();
    }

    @Test void logoutAuditIsAttributableAndContainsOnlySafeFacts() throws Exception {
        UUID fullUser = account();
        Browser full = login(csrf(null), email(fullUser), 200);
        UUID preUser = account();
        var setup = management.begin(preUser);
        String proof = totp.codeAt(decodeBase32(setup.manualSecret()),
                clock.instant().getEpochSecond() / 30);
        management.confirm(preUser, setup.enrollmentId(), proof);
        Browser pre = login(csrf(null), email(preUser), 202);
        Browser anon = csrf(null);
        mvc.perform(post("/api/auth/logout").cookie(full.cookie())
                .header("X-CSRF-TOKEN", full.csrf())).andExpect(status().isNoContent());
        mvc.perform(post("/api/auth/logout").cookie(pre.cookie())
                .header("X-CSRF-TOKEN", pre.csrf())).andExpect(status().isNoContent());
        mvc.perform(post("/api/auth/logout").cookie(anon.cookie())
                .header("X-CSRF-TOKEN", anon.csrf())).andExpect(status().isNoContent());
        assertThat(auditCount(fullUser, "logout", "success")).isEqualTo(1);
        assertThat(auditCount(preUser, "logout", "success")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from identity.security_audit_fact where event_category = 'logout' and actor_user_id = target_user_id and actor_user_id in (?, ?)",
                Integer.class, fullUser, preUser)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from identity.security_audit_fact where event_category = 'logout' and target_user_id is null",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from identity.security_audit_fact where event_category = 'logout' and (reason_code is not null or correlation_id is not null)",
                Integer.class)).isZero();
        assertThat(state(full)).isEqualTo("anonymous");
        assertThat(state(pre)).isEqualTo("anonymous");
    }

    @Test void logoutAuditFailureCannotPreserveSessionAuthority() throws Exception {
        UUID user = account();
        Browser full = login(csrf(null), email(user), 200);
        FAIL_LOGOUT_AUDIT.set(true);
        try {
            mvc.perform(post("/api/auth/logout").cookie(full.cookie())
                    .header("X-CSRF-TOKEN", full.csrf()))
                    .andExpect(status().isNoContent());
        } finally { FAIL_LOGOUT_AUDIT.set(false); }
        assertThat(state(full)).isEqualTo("anonymous");
        assertThat(auditCount(user, "logout", "success")).isZero();
    }

    private int auditCount(UUID user, String category, String outcome) {
        return jdbc.queryForObject("select count(*) from identity.security_audit_fact where target_user_id = ? and event_category = ? and outcome_code = ?",
                Integer.class, user, category, outcome);
    }

    private String cookieId(Browser browser) {
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        request.setCookies(browser.cookie());
        return cookieSerializer.readCookieValues(request).get(0);
    }

    private int fullSessionCount(UUID user) {
        return (int) jdbc.query("select session_id from identity.spring_session",
                (rs, row) -> rs.getString(1)).stream().filter(id -> {
                    var session = (org.springframework.session.Session) sessionRepository.findById(id);
                    if (session == null) return false;
                    var context = (org.springframework.security.core.context.SecurityContext)
                            session.getAttribute("SPRING_SECURITY_CONTEXT");
                    return context != null && context.getAuthentication() != null
                            && context.getAuthentication().getPrincipal()
                                instanceof IdentitySessionPrincipal principal
                            && user.equals(principal.userId())
                            && context.getAuthentication().getAuthorities().stream()
                            .anyMatch(a -> "ROLE_USER".equals(a.getAuthority()));
                }).count();
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
        assertThat(((IdentitySessionState.Challenge) ((org.springframework.session.Session)
                sessionRepository.findById(cookieId(pre)))
                .getAttribute(IdentitySessionState.CHALLENGE_ATTRIBUTE)).failedAttempts()).isZero();
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
        assertThat((Object) ((org.springframework.session.Session) sessionRepository
                .findById(cookieId(pre))).getAttribute(IdentitySessionState.CHALLENGE_ATTRIBUTE))
                .isNull();
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
        assertThat(sessionRepository.findById(cookieId(pre))).isNull();
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

    static final class FaultyCompletionCheckpoint extends IdentitySessionTransitionCheckpoint {
        @Autowired JdbcTemplate jdbc;
        volatile boolean fail;
        volatile boolean observedInTransaction;
        final AtomicReference<CountDownLatch> challengeBarrier = new AtomicReference<>();

        @Override void beforeChallengeLock(jakarta.servlet.http.HttpServletRequest request) {
            CountDownLatch barrier = challengeBarrier.get();
            if (barrier == null) return;
            barrier.countDown();
            try {
                if (!barrier.await(20, java.util.concurrent.TimeUnit.SECONDS)) {
                    throw new IllegalStateException("synthetic_mfa_race_barrier_timeout");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("synthetic_mfa_race_interrupted", interrupted);
            }
        }

        @Override void afterSessionMutation(jakarta.servlet.http.HttpServletRequest request) {
            if (!fail) return;
            var principal = (IdentitySessionPrincipal) org.springframework.security.core.context
                    .SecurityContextHolder.getContext().getAuthentication().getPrincipal();
            observedInTransaction = org.springframework.transaction.support.TransactionSynchronizationManager
                    .isActualTransactionActive()
                    && jdbc.queryForObject("select count(*) from identity.spring_session where session_id = ?",
                            Integer.class, request.getSession(false).getId()) == 1
                    && jdbc.queryForObject("""
                            select count(*) from identity.spring_session_attributes a
                            join identity.spring_session s on s.primary_id = a.session_primary_id
                            where s.session_id = ? and a.attribute_name = 'SPRING_SECURITY_CONTEXT'
                            """, Integer.class, request.getSession(false).getId()) == 1
                    && jdbc.queryForObject("""
                            select count(*) from identity.security_audit_fact
                            where target_user_id = ? and
                              ((event_category = 'mfa_challenge' and outcome_code in
                                  ('totp_accepted', 'recovery_accepted'))
                               or (event_category = 'password_login' and outcome_code = 'success'))
                            """, Integer.class, principal.userId()) >= 1;
            throw new IllegalStateException("synthetic_mfa_completion_failure");
        }
    }

    static class FaultyLogoutAudit extends IdentityPersistence {
        FaultyLogoutAudit(org.springframework.jdbc.core.simple.JdbcClient jdbc) { super(jdbc); }
        @Override void auditLogout(UUID userId, Instant now) {
            if (FAIL_LOGOUT_AUDIT.get()) throw new IllegalStateException("synthetic_audit_unavailable");
            super.auditLogout(userId, now);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Doubles {
        @Bean @Primary MutableClock syntheticClock() { return new MutableClock(); }
        @Bean @Primary SyntheticRates syntheticRates() { return new SyntheticRates(); }
        @Bean @Primary FaultyCompletionCheckpoint faultyCompletionCheckpoint() {
            return new FaultyCompletionCheckpoint();
        }
        @Bean @Primary FaultyLogoutAudit faultyLogoutAudit(
                org.springframework.jdbc.core.simple.JdbcClient jdbc) {
            return new FaultyLogoutAudit(jdbc);
        }
    }
}
