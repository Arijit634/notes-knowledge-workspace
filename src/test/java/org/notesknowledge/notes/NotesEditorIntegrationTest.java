package org.notesknowledge.notes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.notesknowledge.identity.IdentitySessionPrincipal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
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
class NotesEditorIntegrationTest {
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            "pgvector/pgvector:0.8.6-pg18-trixie")
            .withDatabaseName("notes_editor").withUsername("notes_migrator")
            .withPassword("synthetic-notes-migrator-password");

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("notes.cursor.active.version", () -> "test1");
        registry.add("notes.cursor.active.key-base64", () ->
                "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=");
        registry.add("identity.rate.key-base64", () ->
                "AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=");
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired SessionRepository<? extends Session> sessions;
    @Autowired ObjectMapper json;

    @Test
    void migrationCreatesOnlyTwoNotesRelationsAndEnforcesOwnerAndRowShape() {
        assertThat(jdbc.queryForObject("""
                select count(*) from information_schema.tables
                where table_schema in ('identity','profile','notes','knowledge',
                    'publishing','discovery','moderation') and table_type = 'BASE TABLE'
                """, Integer.class)).isEqualTo(13);
        assertThat(jdbc.queryForList("""
                select table_name from information_schema.tables
                where table_schema = 'notes' and table_type = 'BASE TABLE'
                order by table_name
                """, String.class)).containsExactly("note", "note_preferences");
        UUID owner = account();
        UUID other = account();
        UUID noteId = jdbc.queryForObject("select uuidv7()", UUID.class);
        jdbc.update("""
                insert into notes.note (note_id, owner_user_id, title, markdown,
                    lifecycle_state, pinned, revision, ai_enabled, ai_generation,
                    created_at, updated_at)
                values (?, ?, 'Synthetic', '', 'active', false, 1, false, 1, now(), now())
                """, noteId, owner);
        assertThatThrownBy(() -> jdbc.update(
                "update notes.note set owner_user_id = ? where note_id = ?", other, noteId))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "update notes.note set revision = 0 where note_id = ?", noteId))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                update notes.note set lifecycle_state = 'trashed' where note_id = ?
                """, noteId))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                insert into notes.note_preferences (user_id, default_ai_enabled, updated_at)
                values (?, false, now())
                """, UUID.randomUUID()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void preferenceCreateSaveAndOwnershipUseOnlyCurrentSessionAuthority() throws Exception {
        UUID owner = account();
        UUID other = account();
        Browser first = browser(owner, "ROLE_USER");
        Browser second = browser(other, "ROLE_USER");

        assertThat(body(mvc.perform(get("/api/me/note-preferences").cookie(first.cookie()))
                .andExpect(status().isOk()).andReturn()))
                .contains("\"defaultAiEnabledForNewNotes\":false");
        MvcResult created = mvc.perform(post("/api/notes").cookie(first.cookie())
                .header("X-CSRF-TOKEN", first.csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"First\",\"markdown\":\"**draft**\"}"))
                .andExpect(status().isCreated()).andReturn();
        String id = json.readTree(body(created)).get("id").asText();
        String etag = created.getResponse().getHeader("ETag");
        assertThat(etag).matches("\"[A-Za-z0-9_-]+\"");
        assertThat(created.getResponse().getHeader("Location")).isEqualTo("/api/notes/" + id);
        assertThat(json.readTree(body(created)).get("aiEnabled").asBoolean()).isFalse();
        assertThat(json.readTree(body(created)).get("tags").isArray()).isTrue();
        assertThat(jdbc.queryForObject("select revision from notes.note where note_id = ?::uuid",
                Long.class, id)).isEqualTo(1L);

        mvc.perform(put("/api/me/note-preferences").cookie(first.cookie())
                .header("X-CSRF-TOKEN", first.csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"defaultAiEnabledForNewNotes\":true}"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select ai_enabled from notes.note where note_id = ?::uuid",
                Boolean.class, id)).isFalse();
        MvcResult inherited = mvc.perform(post("/api/notes").cookie(first.cookie())
                .header("X-CSRF-TOKEN", first.csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Next\",\"markdown\":\"body\"}"))
                .andExpect(status().isCreated()).andReturn();
        assertThat(json.readTree(body(inherited)).get("aiEnabled").asBoolean()).isTrue();
        MvcResult override = mvc.perform(post("/api/notes").cookie(first.cookie())
                .header("X-CSRF-TOKEN", first.csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Override\",\"markdown\":\"body\",\"aiEnabled\":false}"))
                .andExpect(status().isCreated()).andReturn();
        assertThat(json.readTree(body(override)).get("aiEnabled").asBoolean()).isFalse();

        mvc.perform(get("/api/notes/{noteId}", id).cookie(second.cookie()))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/notes/{noteId}", id).cookie(second.cookie())
                .header("X-CSRF-TOKEN", second.csrf()).header("If-Match", etag)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Intrusion\",\"markdown\":\"x\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/notes").cookie(first.cookie())
                .header("X-CSRF-TOKEN", first.csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Bad\",\"markdown\":\"x\",\"ownerUserId\":\"" + other + "\"}"))
                .andExpect(status().isUnprocessableContent());

        mvc.perform(put("/api/notes/{noteId}", id).cookie(first.cookie())
                .header("X-CSRF-TOKEN", first.csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Changed\",\"markdown\":\"new\"}"))
                .andExpect(status().isPreconditionRequired());
        MvcResult saved = mvc.perform(put("/api/notes/{noteId}", id).cookie(first.cookie())
                .header("X-CSRF-TOKEN", first.csrf()).header("If-Match", etag)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Changed\",\"markdown\":\"new\"}"))
                .andExpect(status().isOk()).andReturn();
        assertThat(saved.getResponse().getHeader("ETag")).isNotEqualTo(etag);
        mvc.perform(put("/api/notes/{noteId}", id).cookie(first.cookie())
                .header("X-CSRF-TOKEN", first.csrf()).header("If-Match", etag)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Stale\",\"markdown\":\"lost\"}"))
                .andExpect(status().isPreconditionFailed());
        assertThat(jdbc.queryForObject("select revision from notes.note where note_id = ?::uuid",
                Long.class, id)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("select title from notes.note where note_id = ?::uuid",
                String.class, id)).isEqualTo("Changed");

        jdbc.update("""
                update notes.note set lifecycle_state = 'archived', revision = revision + 1
                where note_id = ?::uuid
                """, id);
        String archivedEtag = mvc.perform(get("/api/notes/{noteId}", id).cookie(first.cookie()))
                .andExpect(status().isOk()).andReturn().getResponse().getHeader("ETag");
        mvc.perform(put("/api/notes/{noteId}", id).cookie(first.cookie())
                .header("X-CSRF-TOKEN", first.csrf()).header("If-Match", archivedEtag)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Illegal\",\"markdown\":\"x\"}"))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("select revision from notes.note where note_id = ?::uuid",
                Long.class, id)).isEqualTo(3L);
    }

    @Test
    void csrfAndPreMfaGateProtectPrivateNotesAndCursorScopeIsOwnerBound() throws Exception {
        Browser owner = browser(account(), "ROLE_USER");
        Browser other = browser(account(), "ROLE_USER");
        Browser pending = browser(account(), "ROLE_MFA_PENDING");
        mvc.perform(get("/api/notes").cookie(pending.cookie()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/notes").cookie(owner.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"No CSRF\",\"markdown\":\"x\"}"))
                .andExpect(status().isForbidden());
        for (int n = 0; n < 2; n++) {
            mvc.perform(post("/api/notes").cookie(owner.cookie())
                    .header("X-CSRF-TOKEN", owner.csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"title\":\"Page " + n + "\",\"markdown\":\"x\"}"))
                    .andExpect(status().isCreated());
        }
        MvcResult page = mvc.perform(get("/api/notes?limit=1").cookie(owner.cookie()))
                .andExpect(status().isOk()).andReturn();
        String cursor = json.readTree(body(page)).get("nextCursor").asText();
        assertThat(cursor).doesNotContain("Page");
        mvc.perform(get("/api/notes").queryParam("limit", "1")
                .queryParam("cursor", cursor).cookie(owner.cookie()))
                .andExpect(status().isOk());
        MvcResult pinned = mvc.perform(get("/api/notes").queryParam("pinned", "true")
                .cookie(owner.cookie())).andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(body(pinned)).get("items").isEmpty()).isTrue();
        mvc.perform(get("/api/notes").queryParam("limit", "1")
                .queryParam("cursor", cursor).cookie(other.cookie()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/notes").queryParam("limit", "1")
                .queryParam("pinned", "true")
                .queryParam("cursor", cursor).cookie(owner.cookie()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void concurrentEditorsCannotBothCommitTheSameRevision() throws Exception {
        UUID owner = account();
        Browser first = browser(owner, "ROLE_USER");
        Browser second = browser(owner, "ROLE_USER");
        MvcResult created = mvc.perform(post("/api/notes").cookie(first.cookie())
                .header("X-CSRF-TOKEN", first.csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Shared\",\"markdown\":\"original\"}"))
                .andExpect(status().isCreated()).andReturn();
        String id = json.readTree(body(created)).get("id").asText();
        String etag = created.getResponse().getHeader("ETag");
        CountDownLatch start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var a = workers.submit(() -> concurrentSave(start, first, id, etag, "Editor A"));
            var b = workers.submit(() -> concurrentSave(start, second, id, etag, "Editor B"));
            start.countDown();
            assertThat(List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 412);
        }
        assertThat(jdbc.queryForObject("select revision from notes.note where note_id = ?::uuid",
                Long.class, id)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("select title from notes.note where note_id = ?::uuid",
                String.class, id)).isIn("Editor A", "Editor B");
    }

    private int concurrentSave(CountDownLatch start, Browser browser, String id,
            String etag, String title) throws Exception {
        if (!start.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("start timed out");
        return mvc.perform(put("/api/notes/{noteId}", id).cookie(browser.cookie())
                .header("X-CSRF-TOKEN", browser.csrf()).header("If-Match", etag)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"" + title + "\",\"markdown\":\"saved\"}"))
                .andReturn().getResponse().getStatus();
    }

    private UUID account() {
        UUID id = jdbc.queryForObject("select uuidv7()", UUID.class);
        String email = "note-" + id + "@example.test";
        jdbc.update("""
                insert into identity.account (user_id, canonical_email, display_email,
                    email_verified_at, account_state, created_at, updated_at)
                values (?, ?, ?, now(), 'active', now(), now())
                """, id, email, email);
        return id;
    }

    private Browser browser(UUID owner, String role) throws Exception {
        Session session = sessions.createSession();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new IdentitySessionPrincipal(owner), null,
                List.of(new SimpleGrantedAuthority(role))));
        session.setAttribute("SPRING_SECURITY_CONTEXT", context);
        save(session);
        Cookie cookie = new Cookie("SESSION", Base64.getEncoder().encodeToString(
                session.getId().getBytes(StandardCharsets.UTF_8)));
        MvcResult csrf = mvc.perform(get("/api/auth/csrf").cookie(cookie))
                .andExpect(status().isOk()).andReturn();
        return new Browser(csrf.getResponse().getCookie("SESSION") == null
                ? cookie : csrf.getResponse().getCookie("SESSION"),
                json.readTree(body(csrf)).get("csrfToken").asText());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void save(Session session) { ((SessionRepository) sessions).save(session); }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    private record Browser(Cookie cookie, String csrf) { }
}
