package org.notesknowledge.notes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.notesknowledge.identity.IdentitySessionPrincipal;
import org.notesknowledge.notes.spi.SourceRetirementPublicationConsequence;
import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
@org.springframework.context.annotation.Import(NotesEditorIntegrationTest.TimeConfiguration.class)
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
    @MockitoBean SourceRetirementPublicationConsequence publications;
    @MockitoBean org.notesknowledge.security.RateLimitPort rates;
    @Autowired AdjustableClock clock;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder passwords;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean NotesRepository noteRepository;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean(name = "googleOidcProtocolAdapter") Object oidcProvider;
    @Autowired org.springframework.context.ApplicationContext application;

    @BeforeEach
    void currentClock() {
        clock.offset = java.time.Duration.ZERO;
        org.mockito.Mockito.clearInvocations(oidcProvider);
        org.mockito.Mockito.when(rates.evaluate(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new org.notesknowledge.security.RateLimitPort.Allowed());
    }

    @Test
    void aiTransitionsHaveFreshGenerationsAndCurrentNoopsButNeverSaveContent() throws Exception {
        UUID user = account();
        Browser owner = browser(user, "ROLE_USER");
        var created = createNote(owner);
        String id = json.readTree(body(created)).get("id").asText();
        String initial = created.getResponse().getHeader("ETag");
        var noop = aiAccess(owner, id, initial, false).andExpect(status().isOk()).andReturn();
        assertThat(noop.getResponse().getHeader("ETag")).isEqualTo(initial);
        assertAiRow(id, false, 1, 1);
        var on = aiAccess(owner, id, initial, true).andExpect(status().isOk()).andReturn();
        String enabled = on.getResponse().getHeader("ETag");
        assertThat(enabled).isNotEqualTo(initial);
        assertThat(json.readTree(body(on)).get("aiEnabled").asBoolean()).isTrue();
        assertThat(body(on)).doesNotContain("aiGeneration", "status", "processing", "acknowledgement");
        assertThat(on.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertAiRow(id, true, 2, 2);
        aiAccess(owner, id, initial, true).andExpect(status().isPreconditionFailed());
        aiAccess(owner, id, enabled, true).andExpect(status().isOk());
        assertAiRow(id, true, 2, 2);
        var off = aiAccess(owner, id, enabled, false).andExpect(status().isOk()).andReturn();
        assertAiRow(id, false, 3, 3);
        var saved = mvc.perform(put("/api/notes/{noteId}", id).cookie(owner.cookie())
                .header("X-CSRF-TOKEN", owner.csrf()).header("If-Match", off.getResponse().getHeader("ETag"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Still usable\",\"markdown\":\"Saved with AI off\"}"))
                .andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(body(saved)).get("aiEnabled").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("select ai_generation from notes.note where note_id = ?::uuid", Long.class, id)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from pg_tables where schemaname='knowledge'", Integer.class)).isEqualTo(3);
        org.mockito.Mockito.verifyNoInteractions(oidcProvider);
        assertThat(application.containsBean("managedEmailDeliveryAdapter")).isFalse();
    }

    @Test
    void aiAccessRequiresOwnerCsrfTypedBooleanAndCurrentPrecondition() throws Exception {
        Browser owner = browser(account(), "ROLE_USER"), other = browser(account(), "ROLE_USER");
        var created = createNote(owner);
        String id = json.readTree(body(created)).get("id").asText();
        String etag = created.getResponse().getHeader("ETag");
        aiAccess(owner, id, null, true).andExpect(status().isPreconditionRequired());
        aiAccess(other, id, etag, true).andExpect(status().isNotFound());
        aiAccess(owner, UUID.randomUUID().toString(), etag, true).andExpect(status().isNotFound());
        mvc.perform(put("/api/notes/{noteId}/ai-access", id).cookie(owner.cookie())
                .header("If-Match", etag).contentType(MediaType.APPLICATION_JSON).content("{\"aiEnabled\":true}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/notes/{noteId}/ai-access", id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"aiEnabled\":true}")).andExpect(status().isForbidden()); // CSRF rejects first.
        for (String invalid : List.of("{}", "{\"aiEnabled\":null}", "{\"aiEnabled\":\"true\"}",
                "{\"aiEnabled\":1}")) {
            mvc.perform(put("/api/notes/{noteId}/ai-access", id).cookie(owner.cookie())
                    .header("If-Match", etag).header("X-CSRF-TOKEN", owner.csrf())
                    .contentType(MediaType.APPLICATION_JSON).content(invalid)).andExpect(status().isUnprocessableContent());
        }
        mvc.perform(put("/api/notes/{noteId}/ai-access", id).cookie(owner.cookie())
                .header("If-Match", etag).header("X-CSRF-TOKEN", owner.csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"aiEnabled\":true,\"ownerUserId\":\"ignored\"}"))
                .andExpect(status().isBadRequest());
        assertAiRow(id, false, 1, 1);
        jdbc.update("update notes.note set lifecycle_state = 'logically_deleted', deleted_at = now() where note_id = ?::uuid", id);
        aiAccess(owner, id, etag, true).andExpect(status().isNotFound());
    }

    @Test
    void bulkExplicitlyChangesApplicableExistingNotesWithoutChangingPreferenceOrDisclosingIds() throws Exception {
        UUID user = account();
        Browser owner = browser(user, "ROLE_USER"), other = browser(account(), "ROLE_USER");
        var created = createNote(owner);
        String id = json.readTree(body(created)).get("id").asText();
        String otherId = json.readTree(body(createNote(other))).get("id").asText();
        String deletedId = json.readTree(body(createNote(owner))).get("id").asText();
        var archived = createNote(owner);
        String archivedId = json.readTree(body(archived)).get("id").asText();
        command(owner, archivedId, archived.getResponse().getHeader("ETag"), "POST", "archive").andExpect(status().isOk());
        var trashed = createNote(owner);
        String trashedId = json.readTree(body(trashed)).get("id").asText();
        command(owner, trashedId, trashed.getResponse().getHeader("ETag"), "POST", "trash").andExpect(status().isOk());
        jdbc.update("update notes.note set lifecycle_state='logically_deleted', deleted_at=now() where note_id=?::uuid", deletedId);
        jdbc.update("insert into notes.note_preferences(user_id, default_ai_enabled, updated_at) values (?, false, now())", user);
        var selected = java.util.Map.of("aiEnabled", true, "scope", "selected", "confirm", true,
                "noteIds", List.of(id, archivedId, trashedId, otherId, deletedId, UUID.randomUUID().toString()));
        var result = bulk(owner, selected).andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(body(result)).properties()).hasSize(1);
        assertThat(json.readTree(body(result)).get("affectedCount").asLong()).isEqualTo(3);
        assertAiRow(id, true, 2, 2);
        assertAiRow(archivedId, true, 3, 2);
        assertAiRow(trashedId, true, 3, 2);
        assertAiRow(otherId, false, 1, 1);
        assertAiRow(deletedId, false, 1, 1);
        assertThat(jdbc.queryForObject("select default_ai_enabled from notes.note_preferences where user_id=?", Boolean.class, user)).isFalse();
        for (String unavailable : List.of(otherId, UUID.randomUUID().toString())) {
            assertThat(body(bulk(owner, java.util.Map.of("aiEnabled", true, "scope", "selected", "confirm", true,
                    "noteIds", List.of(unavailable))).andExpect(status().isOk()).andReturn()))
                    .isEqualTo("{\"affectedCount\":0}");
        }
        bulk(owner, selected).andExpect(status().isOk());
        assertAiRow(id, true, 2, 2);
        var off = bulk(owner, java.util.Map.of("aiEnabled", false, "scope", "allExisting", "confirm", true))
                .andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(body(off)).get("affectedCount").asInt()).isEqualTo(3);
        assertAiRow(id, false, 3, 3);
        assertAiRow(deletedId, false, 1, 1);
        assertThat(jdbc.queryForObject("select lifecycle_state from notes.note where note_id=?::uuid", String.class, archivedId)).isEqualTo("archived");
        assertThat(jdbc.queryForObject("select lifecycle_state from notes.note where note_id=?::uuid", String.class, trashedId)).isEqualTo("trashed");
        org.mockito.Mockito.verifyNoInteractions(oidcProvider);
        assertThat(application.containsBean("managedEmailDeliveryAdapter")).isFalse();
    }

    @Test
    void perNoteAiWorksInArchiveAndTrashWithoutChangingTheirLifecycle() throws Exception {
        Browser owner = browser(account(), "ROLE_USER");
        for (String lifecycle : List.of("archive", "trash")) {
            var created = createNote(owner);
            String id = json.readTree(body(created)).get("id").asText();
            var changed = command(owner, id, created.getResponse().getHeader("ETag"), "POST", lifecycle)
                    .andExpect(status().isOk()).andReturn();
            var on = aiAccess(owner, id, changed.getResponse().getHeader("ETag"), true).andExpect(status().isOk()).andReturn();
            assertAiRow(id, true, 3, 2);
            assertThat(json.readTree(body(on)).get("lifecycle").asText()).isEqualTo(lifecycle.equals("archive") ? "archived" : "trashed");
        }
    }

    @Test
    void bulkConfirmationSelectionBoundsAndRateFailuresAreFailSafe() throws Exception {
        Browser owner = browser(account(), "ROLE_USER");
        String id = json.readTree(body(createNote(owner))).get("id").asText();
        for (var invalid : List.of(java.util.Map.of("aiEnabled", true, "scope", "allExisting"),
                java.util.Map.of("aiEnabled", true, "scope", "allExisting", "confirm", false),
                java.util.Map.of("aiEnabled", "true", "scope", "allExisting", "confirm", true),
                java.util.Map.of("aiEnabled", true, "scope", "global", "confirm", true),
                java.util.Map.of("aiEnabled", true, "scope", "selected", "confirm", true, "noteIds", List.of()),
                java.util.Map.of("aiEnabled", true, "scope", "selected", "confirm", true, "noteIds", List.of(id, id)),
                java.util.Map.of("aiEnabled", true, "scope", "selected", "confirm", true, "noteIds", List.of("not-a-uuid")))) {
            bulk(owner, invalid).andExpect(status().isUnprocessableContent());
        }
        bulk(owner, java.util.Map.of("aiEnabled", true, "scope", "allExisting", "confirm", true, "ownerUserId", id))
                .andExpect(status().isBadRequest());
        var oversized = java.util.stream.IntStream.range(0, 1001).mapToObj(n -> UUID.randomUUID().toString()).toList();
        bulk(owner, java.util.Map.of("aiEnabled", true, "scope", "selected", "confirm", true, "noteIds", oversized))
                .andExpect(status().isPayloadTooLarge());
        mvc.perform(post("/api/notes/ai-access-bulk").cookie(owner.cookie()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"aiEnabled\":true,\"scope\":\"allExisting\",\"confirm\":true}"))
                .andExpect(status().isForbidden());
        org.mockito.Mockito.when(rates.evaluate(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new org.notesknowledge.security.RateLimitPort.Throttled(12));
        var throttled = bulk(owner, java.util.Map.of("aiEnabled", true, "scope", "allExisting", "confirm", true))
                .andExpect(status().isTooManyRequests()).andReturn();
        assertThat(throttled.getResponse().getHeader("Retry-After")).isEqualTo("12");
        org.mockito.Mockito.when(rates.evaluate(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new org.notesknowledge.security.RateLimitPort.ControlUnavailable());
        bulk(owner, java.util.Map.of("aiEnabled", true, "scope", "allExisting", "confirm", true))
                .andExpect(status().isServiceUnavailable());
        assertAiRow(id, false, 1, 1);
    }

    @Test
    void wholeExistingScopeUsesBoundedLockedBatchesAndCountsOnlyRealChanges() throws Exception {
        UUID user = account();
        Browser owner = browser(user, "ROLE_USER");
        jdbc.update("""
                insert into notes.note(note_id,owner_user_id,title,markdown,lifecycle_state,pinned,
                    ai_enabled,ai_generation,revision,created_at,updated_at)
                select uuidv7(), ?, 'Original', 'Body', 'active', false, false, 1, 1, now(), now()
                from generate_series(1,205)
                """, user);
        org.mockito.Mockito.clearInvocations(noteRepository, rates);
        var result = bulk(owner, java.util.Map.of("aiEnabled", true, "scope", "allExisting", "confirm", true))
                .andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(body(result)).get("affectedCount").asInt()).isEqualTo(205);
        org.mockito.Mockito.verify(noteRepository, org.mockito.Mockito.times(3)).lockAiAccessBatch(
                org.mockito.ArgumentMatchers.eq(user), org.mockito.ArgumentMatchers.eq(true),
                org.mockito.ArgumentMatchers.eq(List.of()), org.mockito.ArgumentMatchers.nullable(UUID.class),
                org.mockito.ArgumentMatchers.any(UUID.class), org.mockito.ArgumentMatchers.eq(100));
        var ids = org.mockito.ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(noteRepository, org.mockito.Mockito.times(3)).setAiAccess(
                org.mockito.ArgumentMatchers.eq(user), ids.capture(), org.mockito.ArgumentMatchers.eq(true),
                org.mockito.ArgumentMatchers.any(Instant.class));
        assertThat(ids.getAllValues().stream().map(List::size).toList()).containsExactly(100, 100, 5);
        assertThat(jdbc.queryForObject("select count(*) from notes.note where owner_user_id=? and ai_enabled and revision=2 and ai_generation=2", Integer.class, user)).isEqualTo(205);
        org.mockito.Mockito.verify(rates, org.mockito.Mockito.times(9)).evaluate(org.mockito.ArgumentMatchers.any());
        assertThat(body(bulk(owner, java.util.Map.of("aiEnabled", true, "scope", "allExisting", "confirm", true))
                .andExpect(status().isOk()).andReturn())).isEqualTo("{\"affectedCount\":0}");
    }

    @Test
    void bulkFailureRollsBackItsBatchAndNeverReportsPartialSuccess() throws Exception {
        UUID user = account();
        Browser owner = browser(user, "ROLE_USER");
        String id = json.readTree(body(createNote(owner))).get("id").asText();
        org.mockito.Mockito.doAnswer(invocation -> {
            invocation.callRealMethod();
            throw ApiFailureException.of(ApiFailureException.Kind.SERVICE_UNAVAILABLE);
        }).when(noteRepository).setAiAccess(org.mockito.ArgumentMatchers.eq(user),
                org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.eq(true), org.mockito.ArgumentMatchers.any());
        bulk(owner, java.util.Map.of("aiEnabled", true, "scope", "allExisting", "confirm", true))
                .andExpect(status().isServiceUnavailable());
        assertAiRow(id, false, 1, 1);
    }

    @Test
    void lateBatchThrottleReportsErrorAndRetryCountsOnlyRemainingChanges() throws Exception {
        UUID user = account();
        Browser owner = browser(user, "ROLE_USER");
        jdbc.update("""
                insert into notes.note(note_id,owner_user_id,title,markdown,lifecycle_state,pinned,
                    ai_enabled,ai_generation,revision,created_at,updated_at)
                select uuidv7(), ?, 'Original', 'Body', 'active', false, false, 1, 1, now(), now()
                from generate_series(1,205)
                """, user);
        var evaluations = new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.when(rates.evaluate(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return evaluations.incrementAndGet() <= 3 ? new org.notesknowledge.security.RateLimitPort.Allowed()
                    : new org.notesknowledge.security.RateLimitPort.Throttled(10);
        });
        var failed = bulk(owner, java.util.Map.of("aiEnabled", true, "scope", "allExisting", "confirm", true))
                .andExpect(status().isTooManyRequests()).andReturn();
        assertThat(body(failed)).doesNotContain("affectedCount");
        assertThat(jdbc.queryForObject("select count(*) from notes.note where owner_user_id=? and ai_enabled and revision=2", Integer.class, user)).isEqualTo(100);
        org.mockito.Mockito.when(rates.evaluate(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new org.notesknowledge.security.RateLimitPort.Allowed();
        });
        var retried = bulk(owner, java.util.Map.of("aiEnabled", true, "scope", "allExisting", "confirm", true))
                .andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(body(retried)).get("affectedCount").asInt()).isEqualTo(105);
        assertThat(jdbc.queryForObject("select count(*) from notes.note where owner_user_id=? and ai_enabled and revision=2 and ai_generation=2", Integer.class, user)).isEqualTo(205);
    }

    @Test
    void bulkWaitsForUnrelatedCurrentWriteAndNeverOverwritesIt() throws Exception {
        UUID user = account();
        Browser owner = browser(user, "ROLE_USER");
        String id = json.readTree(body(createNote(owner))).get("id").asText();
        CountDownLatch locked = new CountDownLatch(1), batchReached = new CountDownLatch(1), release = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            batchReached.countDown();
            return invocation.callRealMethod();
        }).when(noteRepository).lockAiAccessBatch(org.mockito.ArgumentMatchers.eq(user), org.mockito.ArgumentMatchers.eq(true),
                org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.nullable(UUID.class),
                org.mockito.ArgumentMatchers.any(UUID.class), org.mockito.ArgumentMatchers.eq(100));
        try (var workers = Executors.newFixedThreadPool(2)) {
            var save = workers.submit(() -> {
                var tx = new org.springframework.transaction.support.TransactionTemplate(
                        new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
                tx.executeWithoutResult(status -> {
                    jdbc.queryForObject("select note_id from notes.note where note_id=?::uuid for update", UUID.class, id);
                    locked.countDown();
                    try { if (!release.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("release timed out"); }
                    catch (InterruptedException failure) { throw new IllegalStateException(failure); }
                    jdbc.update("update notes.note set title='Concurrent', markdown='Saved current body', pinned=true, revision=revision+1 where note_id=?::uuid", id);
                    jdbc.update("insert into notes.note_tag(note_id,owner_user_id,normalized_label,display_label,created_at) values (?::uuid,?,'films','Films',now())", id, user);
                });
            });
            assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue();
            var change = workers.submit(() -> bulk(owner, java.util.Map.of("aiEnabled", true, "scope", "allExisting", "confirm", true))
                    .andExpect(status().isOk()).andReturn());
            assertThat(batchReached.await(30, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            save.get(30, TimeUnit.SECONDS);
            assertThat(json.readTree(body(change.get(30, TimeUnit.SECONDS))).get("affectedCount").asInt()).isEqualTo(1);
            var current = jdbc.queryForMap("select title, markdown, pinned, revision, lifecycle_state from notes.note where note_id=?::uuid", id);
            assertThat(current).containsEntry("title", "Concurrent").containsEntry("markdown", "Saved current body")
                    .containsEntry("pinned", true).containsEntry("revision", 3L).containsEntry("lifecycle_state", "active");
            assertThat(jdbc.queryForObject("select display_label from notes.note_tag where note_id=?::uuid", String.class, id)).isEqualTo("Films");
        } finally { release.countDown(); }
    }

    private org.springframework.test.web.servlet.ResultActions aiAccess(Browser browser, String id, String etag, boolean enabled) throws Exception {
        var request = put("/api/notes/{noteId}/ai-access", id).cookie(browser.cookie())
                .header("X-CSRF-TOKEN", browser.csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("aiEnabled", enabled)));
        if (etag != null) request.header("If-Match", etag);
        return mvc.perform(request);
    }

    private org.springframework.test.web.servlet.ResultActions bulk(Browser browser, java.util.Map<String, ?> body) throws Exception {
        return mvc.perform(post("/api/notes/ai-access-bulk").cookie(browser.cookie())
                .header("X-CSRF-TOKEN", browser.csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    private void assertAiRow(String id, boolean enabled, long revision, long generation) {
        assertThat(jdbc.queryForMap("select ai_enabled,revision,ai_generation,title,markdown from notes.note where note_id=?::uuid", id))
                .containsEntry("ai_enabled", enabled).containsEntry("revision", revision).containsEntry("ai_generation", generation)
                .containsEntry("title", "Original").containsEntry("markdown", "Body");
    }

    @Test
    void permanentDeletionDeniesImmediatelyButRetainsSavedRowAndTags() throws Exception {
        UUID user = account();
        Browser owner = recentBrowser(user);
        MvcResult original = createNote(owner);
        String id = json.readTree(body(original)).get("id").asText();
        MvcResult tagged = replaceTags(owner, id, original.getResponse().getHeader("ETag"),
                List.of("Retained synthetic tag")).andReturn();
        MvcResult trashed = command(owner, id, tagged.getResponse().getHeader("ETag"), "POST", "trash").andReturn();
        String etag = trashed.getResponse().getHeader("ETag");
        long revision = jdbc.queryForObject("select revision from notes.note where note_id = ?::uuid", Long.class, id);
        long generation = jdbc.queryForObject("select ai_generation from notes.note where note_id = ?::uuid", Long.class, id);
        var result = deleteNote(owner, id, etag, "{\"confirmPermanentDelete\":true}")
                .andExpect(status().isNoContent()).andReturn();
        assertThat(body(result)).isEmpty();
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(jdbc.queryForObject("select count(*) from notes.note where note_id = ?::uuid", Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from notes.note_tag where note_id = ?::uuid", Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select title from notes.note where note_id = ?::uuid", String.class, id)).isEqualTo("Original");
        assertThat(jdbc.queryForObject("select markdown from notes.note where note_id = ?::uuid", String.class, id)).isEqualTo("Body");
        assertThat(jdbc.queryForObject("select lifecycle_state from notes.note where note_id = ?::uuid", String.class, id)).isEqualTo("logically_deleted");
        assertThat(jdbc.queryForObject("select revision from notes.note where note_id = ?::uuid", Long.class, id)).isEqualTo(revision + 1);
        assertThat(jdbc.queryForObject("select ai_generation from notes.note where note_id = ?::uuid", Long.class, id)).isEqualTo(generation + 1);
        assertThat(jdbc.queryForObject("select deleted_at is not null and pre_trash_state is null and trashed_at is null from notes.note where note_id = ?::uuid", Boolean.class, id)).isTrue();
        mvc.perform(get("/api/notes/{noteId}", id).cookie(owner.cookie())).andExpect(status().isNotFound());
        for (String state : List.of("active", "archived", "trashed")) {
            assertThat(json.readTree(body(mvc.perform(get("/api/notes").queryParam("lifecycle", state)
                    .cookie(owner.cookie())).andReturn())).get("items").isEmpty()).isTrue();
        }
        mvc.perform(put("/api/notes/{noteId}", id).cookie(owner.cookie()).header("X-CSRF-TOKEN", owner.csrf())
                .header("If-Match", etag).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"No\",\"markdown\":\"No\"}")).andExpect(status().isNotFound());
        replaceTags(owner, id, etag, List.of()).andExpect(status().isNotFound());
        for (String name : List.of("archive", "return-from-archive", "trash", "restore")) {
            command(owner, id, etag, "POST", name).andExpect(status().isNotFound());
        }
        command(owner, id, etag, "PUT", "pin").andExpect(status().isNotFound());
        command(owner, id, etag, "DELETE", "pin").andExpect(status().isNotFound());
        deleteNote(owner, id, etag, "{\"confirmPermanentDelete\":true}").andExpect(status().isNotFound());
    }

    @Test
    void permanentDeleteAndRestoreCannotBothCommitFromOneRevision() throws Exception {
        Browser owner = recentBrowser(account());
        var created = createNote(owner);
        String id = json.readTree(body(created)).get("id").asText();
        var trashed = command(owner, id, created.getResponse().getHeader("ETag"), "POST", "trash").andReturn();
        String etag = trashed.getResponse().getHeader("ETag");
        CountDownLatch start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var deletion = workers.submit(() -> {
                if (!start.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("start timed out");
                return deleteNote(owner, id, etag, "{\"confirmPermanentDelete\":true}").andReturn().getResponse().getStatus();
            });
            var restoration = workers.submit(() -> {
                if (!start.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("start timed out");
                return command(owner, id, etag, "POST", "restore").andReturn().getResponse().getStatus();
            });
            start.countDown();
            int deleted = deletion.get(30, TimeUnit.SECONDS), restored = restoration.get(30, TimeUnit.SECONDS);
            if (deleted == 204) assertThat(restored).isEqualTo(404);
            else { assertThat(deleted).isEqualTo(412); assertThat(restored).isEqualTo(200); }
            assertThat(jdbc.queryForObject("select revision from notes.note where note_id = ?::uuid", Long.class, id)).isEqualTo(3L);
            assertThat(jdbc.queryForObject("select ai_generation from notes.note where note_id = ?::uuid", Long.class, id)).isEqualTo(deleted == 204 ? 2L : 1L);
            assertThat(jdbc.queryForObject("select lifecycle_state from notes.note where note_id = ?::uuid", String.class, id)).isEqualTo(deleted == 204 ? "logically_deleted" : "active");
        }
    }

    @Test
    void permanentDeletionRequiresTrashConfirmationOwnerCsrfAndCurrentIfMatch() throws Exception {
        Browser owner = recentBrowser(account()), other = recentBrowser(account());
        MvcResult created = createNote(owner);
        String id = json.readTree(body(created)).get("id").asText();
        String original = created.getResponse().getHeader("ETag");
        deleteNote(owner, id, original, "{\"confirmPermanentDelete\":true}").andExpect(status().isConflict());
        var archived = command(owner, id, original, "POST", "archive").andReturn();
        deleteNote(owner, id, archived.getResponse().getHeader("ETag"), "{\"confirmPermanentDelete\":true}").andExpect(status().isConflict());
        var trashed = command(owner, id, archived.getResponse().getHeader("ETag"), "POST", "trash").andReturn();
        String etag = trashed.getResponse().getHeader("ETag");
        mvc.perform(delete("/api/notes/{noteId}", id).cookie(owner.cookie()).header("If-Match", etag)
                .contentType(MediaType.APPLICATION_JSON).content("{\"confirmPermanentDelete\":true}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/notes/{noteId}", id)
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"confirmPermanentDelete\":true}"))
                .andExpect(status().isUnauthorized());
        Browser pending = browser(account(), "ROLE_MFA_PENDING");
        deleteNote(pending, id, etag, "{\"confirmPermanentDelete\":true}").andExpect(status().isForbidden());
        deleteNote(other, id, etag, "{\"confirmPermanentDelete\":true}").andExpect(status().isNotFound());
        deleteNote(owner, UUID.randomUUID().toString(), etag, "{\"confirmPermanentDelete\":true}").andExpect(status().isNotFound());
        deleteNote(owner, id, null, "{\"confirmPermanentDelete\":true}").andExpect(status().isPreconditionRequired());
        deleteNote(owner, id, original, "{\"confirmPermanentDelete\":true}").andExpect(status().isPreconditionFailed());
        for (String input : List.of("{}", "{\"confirmPermanentDelete\":false}")) {
            deleteNote(owner, id, etag, input).andExpect(status().isUnprocessableEntity());
        }
        for (String input : List.of("{\"confirmPermanentDelete\":null}", "{\"confirmPermanentDelete\":\"true\"}",
                "{\"confirmPermanentDelete\":true,\"ownerUserId\":\"ignored\"}",
                "{\"confirmPermanentDelete\":true,\"confirmPublicationUnpublish\":null}")) {
            deleteNote(owner, id, etag, input).andExpect(status().isBadRequest());
        }
        deleteNote(owner, id, etag, null).andExpect(status().isUnprocessableEntity());
        assertThat(jdbc.queryForObject("select lifecycle_state from notes.note where note_id = ?::uuid", String.class, id)).isEqualTo("trashed");
    }

    @Test
    void permanentDeletionUsesCurrentPersistedRecentProofAndRejectsMissingOrExpiredProof() throws Exception {
        UUID user = account();
        Browser missing = browser(user, "ROLE_USER");
        MvcResult created = createNote(missing);
        String id = json.readTree(body(created)).get("id").asText();
        var trashed = command(missing, id, created.getResponse().getHeader("ETag"), "POST", "trash").andReturn();
        String etag = trashed.getResponse().getHeader("ETag");
        var noProof = deleteNote(missing, id, etag, "{\"confirmPermanentDelete\":true}").andExpect(status().isForbidden()).andReturn();
        assertThat(json.readTree(body(noProof)).get("code").asText()).isEqualTo("recent_authentication_required");
        Browser fresh = recentBrowser(user);
        clock.offset = java.time.Duration.ofMinutes(16);
        var expired = deleteNote(fresh, id, etag, "{\"confirmPermanentDelete\":true}").andExpect(status().isForbidden()).andReturn();
        assertThat(json.readTree(body(expired)).get("code").asText()).isEqualTo("recent_authentication_required");
        assertThat(jdbc.queryForObject("select revision from notes.note where note_id = ?::uuid", Long.class, id)).isEqualTo(2L);
        currentClock();
        deleteNote(fresh, id, etag, "{\"confirmPermanentDelete\":true}").andExpect(status().isNoContent());
    }

    @Test
    void permanentDeletionRequiresExplicitPublicationDenialBeforeSourceTransition() throws Exception {
        UUID user = account();
        Browser owner = recentBrowser(user);
        var created = createNote(owner);
        String id = json.readTree(body(created)).get("id").asText();
        var trashed = command(owner, id, created.getResponse().getHeader("ETag"), "POST", "trash").andReturn();
        String etag = trashed.getResponse().getHeader("ETag");
        org.mockito.Mockito.when(publications.hasActiveSourcePublication(user, UUID.fromString(id))).thenReturn(true);
        var required = deleteNote(owner, id, etag, "{\"confirmPermanentDelete\":true}").andExpect(status().isConflict()).andReturn();
        assertThat(json.readTree(body(required)).get("code").asText()).isEqualTo("publication_consequence_required");
        org.mockito.Mockito.verify(publications, org.mockito.Mockito.never()).makeIneligible(user, UUID.fromString(id));
        org.mockito.Mockito.doAnswer(call -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(jdbc.queryForObject("select lifecycle_state from notes.note where note_id = ?::uuid", String.class, id)).isEqualTo("trashed");
            assertThat(jdbc.queryForObject("select deleted_at is null from notes.note where note_id = ?::uuid", Boolean.class, id)).isTrue();
            return null;
        }).when(publications).makeIneligible(user, UUID.fromString(id));
        deleteNote(owner, id, etag, "{\"confirmPermanentDelete\":true,\"confirmPublicationUnpublish\":true}").andExpect(status().isNoContent());
        org.mockito.Mockito.verify(publications).makeIneligible(user, UUID.fromString(id));
    }

    @Test
    void failedPermanentDeletionConsequenceRollsBackSavedContentMetadataRevisionAndGeneration() throws Exception {
        UUID user = account();
        Browser owner = recentBrowser(user);
        var created = createNote(owner);
        String id = json.readTree(body(created)).get("id").asText();
        var trashed = command(owner, id, created.getResponse().getHeader("ETag"), "POST", "trash").andReturn();
        var before = jdbc.queryForMap("select * from notes.note where note_id = ?::uuid", id);
        org.mockito.Mockito.when(publications.hasActiveSourcePublication(user, UUID.fromString(id))).thenReturn(true);
        org.mockito.Mockito.doAnswer(call -> {
            jdbc.update("update notes.note set title = 'Synthetic consequence effect' where note_id = ?::uuid", id);
            throw ApiFailureException.of(ApiFailureException.Kind.SERVICE_UNAVAILABLE);
        }).when(publications).makeIneligible(user, UUID.fromString(id));
        deleteNote(owner, id, trashed.getResponse().getHeader("ETag"),
                "{\"confirmPermanentDelete\":true,\"confirmPublicationUnpublish\":true}").andExpect(status().isServiceUnavailable());
        assertThat(jdbc.queryForMap("select * from notes.note where note_id = ?::uuid", id)).isEqualTo(before);
    }

    @org.springframework.boot.test.context.TestConfiguration
    static class TimeConfiguration {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        AdjustableClock adjustableClock() { return new AdjustableClock(); }
    }

    static final class AdjustableClock extends Clock {
        volatile java.time.Duration offset = java.time.Duration.ZERO;
        @Override public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return Clock.offset(Clock.system(zone), offset); }
        @Override public Instant instant() { return Instant.now().plus(offset); }
    }

    private Browser recentBrowser(UUID user) throws Exception {
        String syntheticPassword = "Synthetic-note-delete-password-123!";
        jdbc.update("update identity.account set password_verifier = ? where user_id = ?", passwords.encode(syntheticPassword), user);
        Browser browser = browser(user, "ROLE_USER");
        mvc.perform(post("/api/auth/reauth/password").cookie(browser.cookie()).header("X-CSRF-TOKEN", browser.csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(java.util.Map.of("password", syntheticPassword))))
                .andExpect(status().isNoContent());
        return browser;
    }

    private org.springframework.test.web.servlet.ResultActions deleteNote(Browser browser, String id, String etag, String input) throws Exception {
        var request = delete("/api/notes/{noteId}", id).cookie(browser.cookie()).header("X-CSRF-TOKEN", browser.csrf());
        if (etag != null) request.header("If-Match", etag);
        if (input != null) request.contentType(MediaType.APPLICATION_JSON).content(input);
        return mvc.perform(request);
    }

    @Test
    void trashAndRestorePreserveOriginalLifecycleMetadataContentAndAdvanceEtags() throws Exception {
        for (String previous : List.of("active", "archived")) {
            Browser owner = browser(account(), "ROLE_USER");
            MvcResult original = createNote(owner);
            String id = json.readTree(body(original)).get("id").asText();
            if (previous.equals("archived")) original = command(owner, id,
                    original.getResponse().getHeader("ETag"), "POST", "archive").andReturn();
            String before = original.getResponse().getHeader("ETag");
            long revision = jdbc.queryForObject("select revision from notes.note where note_id = ?::uuid", Long.class, id);
            MvcResult trashed = command(owner, id, before, "POST", "trash").andExpect(status().isOk()).andReturn();
            String trashEtag = trashed.getResponse().getHeader("ETag");
            assertThat(trashEtag).isNotEqualTo(before);
            assertThat(json.readTree(body(trashed)).get("lifecycle").asText()).isEqualTo("trashed");
            assertThat(jdbc.queryForObject("select pre_trash_state from notes.note where note_id = ?::uuid", String.class, id)).isEqualTo(previous);
            assertThat(jdbc.queryForObject("select trashed_at is not null from notes.note where note_id = ?::uuid", Boolean.class, id)).isTrue();
            assertThat(jdbc.queryForObject("select revision from notes.note where note_id = ?::uuid", Long.class, id)).isEqualTo(revision + 1);
            assertThat(trashed.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
            command(owner, id, before, "POST", "restore").andExpect(status().isPreconditionFailed());
            command(owner, id, trashEtag, "POST", "trash").andExpect(status().isConflict());
            mvc.perform(put("/api/notes/{noteId}", id).cookie(owner.cookie()).header("X-CSRF-TOKEN", owner.csrf())
                    .header("If-Match", trashEtag).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"title\":\"Unsaved\",\"markdown\":\"Never saved\"}"))
                    .andExpect(status().isConflict());
            replaceTags(owner, id, trashEtag, List.of("Forbidden")).andExpect(status().isConflict());
            command(owner, id, trashEtag, "PUT", "pin").andExpect(status().isConflict());
            command(owner, id, trashEtag, "POST", "archive").andExpect(status().isConflict());
            MvcResult restored = command(owner, id, trashEtag, "POST", "restore").andExpect(status().isOk()).andReturn();
            assertThat(restored.getResponse().getHeader("ETag")).isNotEqualTo(trashEtag);
            assertThat(json.readTree(body(restored)).get("lifecycle").asText()).isEqualTo(previous);
            assertThat(json.readTree(body(restored)).get("title").asText()).isEqualTo("Original");
            assertThat(json.readTree(body(restored)).get("markdown").asText()).isEqualTo("Body");
            assertThat(jdbc.queryForObject("select pre_trash_state is null and trashed_at is null from notes.note where note_id = ?::uuid", Boolean.class, id)).isTrue();
            assertThat(jdbc.queryForObject("select revision from notes.note where note_id = ?::uuid", Long.class, id)).isEqualTo(revision + 2);
            command(owner, id, restored.getResponse().getHeader("ETag"), "POST", "restore").andExpect(status().isConflict());
            for (String view : List.of("active", "archived", "trashed")) {
                var rows = json.readTree(body(mvc.perform(get("/api/notes?lifecycle=" + view).cookie(owner.cookie())).andReturn())).get("items");
                assertThat(java.util.stream.StreamSupport.stream(rows.spliterator(), false)
                        .anyMatch(row -> row.get("id").asText().equals(id))).isEqualTo(view.equals(previous));
            }
        }
        org.mockito.Mockito.verify(publications, org.mockito.Mockito.times(2))
                .hasActiveSourcePublication(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(publications, org.mockito.Mockito.never())
                .makeIneligible(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void trashAndRestoreRequireFullOwnerCsrfCurrentValidatorAndNarrowBodies() throws Exception {
        Browser owner = browser(account(), "ROLE_USER");
        Browser other = browser(account(), "ROLE_USER");
        Browser pending = browser(account(), "ROLE_MFA_PENDING");
        MvcResult created = createNote(owner);
        String id = json.readTree(body(created)).get("id").asText();
        String etag = created.getResponse().getHeader("ETag");
        for (String action : List.of("trash", "restore")) {
            command(other, id, null, "POST", action).andExpect(status().isNotFound());
            command(owner, UUID.randomUUID().toString(), null, "POST", action).andExpect(status().isNotFound());
            command(owner, id, null, "POST", action).andExpect(status().isPreconditionRequired());
            command(pending, id, etag, "POST", action).andExpect(status().isForbidden());
            mvc.perform(post("/api/notes/{noteId}/" + action, id).cookie(owner.cookie()).header("If-Match", etag))
                    .andExpect(status().isForbidden());
            for (String bad : List.of("{\"title\":\"Not saved\"}", "{\"confirmPublicationUnpublish\":\"true\"}",
                    "{\"keepPublished\":true}", "{\"confirmPublicationUnpublish\":null}")) {
                mvc.perform(post("/api/notes/{noteId}/" + action, id).cookie(owner.cookie())
                        .header("X-CSRF-TOKEN", owner.csrf()).header("If-Match", etag)
                        .contentType(MediaType.APPLICATION_JSON).content(bad)).andExpect(status().isBadRequest());
            }
        }
        command(owner, id, etag, "POST", "restore").andExpect(status().isConflict());
        MvcResult trashed = command(owner, id, etag, "POST", "trash").andExpect(status().isOk()).andReturn();
        String current = trashed.getResponse().getHeader("ETag");
        command(owner, id, etag, "POST", "trash").andExpect(status().isPreconditionFailed());
        command(owner, id, null, "POST", "restore").andExpect(status().isPreconditionRequired());
        // A malformed legacy trash row is not a recoverable Note; no destination is guessed.
        jdbc.update("update notes.note set pre_trash_state = null where note_id = ?::uuid", id);
        command(owner, id, current, "POST", "restore").andExpect(status().isConflict());
        jdbc.update("update notes.note set lifecycle_state = 'logically_deleted', pre_trash_state = null, trashed_at = null, deleted_at = now() where note_id = ?::uuid", id);
        for (String action : List.of("trash", "restore")) command(owner, id, current, "POST", action).andExpect(status().isNotFound());
    }

    @Test
    void publicationConsequenceRequiresExplicitConfirmationAndRunsBeforeSourceTransition() throws Exception {
        UUID ownerId = account();
        Browser owner = browser(ownerId, "ROLE_USER");
        MvcResult created = createNote(owner);
        String id = json.readTree(body(created)).get("id").asText();
        String etag = created.getResponse().getHeader("ETag");
        org.mockito.Mockito.when(publications.hasActiveSourcePublication(ownerId, UUID.fromString(id))).thenReturn(true);
        MvcResult rejected = command(owner, id, etag, "POST", "trash").andExpect(status().isConflict()).andReturn();
        assertThat(json.readTree(body(rejected)).get("code").asText()).isEqualTo("publication_consequence_required");
        assertThat(jdbc.queryForObject("select revision from notes.note where note_id = ?::uuid", Long.class, id)).isEqualTo(1L);
        org.mockito.Mockito.verify(publications, org.mockito.Mockito.never()).makeIneligible(ownerId, UUID.fromString(id));
        org.mockito.Mockito.doAnswer(call -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(jdbc.queryForObject("select lifecycle_state from notes.note where note_id = ?::uuid", String.class, id)).isEqualTo("active");
            assertThat(jdbc.queryForObject("select revision from notes.note where note_id = ?::uuid", Long.class, id)).isEqualTo(1L);
            return null;
        }).when(publications).makeIneligible(ownerId, UUID.fromString(id));
        mvc.perform(post("/api/notes/{noteId}/trash", id).cookie(owner.cookie())
                .header("X-CSRF-TOKEN", owner.csrf()).header("If-Match", etag)
                .contentType(MediaType.APPLICATION_JSON).content("{\"confirmPublicationUnpublish\":false}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/notes/{noteId}/trash", id).cookie(owner.cookie())
                .header("X-CSRF-TOKEN", owner.csrf()).header("If-Match", etag)
                .contentType(MediaType.APPLICATION_JSON).content("{\"confirmPublicationUnpublish\":true}"))
                .andExpect(status().isOk());
        org.mockito.Mockito.verify(publications).makeIneligible(ownerId, UUID.fromString(id));
        assertThat(jdbc.queryForObject("select lifecycle_state from notes.note where note_id = ?::uuid", String.class, id)).isEqualTo("trashed");
    }

    @Test
    void failedPublicationConsequenceRollsBackTheEntireRetirementTransaction() throws Exception {
        UUID ownerId = account();
        Browser owner = browser(ownerId, "ROLE_USER");
        MvcResult created = createNote(owner);
        String id = json.readTree(body(created)).get("id").asText();
        String etag = created.getResponse().getHeader("ETag");
        org.mockito.Mockito.when(publications.hasActiveSourcePublication(ownerId, UUID.fromString(id))).thenReturn(true);
        org.mockito.Mockito.doAnswer(call -> {
            // Controlled synthetic DB effect proves participation/rollback, not real Publication state.
            jdbc.update("update notes.note set title = 'Synthetic consequence effect' where note_id = ?::uuid", id);
            throw ApiFailureException.of(ApiFailureException.Kind.SERVICE_UNAVAILABLE);
        }).when(publications).makeIneligible(ownerId, UUID.fromString(id));
        mvc.perform(post("/api/notes/{noteId}/trash", id).cookie(owner.cookie())
                .header("X-CSRF-TOKEN", owner.csrf()).header("If-Match", etag)
                .contentType(MediaType.APPLICATION_JSON).content("{\"confirmPublicationUnpublish\":true}"))
                .andExpect(status().isServiceUnavailable());
        assertThat(jdbc.queryForObject("select title from notes.note where note_id = ?::uuid", String.class, id)).isEqualTo("Original");
        assertThat(jdbc.queryForObject("select revision from notes.note where note_id = ?::uuid", Long.class, id)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select lifecycle_state from notes.note where note_id = ?::uuid", String.class, id)).isEqualTo("active");
        assertThat(jdbc.queryForObject("select pre_trash_state is null and trashed_at is null from notes.note where note_id = ?::uuid", Boolean.class, id)).isTrue();
    }

    @Test
    void trashCompetesWithSaveTagsPinAndArchiveThroughOneCurrentRevision() throws Exception {
        for (String competitor : List.of("save", "tags", "pin", "archive")) {
            Browser owner = browser(account(), "ROLE_USER");
            MvcResult created = createNote(owner);
            String id = json.readTree(body(created)).get("id").asText();
            String etag = created.getResponse().getHeader("ETag");
            CountDownLatch start = new CountDownLatch(1);
            try (var workers = Executors.newFixedThreadPool(2)) {
                var trash = workers.submit(() -> racedCommand(start, owner, id, etag, "trash"));
                var competing = workers.submit(() -> racedCommand(start, owner, id, etag, competitor));
                start.countDown();
                int trashStatus = trash.get(30, TimeUnit.SECONDS), otherStatus = competing.get(30, TimeUnit.SECONDS);
                assertThat(List.of(trashStatus, otherStatus)).containsExactlyInAnyOrder(200, 412);
                assertThat(jdbc.queryForObject("select revision from notes.note where note_id = ?::uuid", Long.class, id)).isEqualTo(2L);
                assertThat(jdbc.queryForObject("select lifecycle_state from notes.note where note_id = ?::uuid", String.class, id))
                        .isEqualTo(trashStatus == 200 ? "trashed" : competitor.equals("archive") ? "archived" : "active");
                assertThat(jdbc.queryForObject("select title from notes.note where note_id = ?::uuid", String.class, id))
                        .isEqualTo(otherStatus == 200 && competitor.equals("save") ? "Winner" : "Original");
                assertThat(jdbc.queryForObject("select markdown from notes.note where note_id = ?::uuid", String.class, id))
                        .isEqualTo(otherStatus == 200 && competitor.equals("save") ? "saved" : "Body");
                assertThat(jdbc.queryForObject("select pinned from notes.note where note_id = ?::uuid", Boolean.class, id)).isEqualTo(otherStatus == 200 && competitor.equals("pin"));
                assertThat(jdbc.queryForObject("select count(*) from notes.note_tag where note_id = ?::uuid", Integer.class, id)).isEqualTo(otherStatus == 200 && competitor.equals("tags") ? 1 : 0);
                assertThat(jdbc.queryForObject("select pre_trash_state from notes.note where note_id = ?::uuid", String.class, id)).isEqualTo(trashStatus == 200 ? "active" : null);
                assertThat(jdbc.queryForObject("select trashed_at is not null from notes.note where note_id = ?::uuid", Boolean.class, id)).isEqualTo(trashStatus == 200);
            }
        }
    }

    @Test
    void migrationPreservesNotesRelationsAndEnforcesOwnerAndRowShape() {
        assertThat(jdbc.queryForObject("""
                select count(*) from information_schema.tables
                where table_schema in ('identity','profile','notes','knowledge',
                    'publishing','discovery','moderation') and table_type = 'BASE TABLE'
                """, Integer.class)).isEqualTo(22);
        assertThat(jdbc.queryForList("""
                select table_name from information_schema.tables
                where table_schema = 'notes' and table_type = 'BASE TABLE'
                order by table_name
                """, String.class)).containsExactly("attachment", "note", "note_preferences", "note_tag", "note_version", "note_version_hold");
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
