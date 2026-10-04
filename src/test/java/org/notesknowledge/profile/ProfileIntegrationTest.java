package org.notesknowledge.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.ArgumentMatchers.any;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.notesknowledge.identity.IdentitySessionPrincipal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@Tag("DATABASE") @Tag("API") @Tag("SECURITY")
@Testcontainers @SpringBootTest @AutoConfigureMockMvc @ExtendWith(OutputCaptureExtension.class)
class ProfileIntegrationTest {
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18-trixie")
            .withDatabaseName("private_profile").withUsername("profile_migrator")
            .withPassword("synthetic-profile-migrator-password");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("notes.cursor.active.version", () -> "test1");
        registry.add("notes.cursor.active.key-base64", () -> "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=");
        registry.add("identity.rate.key-base64", () -> "AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=");
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired SessionRepository<? extends Session> sessions;
    @MockitoBean org.notesknowledge.security.RateLimitPort rates;
    @MockitoSpyBean JpaProfileRepositoryAdapter repository;
    @MockitoSpyBean ProfileService profiles;

    @BeforeEach void resetRates() {
        org.mockito.Mockito.when(rates.evaluate(any())).thenReturn(new org.notesknowledge.security.RateLimitPort.Allowed());
    }

    @Test void absentReadIsDeterministicPrivateNoStoreAndDoesNotPersistOrInventData() throws Exception {
        UUID user = account(); Browser browser = browser(user, "ROLE_USER");
        MvcResult first = mvc.perform(get("/api/me/profile").cookie(browser.cookie())).andExpect(status().isOk()).andReturn();
        MvcResult next = mvc.perform(get("/api/me/profile").cookie(browser.cookie())).andExpect(status().isOk()).andReturn();
        assertThat(body(first)).isEqualTo(body(next));
        assertThat(json.readTree(body(first)).properties()).extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder("displayName", "biography", "handle", "updatedAt", "avatar");
        assertThat(json.readTree(body(first)).get("displayName").asText()).isEmpty();
        assertThat(json.readTree(body(first)).get("handle").isNull()).isTrue();
        assertThat(json.readTree(body(first)).get("updatedAt").isNull()).isTrue();
        assertThat(first.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(jdbc.queryForObject("select count(*) from profile.profile where user_id=?", Integer.class, user)).isZero();
    }

    @Test void replacementIsAllowlistedOwnerScopedAndKeepsImmutableRootWhileClearingHandle() throws Exception {
        UUID user = account(); Browser owner = browser(user, "ROLE_USER"), other = browser(account(), "ROLE_USER");
        String before = jdbc.queryForObject("select row_to_json(a)::text from identity.account a where user_id=?", String.class, user);
        MvcResult changed = replace(owner, input("Reader", "A short introduction.", "Reader_One")).andExpect(status().isOk()).andReturn();
        assertThat(changed.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(json.readTree(body(changed)).properties()).extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder("displayName", "biography", "handle", "updatedAt", "avatar");
        UUID id = jdbc.queryForObject("select profile_id from profile.profile where user_id=?", UUID.class, user);
        assertThat(id.toString()).matches("[0-9a-f-]{14}7[0-9a-f-]{21}");
        assertThat(jdbc.queryForObject("select public_handle_normalized from profile.profile where user_id=?", String.class, user)).isEqualTo("reader_one");
        assertThat(json.readTree(body(mvc.perform(get("/api/me/profile").cookie(other.cookie())).andReturn())).get("displayName").asText()).isEmpty();
        replace(owner, input("New display", "New biography", null)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select profile_id from profile.profile where user_id=?", UUID.class, user)).isEqualTo(id);
        assertThat(jdbc.queryForObject("select public_handle_normalized from profile.profile where user_id=?", String.class, user)).isNull();
        assertThat(jdbc.queryForObject("select row_to_json(a)::text from identity.account a where user_id=?", String.class, user)).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from identity.security_audit_fact where actor_user_id=?", Integer.class, user)).isZero();
    }

    @Test void normalizedConflictIs409WithoutLeakingAnotherProfileAndRollsBackAllFields() throws Exception {
        Browser one = browser(account(), "ROLE_USER"), two = browser(account(), "ROLE_USER");
        replace(one, input("Private owner", "Private biography", "Unique_Reader")).andExpect(status().isOk());
        replace(two, input("Original", "Kept", null)).andExpect(status().isOk());
        var conflict = replace(two, input("Changed", "Discarded", "unique_reader")).andExpect(status().isConflict()).andReturn();
        assertThat(body(conflict)).contains("profile_handle_unavailable").doesNotContain("Private owner", "Private biography", "Unique_Reader", "unique_reader");
        var kept = mvc.perform(get("/api/me/profile").cookie(two.cookie())).andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(body(kept)).get("displayName").asText()).isEqualTo("Original");
    }

    @Test void validationRejectsBoundsShapeAndAuthorityMassAssignmentWithoutWrites() throws Exception {
        UUID user = account(); Browser owner = browser(user, "ROLE_USER");
        for (String request : List.of(input("x".repeat(101), "", null), input("", "x".repeat(501), null), input("", "", "ab"),
                input("", "", "a".repeat(31)), input("", "", ""), input("", "", " user"),
                "{\"displayName\":null,\"biography\":\"\",\"handle\":null}", "{\"displayName\":\"\",\"biography\":\"\"}",
                "{\"displayName\":\"\",\"biography\":\"\",\"handle\":42}")) {
            replace(owner, request).andExpect(status().isUnprocessableContent());
        }
        for (String field : List.of("userId", "profileId", "email", "roles", "avatarObjectKey", "publicProfileActive")) {
            replace(owner, "{\"displayName\":\"\",\"biography\":\"\",\"handle\":null,\"" + field + "\":\"forged\"}")
                    .andExpect(status().isUnprocessableContent());
        }
        assertThat(jdbc.queryForObject("select count(*) from profile.profile where user_id=?", Integer.class, user)).isZero();
    }

    @Test void authenticationEligibilityAndCsrfAreMandatoryButRecentAuthIsNotInvented() throws Exception {
        mvc.perform(get("/api/me/profile")).andExpect(status().isUnauthorized());
        var anonymousProof = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
        mvc.perform(put("/api/me/profile").cookie(anonymousProof.getResponse().getCookie("SESSION"))
                .header("X-CSRF-TOKEN", json.readTree(body(anonymousProof)).get("csrfToken").asText())
                .contentType(MediaType.APPLICATION_JSON).content(input("", "", null)))
                .andExpect(status().isUnauthorized());
        mvc.perform(put("/api/me/profile").contentType(MediaType.APPLICATION_JSON).content(input("", "", null)))
                .andExpect(status().isForbidden());
        UUID user = account(); Browser owner = browser(user, "ROLE_USER"), pending = browser(account(), "ROLE_MFA_PENDING");
        mvc.perform(get("/api/me/profile").cookie(pending.cookie())).andExpect(status().isForbidden());
        replace(pending, input("", "", null)).andExpect(status().isForbidden());
        mvc.perform(put("/api/me/profile").cookie(owner.cookie()).contentType(MediaType.APPLICATION_JSON).content(input("", "", null)))
                .andExpect(status().isForbidden());
        // Synthetic full session deliberately has no recent-password-proof fact.
        replace(owner, input("Ordinary update", "", "Ordinary_Reader")).andExpect(status().isOk());
        jdbc.update("update identity.account set account_state='suspended' where user_id=?", user);
        mvc.perform(get("/api/me/profile").cookie(owner.cookie())).andExpect(status().isUnauthorized());
        // The previous request invalidated the old session; its obsolete CSRF proof
        // may be rejected before the anonymous-authority boundary.
        assertThat(replace(owner, input("Denied", "", null)).andReturn().getResponse().getStatus()).isIn(401, 403);
        assertThat(jdbc.queryForObject("select display_name from profile.profile where user_id=?", String.class, user)).isEqualTo("Ordinary update");
    }

    @Test void dependencyFailureIsSanitizedNoStore503NotEmptySuccess() throws Exception {
        Browser owner = browser(account(), "ROLE_USER");
        org.mockito.Mockito.doThrow(new DataAccessResourceFailureException("synthetic-private-diagnostic"))
                .when(repository).find(any());
        var failure = mvc.perform(get("/api/me/profile").cookie(owner.cookie())).andExpect(status().isServiceUnavailable()).andReturn();
        assertThat(body(failure)).contains("service_unavailable").doesNotContain("synthetic-private-diagnostic");
        assertThat(failure.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        org.mockito.Mockito.doThrow(new org.springframework.transaction.CannotCreateTransactionException("synthetic-private-connection-diagnostic"))
                .when(profiles).read(any());
        var connection = mvc.perform(get("/api/me/profile").cookie(owner.cookie())).andExpect(status().isServiceUnavailable()).andReturn();
        assertThat(body(connection)).contains("service_unavailable").doesNotContain("synthetic-private-connection-diagnostic");
    }

    @Test void unconfiguredAvatarStoreReturnsSanitized503WithoutCreatingProfile() throws Exception {
        UUID user = account(); Browser owner = browser(user, "ROLE_USER");
        var response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/me/profile/avatar")
                .file(new org.springframework.mock.web.MockMultipartFile("file", "fixture.png", "image/png", AvatarImages.image("png")))
                .with(request -> { request.setMethod("PUT"); return request; })
                .cookie(owner.cookie()).header("X-CSRF-TOKEN", owner.csrf()))
                .andExpect(status().isServiceUnavailable()).andReturn();
        assertThat(body(response)).contains("service_unavailable").doesNotContain("fixture", "private-avatar", "object_reference");
        assertThat(jdbc.queryForObject("select count(*) from profile.profile where user_id=?", Integer.class, user)).isZero();
    }

    @Test void profileContentNeverAppearsInRequestTelemetryOrConflictDiagnostics(CapturedOutput output) throws Exception {
        Browser one = browser(account(), "ROLE_USER"), two = browser(account(), "ROLE_USER");
        replace(one, input("SyntheticPrivacyMarkerName", "SyntheticPrivacyMarkerBiography", "PrivacyMarker_Handle"))
                .andExpect(status().isOk());
        replace(two, input("Different", "", "privacymarker_handle")).andExpect(status().isConflict());
        mvc.perform(get("/api/me/profile").cookie(one.cookie())).andExpect(status().isOk());
        assertThat(output.getAll()).doesNotContain("SyntheticPrivacyMarkerName", "SyntheticPrivacyMarkerBiography", "PrivacyMarker_Handle", "privacymarker_handle");
    }

    @Test void concurrentHandleClaimHasOneSuccessAndOneConstraintConflict() throws Exception {
        Browser one = browser(account(), "ROLE_USER"), two = browser(account(), "ROLE_USER");
        CyclicBarrier ready = new CyclicBarrier(2);
        org.mockito.Mockito.doAnswer(invocation -> {
            ready.await(10, TimeUnit.SECONDS); return invocation.callRealMethod();
        }).when(repository).replace(any());
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> replace(one, input("One", "", "Race_Reader")).andReturn().getResponse().getStatus());
            var second = workers.submit(() -> replace(two, input("Two", "", "race_reader")).andReturn().getResponse().getStatus());
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 409);
        }
        assertThat(jdbc.queryForObject("select count(*) from profile.profile where public_handle_normalized='race_reader'", Integer.class)).isEqualTo(1);
    }

    @Test void concurrentFirstReplacementKeepsOneImmutableProfilePerOwner() throws Exception {
        UUID user = account(); Browser one = browser(user, "ROLE_USER"), two = browser(user, "ROLE_USER");
        CyclicBarrier ready = new CyclicBarrier(2);
        org.mockito.Mockito.doAnswer(invocation -> { ready.await(10, TimeUnit.SECONDS); return invocation.callRealMethod(); }).when(repository).replace(any());
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> replace(one, input("One", "First", null)).andReturn().getResponse().getStatus());
            var second = workers.submit(() -> replace(two, input("Two", "Second", null)).andReturn().getResponse().getStatus());
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS))).containsExactly(200, 200);
        }
        assertThat(jdbc.queryForObject("select count(*) from profile.profile where user_id=?", Integer.class, user)).isEqualTo(1);
        String complete = jdbc.queryForObject("select display_name || ':' || biography from profile.profile where user_id=?", String.class, user);
        assertThat(complete).isIn("One:First", "Two:Second");
    }

    @Test void migrationAddsOnlyPrivateRootAndRestrictsIdentityHandleAndBounds() throws Exception {
        assertThat(jdbc.queryForObject("show server_version", String.class)).startsWith("18.");
        assertThat(jdbc.queryForObject("select count(*) from pg_tables where schemaname in ('identity','profile','notes','knowledge','publishing','discovery','moderation')", Integer.class)).isEqualTo(18);
        assertThat(jdbc.queryForList("select tablename from pg_tables where schemaname='profile'", String.class)).containsExactlyInAnyOrder("profile", "avatar_asset");
        assertThat(jdbc.queryForList("select column_name from information_schema.columns where table_schema='profile' and table_name='profile' order by ordinal_position", String.class))
                .containsExactly("profile_id", "user_id", "display_name", "biography", "public_handle_original", "public_handle_normalized", "updated_at", "selected_avatar_id");
        UUID user = account(), other = account(); Browser owner = browser(user, "ROLE_USER");
        replace(owner, input("", "", null)).andExpect(status().isOk());
        assertThatThrownBy(() -> jdbc.update("delete from identity.account where user_id=?", user)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update profile.profile set user_id=? where user_id=?", other, user)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update profile.profile set profile_id=uuidv7() where user_id=?", user)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into profile.profile values (uuidv7(),?,'','',null,null,now())", user)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into profile.profile values (uuidv7(),uuidv7(),'','',null,null,now())")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update profile.profile set display_name=? where user_id=?", "x".repeat(101), user)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update profile.profile set biography=? where user_id=?", "x".repeat(501), user)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update profile.profile set public_handle_original='Reader',public_handle_normalized=null where user_id=?", user)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update profile.profile set public_handle_original='Reader',public_handle_normalized='WRONG' where user_id=?", user)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update profile.profile set public_handle_original='a-b',public_handle_normalized='a-b' where user_id=?", user)).isInstanceOf(DataIntegrityViolationException.class);
        for (String table : List.of("profile.public_profile_projection", "notes.attachment")) {
            assertThat(jdbc.queryForObject("select to_regclass(?) is null", Boolean.class, table)).isTrue();
        }
    }

    private org.springframework.test.web.servlet.ResultActions replace(Browser owner, String input) throws Exception {
        return mvc.perform(put("/api/me/profile").cookie(owner.cookie()).header("X-CSRF-TOKEN", owner.csrf()).contentType(MediaType.APPLICATION_JSON).content(input));
    }
    private String input(String name, String biography, String handle) {
        var input = new java.util.LinkedHashMap<String, Object>();
        input.put("displayName", name); input.put("biography", biography); input.put("handle", handle);
        return json.writeValueAsString(input);
    }
    private UUID account() {
        UUID id = jdbc.queryForObject("select uuidv7()", UUID.class);
        String email = "profile-" + id + "@example.test";
        jdbc.update("insert into identity.account (user_id,canonical_email,display_email,email_verified_at,account_state,created_at,updated_at) values (?,?,?,now(),'active',now(),now())", id, email, email);
        return id;
    }
    private Browser browser(UUID user, String role) throws Exception {
        Session session = sessions.createSession(); var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(new IdentitySessionPrincipal(user), null, List.of(new SimpleGrantedAuthority(role))));
        session.setAttribute("SPRING_SECURITY_CONTEXT", context); save(session);
        Cookie cookie = new Cookie("SESSION", Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8)));
        var csrf = mvc.perform(get("/api/auth/csrf").cookie(cookie)).andExpect(status().isOk()).andReturn();
        return new Browser(csrf.getResponse().getCookie("SESSION") == null ? cookie : csrf.getResponse().getCookie("SESSION"), json.readTree(body(csrf)).get("csrfToken").asText());
    }
    @SuppressWarnings({"rawtypes", "unchecked"}) private void save(Session session) { ((SessionRepository) sessions).save(session); }
    private String body(MvcResult result) throws Exception { return result.getResponse().getContentAsString(); }
    private record Browser(Cookie cookie, String csrf) { }
}
