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
    void migrationCreatesOnlyThreeNotesRelationsAndEnforcesOwnerAndRowShape() {
        assertThat(jdbc.queryForObject("""
                select count(*) from information_schema.tables
                where table_schema in ('identity','profile','notes','knowledge',
                    'publishing','discovery','moderation') and table_type = 'BASE TABLE'
                """, Integer.class)).isEqualTo(14);
        assertThat(jdbc.queryForList("""
                select table_name from information_schema.tables
                where table_schema = 'notes' and table_type = 'BASE TABLE'
                order by table_name
                """, String.class)).containsExactly("note", "note_preferences", "note_tag");
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

    @Test
    void tagsHaveCompositeNoteLocalIdentityAndRestrictiveOwnerSafeForeignKey() throws Exception {
        UUID owner = account();
        UUID other = account();
        Browser browser = browser(owner, "ROLE_USER");
        MvcResult note = createNote(browser);
        UUID id = UUID.fromString(json.readTree(body(note)).get("id").asText());
        jdbc.update("""
                insert into notes.note_tag values (?, ?, 'films', 'Films', now())
                """, id, owner);
        assertThatThrownBy(() -> jdbc.update("""
                insert into notes.note_tag values (?, ?, 'films', 'FILMS', now())
                """, id, owner)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                insert into notes.note_tag values (?, ?, 'other', 'Other', now())
                """, id, other)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("delete from notes.note where note_id = ?", id))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("""
                select pg_get_constraintdef(oid) from pg_constraint
                where conrelid = 'notes.note_tag'::regclass and contype = 'p'
                """, String.class)).isEqualTo("PRIMARY KEY (note_id, normalized_label)");
        assertThat(jdbc.queryForObject("""
                select pg_get_constraintdef(oid) from pg_constraint
                where conrelid = 'notes.note_tag'::regclass and contype = 'f'
                """, String.class)).contains("(note_id, owner_user_id)", "ON DELETE RESTRICT");
        assertThat(jdbc.queryForList("select indexname from pg_indexes where schemaname = 'notes'",
                String.class)).contains("ix_note_tag_owner_label");
        UUID secondId = UUID.fromString(json.readTree(body(createNote(browser))).get("id").asText());
        jdbc.update("insert into notes.note_tag values (?, ?, 'films', 'Films', now())", secondId, owner);
        UUID otherId = UUID.fromString(json.readTree(body(createNote(browser(other, "ROLE_USER")))).get("id").asText());
        jdbc.update("insert into notes.note_tag values (?, ?, 'films', 'Films', now())", otherId, other);
        assertThat(jdbc.queryForObject("select count(*) from notes.note_tag where note_id = ?",
                Integer.class, id)).isEqualTo(1);
    }

    @Test
    void explicitTagReplacementReturnsAuthoritativeCoreAndRejectsStaleCommands() throws Exception {
        Browser owner = browser(account(), "ROLE_USER");
        MvcResult created = createNote(owner);
        String id = json.readTree(body(created)).get("id").asText();
        String first = created.getResponse().getHeader("ETag");
        MvcResult tagged = replaceTags(owner, id, first, List.of(" Films ", "Watch-later"))
                .andExpect(status().isOk()).andReturn();
        String second = tagged.getResponse().getHeader("ETag");
        assertThat(second).isNotEqualTo(first);
        assertThat(json.readTree(body(tagged)).get("tags").toString()).isEqualTo("[\"Films\",\"Watch-later\"]");
        assertThat(json.readTree(body(tagged)).get("title").asText()).isEqualTo("Original");
        assertThat(json.readTree(body(mvc.perform(get("/api/notes").cookie(owner.cookie()))
                .andExpect(status().isOk()).andReturn())).get("items").get(0).get("tags").toString())
                .isEqualTo("[\"Films\",\"Watch-later\"]");
        replaceTags(owner, id, first, List.of("Stale")).andExpect(status().isPreconditionFailed());
        mvc.perform(put("/api/notes/{noteId}", id).cookie(owner.cookie())
                .header("X-CSRF-TOKEN", owner.csrf()).header("If-Match", first)
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Stale\",\"markdown\":\"x\"}"))
                .andExpect(status().isPreconditionFailed());
        MvcResult same = replaceTags(owner, id, second, List.of("Watch-later", "Films"))
                .andExpect(status().isOk()).andReturn();
        assertThat(same.getResponse().getHeader("ETag")).isEqualTo(second);
        MvcResult reduced = replaceTags(owner, id, second, List.of("Films"))
                .andExpect(status().isOk()).andReturn();
        String third = reduced.getResponse().getHeader("ETag");
        assertThat(jdbc.queryForObject("select revision from notes.note where note_id = ?::uuid",
                Long.class, id)).isEqualTo(3L);
        MvcResult saved = mvc.perform(put("/api/notes/{noteId}", id).cookie(owner.cookie())
                .header("X-CSRF-TOKEN", owner.csrf()).header("If-Match", third)
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Saved\",\"markdown\":\"text\"}"))
                .andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(body(saved)).get("tags").toString()).isEqualTo("[\"Films\"]");
        replaceTags(owner, id, third, List.of()).andExpect(status().isPreconditionFailed());
        replaceTags(owner, id, saved.getResponse().getHeader("ETag"), List.of())
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from notes.note_tag where note_id = ?::uuid",
                Integer.class, id)).isZero();
    }

    @Test
    void tagCommandEnforcesOwnerSessionCsrfPreconditionValidationAndLifecycle() throws Exception {
        Browser owner = browser(account(), "ROLE_USER");
        Browser other = browser(account(), "ROLE_USER");
        Browser pending = browser(account(), "ROLE_MFA_PENDING");
        MvcResult created = createNote(owner);
        String id = json.readTree(body(created)).get("id").asText();
        String etag = created.getResponse().getHeader("ETag");
        replaceTags(other, id, etag, List.of()).andExpect(status().isNotFound());
        replaceTags(owner, UUID.randomUUID().toString(), etag, List.of()).andExpect(status().isNotFound());
        replaceTags(pending, id, etag, List.of()).andExpect(status().isForbidden());
        mvc.perform(put("/api/notes/{noteId}/tags", id)
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                .header("If-Match", etag).contentType(MediaType.APPLICATION_JSON).content("{\"tags\":[]}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(put("/api/notes/{noteId}/tags", id).cookie(owner.cookie())
                .header("If-Match", etag).contentType(MediaType.APPLICATION_JSON).content("{\"tags\":[]}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/notes/{noteId}/tags", id).cookie(owner.cookie())
                .header("X-CSRF-TOKEN", owner.csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"tags\":[]}"))
                .andExpect(status().isPreconditionRequired());
        replaceTags(owner, id, etag, List.of("Films", " films ")).andExpect(status().isUnprocessableContent());
        replaceTags(owner, id, etag, List.of("bad\nlabel")).andExpect(status().isUnprocessableContent());
        for (String input : List.of("{}", "{\"tags\":null}", "{\"tags\":[1]}", "{\"tags\":[null]}")) {
            mvc.perform(put("/api/notes/{noteId}/tags", id).cookie(owner.cookie())
                    .header("X-CSRF-TOKEN", owner.csrf()).header("If-Match", etag)
                    .contentType(MediaType.APPLICATION_JSON).content(input))
                    .andExpect(status().isUnprocessableContent());
        }
        mvc.perform(put("/api/notes/{noteId}/tags", id).cookie(owner.cookie())
                .header("X-CSRF-TOKEN", owner.csrf()).header("If-Match", etag)
                .contentType(MediaType.APPLICATION_JSON).content("{\"tags\":[],\"acceptedSuggestionId\":\"fake\"}"))
                .andExpect(status().isUnprocessableContent());
        assertThat(jdbc.queryForObject("select revision from notes.note where note_id = ?::uuid",
                Long.class, id)).isEqualTo(1L);
        jdbc.update("""
                update notes.note set lifecycle_state = 'trashed', pre_trash_state = 'active',
                    trashed_at = now(), revision = revision + 1 where note_id = ?::uuid
                """, id);
        String current = mvc.perform(get("/api/notes/{noteId}", id).cookie(owner.cookie()))
                .andReturn().getResponse().getHeader("ETag");
        replaceTags(owner, id, current, List.of()).andExpect(status().isConflict());
    }

    @Test
    void simultaneousSaveAndTagReplacementHaveOnlyOneRevisionWinner() throws Exception {
        Browser owner = browser(account(), "ROLE_USER");
        MvcResult created = createNote(owner);
        String id = json.readTree(body(created)).get("id").asText();
        String etag = created.getResponse().getHeader("ETag");
        CountDownLatch start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var save = workers.submit(() -> concurrentSave(start, owner, id, etag, "Saved winner"));
            var tags = workers.submit(() -> {
                if (!start.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("start timed out");
                return replaceTags(owner, id, etag, List.of("Films")).andReturn().getResponse().getStatus();
            });
            start.countDown();
            int saveStatus = save.get(30, TimeUnit.SECONDS);
            int tagStatus = tags.get(30, TimeUnit.SECONDS);
            assertThat(List.of(saveStatus, tagStatus)).containsExactlyInAnyOrder(200, 412);
            assertThat(jdbc.queryForObject("select title from notes.note where note_id = ?::uuid",
                    String.class, id)).isEqualTo(saveStatus == 200 ? "Saved winner" : "Original");
            assertThat(jdbc.queryForObject("select count(*) from notes.note_tag where note_id = ?::uuid",
                    Integer.class, id)).isEqualTo(tagStatus == 200 ? 1 : 0);
        }
        assertThat(jdbc.queryForObject("select revision from notes.note where note_id = ?::uuid",
                Long.class, id)).isEqualTo(2L);
    }

    @Test
    void concurrentTagSetsCannotMergeOrOverwriteTheWinningSet() throws Exception {
        Browser owner = browser(account(), "ROLE_USER");
        MvcResult created = createNote(owner);
        String id = json.readTree(body(created)).get("id").asText();
        String etag = created.getResponse().getHeader("ETag");
        CountDownLatch start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var a = workers.submit(() -> {
                if (!start.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("start timed out");
                return replaceTags(owner, id, etag, List.of("Alpha")).andReturn().getResponse().getStatus();
            });
            var b = workers.submit(() -> {
                if (!start.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("start timed out");
                return replaceTags(owner, id, etag, List.of("Beta")).andReturn().getResponse().getStatus();
            });
            start.countDown();
            int first = a.get(30, TimeUnit.SECONDS);
            assertThat(List.of(first, b.get(30, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 412);
            assertThat(jdbc.queryForList("select display_label from notes.note_tag where note_id = ?::uuid",
                    String.class, id)).containsExactly(first == 200 ? "Alpha" : "Beta");
        }
        assertThat(jdbc.queryForObject("select revision from notes.note where note_id = ?::uuid",
                Long.class, id)).isEqualTo(2L);
    }

    @Test
    void pinDesiredStateNoOpsStillRequireCurrentAuthorityAndRealChangesAdvanceRevision() throws Exception {
        Browser owner = browser(account(), "ROLE_USER");
        MvcResult created = createNote(owner);
        String id = json.readTree(body(created)).get("id").asText();
        String first = created.getResponse().getHeader("ETag");
        MvcResult unpinned = command(owner, id, first, "DELETE", "pin").andExpect(status().isOk()).andReturn();
        assertThat(unpinned.getResponse().getHeader("ETag")).isEqualTo(first);
        MvcResult pinned = command(owner, id, first, "PUT", "pin").andExpect(status().isOk()).andReturn();
        String second = pinned.getResponse().getHeader("ETag");
        assertThat(second).isNotEqualTo(first);
        assertThat(json.readTree(body(pinned)).get("pinned").asBoolean()).isTrue();
        assertThat(json.readTree(body(pinned)).get("title").asText()).isEqualTo("Original");
        assertThat(json.readTree(body(pinned)).get("markdown").asText()).isEqualTo("Body");
        command(owner, id, first, "PUT", "pin").andExpect(status().isPreconditionFailed());
        MvcResult same = command(owner, id, second, "PUT", "pin").andExpect(status().isOk()).andReturn();
        assertThat(same.getResponse().getHeader("ETag")).isEqualTo(second);
        assertThat(jdbc.queryForObject("select revision from notes.note where note_id = ?::uuid", Long.class, id)).isEqualTo(2L);
        MvcResult cleared = command(owner, id, second, "DELETE", "pin").andExpect(status().isOk()).andReturn();
        String third = cleared.getResponse().getHeader("ETag");
        assertThat(third).isNotEqualTo(second);
        assertThat(json.readTree(body(cleared)).get("pinned").asBoolean()).isFalse();
        command(owner, id, second, "DELETE", "pin").andExpect(status().isPreconditionFailed());
        assertThat(command(owner, id, third, "DELETE", "pin").andExpect(status().isOk()).andReturn()
                .getResponse().getHeader("ETag")).isEqualTo(third);
        assertThat(jdbc.queryForObject("select revision from notes.note where note_id = ?::uuid", Long.class, id)).isEqualTo(3L);
    }

    @Test
    void archiveAndReturnAreExplicitTransitionsPreservingPinTagsAndSavedContent() throws Exception {
        Browser owner = browser(account(), "ROLE_USER");
        MvcResult created = createNote(owner);
        String id = json.readTree(body(created)).get("id").asText();
        String initial = created.getResponse().getHeader("ETag");
        command(owner, id, initial, "POST", "return-from-archive").andExpect(status().isConflict());
        MvcResult pinned = command(owner, id, initial, "PUT", "pin").andExpect(status().isOk()).andReturn();
        MvcResult tagged = replaceTags(owner, id, pinned.getResponse().getHeader("ETag"), List.of("Films"))
                .andExpect(status().isOk()).andReturn();
        MvcResult archived = command(owner, id, tagged.getResponse().getHeader("ETag"), "POST", "archive")
                .andExpect(status().isOk()).andReturn();
        String archivedEtag = archived.getResponse().getHeader("ETag");
        assertThat(json.readTree(body(archived)).get("lifecycle").asText()).isEqualTo("archived");
        assertThat(json.readTree(body(archived)).get("pinned").asBoolean()).isTrue();
        assertThat(json.readTree(body(archived)).get("tags").toString()).isEqualTo("[\"Films\"]");
        assertThat(archivedEtag).isNotEqualTo(tagged.getResponse().getHeader("ETag"));
        command(owner, id, archivedEtag, "POST", "archive").andExpect(status().isConflict());
        mvc.perform(put("/api/notes/{noteId}", id).cookie(owner.cookie()).header("X-CSRF-TOKEN", owner.csrf())
                .header("If-Match", archivedEtag).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Forbidden\",\"markdown\":\"Draft\"}"))
                .andExpect(status().isConflict());
        assertThat(json.readTree(body(mvc.perform(get("/api/notes").cookie(owner.cookie())).andReturn())).get("items").isEmpty()).isTrue();
        assertThat(json.readTree(body(mvc.perform(get("/api/notes?lifecycle=archived&pinned=true")
                .cookie(owner.cookie())).andReturn())).get("items").get(0).get("id").asText()).isEqualTo(id);
        MvcResult unpinned = command(owner, id, archivedEtag, "DELETE", "pin").andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(body(unpinned)).get("lifecycle").asText()).isEqualTo("archived");
        MvcResult returned = command(owner, id, unpinned.getResponse().getHeader("ETag"), "POST", "return-from-archive")
                .andExpect(status().isOk()).andReturn();
        assertThat(returned.getResponse().getHeader("ETag")).isNotEqualTo(unpinned.getResponse().getHeader("ETag"));
        assertThat(json.readTree(body(returned)).get("lifecycle").asText()).isEqualTo("active");
        assertThat(json.readTree(body(returned)).get("title").asText()).isEqualTo("Original");
        assertThat(json.readTree(body(returned)).get("markdown").asText()).isEqualTo("Body");
        command(owner, id, returned.getResponse().getHeader("ETag"), "POST", "return-from-archive").andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("select revision from notes.note where note_id = ?::uuid", Long.class, id)).isEqualTo(6L);
    }

    @Test
    void organizationCommandsRequireOwnerFullSessionCsrfCurrentEtagAndEligibleLifecycle() throws Exception {
        UUID ownerId = account();
        Browser owner = browser(ownerId, "ROLE_USER");
        Browser other = browser(account(), "ROLE_USER");
        Browser pending = browser(account(), "ROLE_MFA_PENDING");
        MvcResult created = createNote(owner);
        String id = json.readTree(body(created)).get("id").asText();
        String etag = created.getResponse().getHeader("ETag");
        String[][] commands = { {"PUT", "pin"}, {"DELETE", "pin"}, {"POST", "archive"}, {"POST", "return-from-archive"} };
        for (String[] action : commands) {
            command(other, id, etag, action[0], action[1]).andExpect(status().isNotFound());
            command(owner, UUID.randomUUID().toString(), etag, action[0], action[1]).andExpect(status().isNotFound());
            command(pending, id, etag, action[0], action[1]).andExpect(status().isForbidden());
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(
                    org.springframework.http.HttpMethod.valueOf(action[0]), "/api/notes/{noteId}/" + action[1], id)
                    .cookie(owner.cookie()).header("If-Match", etag)).andExpect(status().isForbidden());
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(
                    org.springframework.http.HttpMethod.valueOf(action[0]), "/api/notes/{noteId}/" + action[1], id))
                    .andExpect(status().isForbidden());
            command(owner, id, null, action[0], action[1]).andExpect(status().isPreconditionRequired());
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(
                    org.springframework.http.HttpMethod.valueOf(action[0]), "/api/notes/{noteId}/" + action[1], id)
                    .cookie(owner.cookie()).header("X-CSRF-TOKEN", owner.csrf()).header("If-Match", etag)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Not saved\"}"))
                    .andExpect(status().isBadRequest());
        }
        jdbc.update("""
                update notes.note set lifecycle_state = 'trashed', pre_trash_state = 'active',
                    trashed_at = now(), revision = revision + 1 where note_id = ?::uuid
                """, id);
        String current = mvc.perform(get("/api/notes/{noteId}", id).cookie(owner.cookie())).andReturn().getResponse().getHeader("ETag");
        for (String[] action : commands) {
            command(owner, id, etag, action[0], action[1]).andExpect(status().isPreconditionFailed());
            command(owner, id, current, action[0], action[1]).andExpect(status().isConflict());
        }
        jdbc.update("""
                update notes.note set lifecycle_state = 'logically_deleted', pre_trash_state = null,
                    trashed_at = null, deleted_at = now(), revision = revision + 1 where note_id = ?::uuid
                """, id);
        for (String[] action : commands) command(owner, id, current, action[0], action[1]).andExpect(status().isNotFound());
        MvcResult fresh = createNote(owner);
        String freshId = json.readTree(body(fresh)).get("id").asText();
        jdbc.update("update identity.account set account_state = 'suspended' where user_id = ?", ownerId);
        for (String[] action : commands) {
            // Eligibility invalidates the old session and its CSRF authority;
            // either security boundary may reject the unsafe browser request.
            command(owner, freshId, fresh.getResponse().getHeader("ETag"), action[0], action[1])
                    .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(401, 403));
        }
        assertThat(jdbc.queryForObject("select revision from notes.note where note_id = ?::uuid", Long.class, freshId)).isEqualTo(1L);
    }

    @Test
    void concurrentSavePinArchiveAndTagsCannotBothMutateOneRevision() throws Exception {
        for (String[] pair : new String[][] { {"save", "pin"}, {"save", "archive"}, {"tags", "archive"}, {"pin", "archive"}, {"archive", "archive"}, {"pin", "pin"} }) {
            Browser owner = browser(account(), "ROLE_USER");
            MvcResult created = createNote(owner);
            String id = json.readTree(body(created)).get("id").asText();
            String etag = created.getResponse().getHeader("ETag");
            CountDownLatch start = new CountDownLatch(1);
            try (var workers = Executors.newFixedThreadPool(2)) {
                var a = workers.submit(() -> racedCommand(start, owner, id, etag, pair[0]));
                var b = workers.submit(() -> racedCommand(start, owner, id, etag, pair[1]));
                start.countDown();
                int first = a.get(30, TimeUnit.SECONDS), second = b.get(30, TimeUnit.SECONDS);
                assertThat(List.of(first, second)).containsExactlyInAnyOrder(200, 412);
                String winner = first == 200 ? pair[0] : pair[1];
                assertThat(jdbc.queryForObject("select revision from notes.note where note_id = ?::uuid", Long.class, id)).isEqualTo(2L);
                assertThat(jdbc.queryForObject("select title from notes.note where note_id = ?::uuid", String.class, id)).isEqualTo(winner.equals("save") ? "Winner" : "Original");
                assertThat(jdbc.queryForObject("select pinned from notes.note where note_id = ?::uuid", Boolean.class, id)).isEqualTo(winner.equals("pin"));
                assertThat(jdbc.queryForObject("select lifecycle_state from notes.note where note_id = ?::uuid", String.class, id)).isEqualTo(winner.equals("archive") ? "archived" : "active");
                assertThat(jdbc.queryForObject("select count(*) from notes.note_tag where note_id = ?::uuid", Integer.class, id)).isEqualTo(winner.equals("tags") ? 1 : 0);
            }
        }
    }

    @Test
    void archivedUnpinAndReturnShareTheSameRevisionWithTagReplacement() throws Exception {
        for (String[] pair : new String[][] { {"unpin", "return-from-archive"}, {"unpin", "tags"}, {"return-from-archive", "tags"} }) {
            Browser owner = browser(account(), "ROLE_USER");
            MvcResult created = createNote(owner);
            String id = json.readTree(body(created)).get("id").asText();
            MvcResult archived = command(owner, id, created.getResponse().getHeader("ETag"), "POST", "archive")
                    .andExpect(status().isOk()).andReturn();
            MvcResult pinned = command(owner, id, archived.getResponse().getHeader("ETag"), "PUT", "pin")
                    .andExpect(status().isOk()).andReturn();
            String etag = pinned.getResponse().getHeader("ETag");
            CountDownLatch start = new CountDownLatch(1);
            try (var workers = Executors.newFixedThreadPool(2)) {
                var a = workers.submit(() -> racedCommand(start, owner, id, etag, pair[0]));
                var b = workers.submit(() -> racedCommand(start, owner, id, etag, pair[1]));
                start.countDown();
                int first = a.get(30, TimeUnit.SECONDS), second = b.get(30, TimeUnit.SECONDS);
                assertThat(List.of(first, second)).containsExactlyInAnyOrder(200, 412);
                String winner = first == 200 ? pair[0] : pair[1];
                assertThat(jdbc.queryForObject("select revision from notes.note where note_id = ?::uuid", Long.class, id)).isEqualTo(4L);
                assertThat(jdbc.queryForObject("select pinned from notes.note where note_id = ?::uuid", Boolean.class, id)).isEqualTo(!winner.equals("unpin"));
                assertThat(jdbc.queryForObject("select lifecycle_state from notes.note where note_id = ?::uuid", String.class, id)).isEqualTo(winner.equals("return-from-archive") ? "active" : "archived");
                assertThat(jdbc.queryForObject("select count(*) from notes.note_tag where note_id = ?::uuid", Integer.class, id)).isEqualTo(winner.equals("tags") ? 1 : 0);
                assertThat(jdbc.queryForObject("select title from notes.note where note_id = ?::uuid", String.class, id)).isEqualTo("Original");
                assertThat(jdbc.queryForObject("select markdown from notes.note where note_id = ?::uuid", String.class, id)).isEqualTo("Body");
            }
        }
    }

    private int racedCommand(CountDownLatch start, Browser owner, String id, String etag, String action) throws Exception {
        if (!start.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("start timed out");
        if (action.equals("save")) return concurrentSave(start, owner, id, etag, "Winner");
        if (action.equals("tags")) return replaceTags(owner, id, etag, List.of("Films")).andReturn().getResponse().getStatus();
        return command(owner, id, etag, action.equals("pin") ? "PUT" : action.equals("unpin") ? "DELETE" : "POST",
                action.equals("unpin") ? "pin" : action).andReturn().getResponse().getStatus();
    }

    private org.springframework.test.web.servlet.ResultActions command(Browser browser, String id,
            String etag, String method, String action) throws Exception {
        var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(
                org.springframework.http.HttpMethod.valueOf(method), "/api/notes/{noteId}/" + action, id)
                .cookie(browser.cookie()).header("X-CSRF-TOKEN", browser.csrf());
        if (etag != null) request.header("If-Match", etag);
        return mvc.perform(request);
    }

    private MvcResult createNote(Browser browser) throws Exception {
        return mvc.perform(post("/api/notes").cookie(browser.cookie())
                .header("X-CSRF-TOKEN", browser.csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Original\",\"markdown\":\"Body\"}"))
                .andExpect(status().isCreated()).andReturn();
    }

    private org.springframework.test.web.servlet.ResultActions replaceTags(Browser browser,
            String id, String etag, List<String> tags) throws Exception {
        return mvc.perform(put("/api/notes/{noteId}/tags", id).cookie(browser.cookie())
                .header("X-CSRF-TOKEN", browser.csrf()).header("If-Match", etag)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(java.util.Map.of("tags", tags))));
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
