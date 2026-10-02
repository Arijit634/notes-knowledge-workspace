package org.notesknowledge.notes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.notesknowledge.identity.IdentitySessionPrincipal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
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
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@Tag("DATABASE") @Tag("API") @Tag("SECURITY")
@Testcontainers @SpringBootTest @AutoConfigureMockMvc
@org.springframework.context.annotation.Import(NotesEditorIntegrationTest.TimeConfiguration.class)
class NoteVersionIntegrationTest {
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18-trixie")
            .withDatabaseName("note_versions").withUsername("notes_migrator")
            .withPassword("synthetic-version-migrator-password");

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("notes.cursor.active.version", () -> "test1");
        registry.add("notes.cursor.active.key-base64", () -> "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=");
        registry.add("identity.rate.key-base64", () -> "AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=");
        registry.add("notes.checkpoints.max-unheld", () -> 2);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired SessionRepository<? extends Session> sessions;
    @Autowired NotesEditorIntegrationTest.AdjustableClock clock;
    @Autowired NoteVersionService versions;
    @MockitoSpyBean NoteVersionRepository history;
    @Autowired NotesService notes;
    @MockitoBean org.notesknowledge.security.RateLimitPort rates;
    @MockitoSpyBean NotesRepository repository;

    @BeforeEach void setup() {
        clock.offset = Duration.ZERO;
        org.mockito.Mockito.when(rates.evaluate(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new org.notesknowledge.security.RateLimitPort.Allowed());
    }

    @Test void policyCapturesOnlyPreviouslySavedContentAtCadenceNotEverySave() throws Exception {
        Browser owner = browser(account(), "ROLE_USER");
        Core core = create(owner);
        core = save(owner, core, "First saved", "First body");
        assertThat(count(core.id())).isZero();
        clock.offset = Duration.ofMinutes(5);
        core = save(owner, core, "Second saved", "Second body");
        var captured = row(onlyVersion(core.id()));
        assertThat(onlyVersion(core.id()).version()).isEqualTo(7);
        assertThat(captured.get("title")).isEqualTo("First saved");
        assertThat(captured.get("markdown")).isEqualTo("First body");
        assertThat(captured.get("source_revision")).isEqualTo(2L);
        assertThat(captured.get("checkpoint_kind")).isEqualTo("policy");
        core = save(owner, core, "Third saved", "Third body");
        assertThat(count(core.id())).isEqualTo(1);
        clock.offset = Duration.ofMinutes(10);
        core = save(owner, core, "Fourth saved", "Fourth body");
        assertThat(count(core.id())).isEqualTo(2);
        assertThat(jdbc.queryForList("select markdown from notes.note_version where note_id=?", String.class, core.id()))
                .containsExactlyInAnyOrder("First body", "Third body").doesNotContain("Fourth body");
    }

    @Test void repeatedSavedContentIsReusedInsteadOfCreatingDuplicateVersions() throws Exception {
        UUID owner = account(); Core core = notesCore(notes.create(owner, "Original", "Body", false));
        clock.offset = Duration.ofMinutes(5);
        core = notesCore(notes.save(owner, core.id(), core.etag(), "Other", "Other body"));
        clock.offset = Duration.ofMinutes(10);
        core = notesCore(notes.save(owner, core.id(), core.etag(), "Original", "Body"));
        clock.offset = Duration.ofMinutes(15);
        notes.save(owner, core.id(), core.etag(), "Other", "Other body");
        assertThat(count(core.id())).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from notes.note_version where note_id=? and title='Original'", Integer.class, core.id())).isEqualTo(1);
    }

    @Test void listIsSummaryOnlyNewestFirstBoundedAndCursorCannotCrossNoteOrOwner() throws Exception {
        Browser owner = browser(account(), "ROLE_USER"), other = browser(account(), "ROLE_USER");
        Core core = create(owner), second = create(owner), foreign = create(other);
        Instant time = Instant.parse("2026-10-01T00:00:00.123Z");
        UUID old = seed(owner.user(), core.id(), 10, "Old", time);
        UUID tied = seed(owner.user(), core.id(), 11, "Tie", time);
        UUID newest = seed(owner.user(), core.id(), 12, "New", time.plusSeconds(1));
        var first = mvc.perform(get(path(core) + "/versions?limit=1").cookie(owner.cookie())).andExpect(status().isOk()).andReturn();
        assertThat(first.getResponse().getHeader("ETag")).isNull();
        assertThat(first.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        var page = json.readTree(body(first));
        assertThat(page.get("items").size()).isEqualTo(1);
        assertThat(page.get("items").get(0).get("id").asText()).isEqualTo(newest.toString());
        assertThat(body(first)).doesNotContain("markdown", "owner", "Body");
        String cursor = page.get("nextCursor").asText();
        var rest = mvc.perform(get(path(core) + "/versions").cookie(owner.cookie()).param("cursor", cursor))
                .andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(body(rest)).get("items").size()).isEqualTo(2);
        UUID larger = old.compareTo(tied) > 0 ? old : tied; // UUIDv7 fixtures share their positive timestamp prefix.
        assertThat(json.readTree(body(rest)).get("items").get(0).get("id").asText()).isEqualTo(larger.toString());
        assertThat(json.readTree(body(rest)).get("nextCursor").isNull()).isTrue();
        mvc.perform(get(path(second) + "/versions").cookie(owner.cookie()).param("cursor", cursor)).andExpect(status().isBadRequest());
        mvc.perform(get(path(foreign) + "/versions").cookie(other.cookie()).param("cursor", cursor)).andExpect(status().isBadRequest());
        for (String invalid : List.of("limit=0", "limit=101", "limit=no", "sort=anything")) {
            int result = mvc.perform(get(path(core) + "/versions?" + invalid).cookie(owner.cookie())).andReturn().getResponse().getStatus();
            assertThat(result).isIn(400, 422);
        }
    }

    @Test void readAndRestoreNeverTreatVersionLocatorAsAuthority() throws Exception {
        Browser owner = browser(account(), "ROLE_USER"), other = browser(account(), "ROLE_USER");
        Core core = create(owner), second = create(owner);
        UUID version = seed(owner.user(), core.id(), 10, "Checkpoint", Instant.now());
        var read = mvc.perform(get(versionPath(core, version)).cookie(owner.cookie())).andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(body(read)).get("markdown").asText()).isEqualTo("Checkpoint body");
        assertThat(read.getResponse().getHeader("ETag")).isNull();
        assertThat(read.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        for (Browser caller : List.of(other, owner)) {
            Core target = caller == other ? core : second;
            mvc.perform(get(path(target) + "/versions").cookie(other.cookie())).andExpect(status().isNotFound());
            mvc.perform(get(versionPath(target, version)).cookie(caller.cookie())).andExpect(status().isNotFound());
            restore(caller, target, version, target.etag(), "{\"confirmRestore\":true}").andExpect(status().isNotFound());
        }
        mvc.perform(get(versionPath(core, UUID.randomUUID())).cookie(owner.cookie())).andExpect(status().isNotFound());
        jdbc.update("update notes.note set lifecycle_state='logically_deleted',deleted_at=now() where note_id=?", core.id());
        mvc.perform(get(path(core) + "/versions").cookie(owner.cookie())).andExpect(status().isNotFound());
        mvc.perform(get(versionPath(core, version)).cookie(owner.cookie())).andExpect(status().isNotFound());
        restore(owner, core, version, core.etag(), "{\"confirmRestore\":true}").andExpect(status().isNotFound());
    }

    @Test void restoreRequiresConfirmationCsrfFullAuthorityAndCurrentNoteEtag() throws Exception {
        Browser owner = browser(account(), "ROLE_USER"), preMfa = browser(account(), "ROLE_MFA_PENDING");
        Core core = create(owner); UUID version = seed(owner.user(), core.id(), 10, "Earlier", Instant.now());
        for (String input : List.of("{}", "{\"confirmRestore\":false}", "{\"confirmRestore\":\"true\"}", "{\"confirmRestore\":true,\"title\":\"Injected\"}"))
            restore(owner, core, version, core.etag(), input).andExpect(status().isUnprocessableContent());
        restore(owner, core, version, null, "{\"confirmRestore\":true}").andExpect(status().isPreconditionRequired());
        restore(owner, core, version, "\"stale\"", "{\"confirmRestore\":true}").andExpect(status().isPreconditionFailed());
        mvc.perform(post(versionPath(core, version) + "/restore").cookie(owner.cookie()).header("If-Match", core.etag())
                .contentType(MediaType.APPLICATION_JSON).content("{\"confirmRestore\":true}")).andExpect(status().isForbidden());
        mvc.perform(get(path(core) + "/versions")).andExpect(status().isUnauthorized());
        mvc.perform(get(path(core) + "/versions").cookie(preMfa.cookie())).andExpect(status().isForbidden());
        restore(preMfa, core, version, core.etag(), "{\"confirmRestore\":true}").andExpect(status().isForbidden());
        assertThat(count(core.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select revision from notes.note where note_id=?", Long.class, core.id())).isEqualTo(1);
    }

    @Test void archivedAndTrashedHistoryIsInspectableButNotRestorable() throws Exception {
        Browser owner = browser(account(), "ROLE_USER");
        for (String lifecycle : List.of("archive", "trash")) {
            Core core = create(owner); UUID version = seed(owner.user(), core.id(), 10, "Earlier", Instant.now());
            var changed = mvc.perform(post(path(core) + "/" + lifecycle).cookie(owner.cookie()).header("X-CSRF-TOKEN", owner.csrf())
                    .header("If-Match", core.etag())).andExpect(status().isOk()).andReturn();
            mvc.perform(get(versionPath(core, version)).cookie(owner.cookie())).andExpect(status().isOk());
            restore(owner, core, version, changed.getResponse().getHeader("ETag"), "{\"confirmRestore\":true}").andExpect(status().isConflict());
            assertThat(count(core.id())).isEqualTo(1);
        }
    }

    @Test void restoreCopiesOnlyEditorContentAndRetainsPreRestoreStateWithoutEditingSelectedCheckpoint() throws Exception {
        Browser owner = browser(account(), "ROLE_USER"); Core core = create(owner);
        UUID version = seed(owner.user(), core.id(), 10, "Historical", Instant.now());
        var selected = row(version);
        jdbc.update("update notes.note set pinned=true,ai_enabled=true,ai_generation=9 where note_id=?", core.id());
        jdbc.update("insert into notes.note_tag(note_id,owner_user_id,normalized_label,display_label,created_at) values (?,?,'kept','Kept',now())", core.id(), owner.user());
        var before = jdbc.queryForMap("select * from notes.note where note_id=?", core.id());
        var result = restore(owner, core, version, core.etag(), "{\"confirmRestore\":true}").andExpect(status().isOk()).andReturn();
        var value = json.readTree(body(result));
        assertThat(value.get("title").asText()).isEqualTo("Historical");
        assertThat(value.get("markdown").asText()).isEqualTo("Historical body");
        assertThat(value.get("pinned").asBoolean()).isTrue(); assertThat(value.get("aiEnabled").asBoolean()).isTrue();
        assertThat(value.get("tags").get(0).asText()).isEqualTo("Kept");
        assertThat(value.get("lifecycle").asText()).isEqualTo("active");
        assertThat(result.getResponse().getHeader("ETag")).isNotEqualTo(core.etag());
        var after = jdbc.queryForMap("select * from notes.note where note_id=?", core.id());
        assertThat(after.get("revision")).isEqualTo(2L);
        for (String key : before.keySet()) if (!List.of("title", "markdown", "revision", "updated_at").contains(key))
            assertThat(after.get(key)).as(key).isEqualTo(before.get(key));
        assertThat(row(version)).isEqualTo(selected);
        assertThat(jdbc.queryForObject("select count(*) from notes.note_version where note_id=? and title='Original' and markdown='Body' and checkpoint_kind='pre_restore'", Integer.class, core.id())).isEqualTo(1);
    }

    @Test void holdAwareCompactionPreservesHeldHistoryAndDeletesOnlyOldUnheldRows() throws Exception {
        UUID owner = account(); Core core = notesCore(notes.create(owner, "Original", "Body", false));
        UUID held = seed(owner, core.id(), 10, "Held", Instant.now().minusSeconds(100));
        UUID holder = jdbc.queryForObject("select uuidv7()", UUID.class);
        versions.acquirePublicationHold(owner, core.id(), held, holder);
        versions.acquirePublicationHold(owner, core.id(), held, holder);
        assertThatThrownBy(() -> versions.acquirePublicationHold(account(), core.id(), held, holder))
                .isInstanceOf(org.notesknowledge.websupport.ApiFailureException.class);
        versions.releasePublicationHold(owner, core.id(), held, UUID.randomUUID());
        assertThat(jdbc.queryForObject("select count(*) from notes.note_version_hold where note_version_id=?", Integer.class, held)).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("update notes.note_version_hold set holder_id=uuidv7() where note_version_id=?", held))
                .isInstanceOf(DataIntegrityViolationException.class);
        UUID old = seed(owner, core.id(), 11, "Old", Instant.now().minusSeconds(50));
        UUID recent = seed(owner, core.id(), 12, "Recent", Instant.now().minusSeconds(20));
        UUID newest = seed(owner, core.id(), 13, "Newest", Instant.now().minusSeconds(10));
        versions.restore(owner, core.id(), newest, core.etag());
        assertThat(history.find(owner, core.id(), held)).isPresent();
        assertThat(history.find(owner, core.id(), old)).isEmpty();
        assertThat(history.find(owner, core.id(), recent)).isEmpty();
        assertThat(history.find(owner, core.id(), newest)).isPresent();
        assertThat(count(core.id())).isEqualTo(3); // held + two unheld, including pre-restore saved content.
        assertThatThrownBy(() -> jdbc.update("delete from notes.note_version where note_version_id=?", held)).isInstanceOf(DataIntegrityViolationException.class);
        versions.releasePublicationHold(owner, core.id(), held, holder);
        assertThat(jdbc.update("delete from notes.note_version where note_version_id=?", held)).isEqualTo(1);
    }

    @Test void migrationEnforcesScopeLineageKindAndImmutabilityEvenForRuntimeDmlRole() throws Exception {
        Browser owner = browser(account(), "ROLE_USER"); Core core = create(owner), second = create(owner);
        UUID version = seed(owner.user(), core.id(), 10, "Frozen", Instant.now());
        var before = row(version);
        assertThatThrownBy(() -> seed(account(), core.id(), 11, "Wrong owner", Instant.now())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> seed(owner.user(), core.id(), 10, "Duplicate revision", Instant.now())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update notes.note_version set title='Changed' where note_version_id=?", version)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into notes.note_version values (uuidv7(),?,?, 'Invalid','Body',11,'arbitrary',now())", core.id(), owner.user())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> seed(owner.user(), core.id(), 0, "Invalid revision", Instant.now())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into notes.note_version_hold values (?,?,?,'publication',uuidv7(),now())", version, second.id(), owner.user())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into notes.note_version_hold values (?,?,?,'private_note',uuidv7(),now())", version, core.id(), owner.user())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("delete from notes.note where note_id=?", core.id())).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.execute("create role version_runtime login password 'synthetic-version-runtime-password'");
        jdbc.execute("grant usage on schema notes to version_runtime");
        jdbc.execute("grant select,insert,update,delete on notes.note_version,notes.note_version_hold to version_runtime");
        var runtime = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), "version_runtime", "synthetic-version-runtime-password"));
        assertThatThrownBy(() -> runtime.update("update notes.note_version set markdown='Rewrite' where note_version_id=?", version)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(row(version)).isEqualTo(before);
        assertThat(jdbc.queryForList("select indexname from pg_indexes where schemaname='notes'", String.class))
                .contains("ux_note_version_scope", "ux_note_version_lineage", "ix_note_version_owner_created");
        assertThat(jdbc.queryForObject("select count(*) from pg_tables where schemaname in ('identity','notes')", Integer.class)).isEqualTo(16);
    }

    @Test void failedSaveAndRestoreRollBackCheckpointInsertionAndCompactionTogether() {
        UUID owner = account(); Core core = notesCore(notes.create(owner, "Original", "Body", false));
        UUID selected = seed(owner, core.id(), 10, "Earlier", Instant.now().minusSeconds(600));
        seed(owner, core.id(), 11, "Middle", Instant.now().minusSeconds(590));
        seed(owner, core.id(), 12, "Recent", Instant.now().minusSeconds(580));
        var oldHistory = jdbc.queryForList("select * from notes.note_version where note_id=? order by source_revision", core.id());
        var before = jdbc.queryForMap("select * from notes.note where note_id=?", core.id());
        org.mockito.Mockito.doThrow(new IllegalStateException("Synthetic write failure"))
                .when(repository).save(org.mockito.ArgumentMatchers.eq(owner), org.mockito.ArgumentMatchers.eq(core.id()),
                        org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
        assertThatThrownBy(() -> notes.save(owner, core.id(), core.etag(), "Next", "New draft"))
                .isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException.class)
                .hasRootCauseInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForList("select * from notes.note_version where note_id=? order by source_revision", core.id())).isEqualTo(oldHistory);
        org.mockito.Mockito.doCallRealMethod().when(repository).save(org.mockito.ArgumentMatchers.eq(owner),
                org.mockito.ArgumentMatchers.eq(core.id()), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
        // Fail after both the Note write and actual retention DELETE, not before either effect.
        org.mockito.Mockito.doAnswer(invocation -> {
            invocation.callRealMethod(); throw new IllegalStateException("Synthetic retention failure");
        }).when(history).compact(org.mockito.ArgumentMatchers.eq(owner), org.mockito.ArgumentMatchers.eq(core.id()),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyList());
        assertThatThrownBy(() -> versions.restore(owner, core.id(), selected, core.etag()))
                .isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException.class)
                .hasRootCauseInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForList("select * from notes.note_version where note_id=? order by source_revision", core.id())).isEqualTo(oldHistory);
        assertThat(jdbc.queryForMap("select * from notes.note where note_id=?", core.id())).isEqualTo(before);
    }

    @Test void saveVersusRestoreHasOneWinnerAndStaleLoserWithCoherentSavedHistory() throws Exception {
        Browser owner = browser(account(), "ROLE_USER");
        for (int attempt = 0; attempt < 6; attempt++) {
            Core core = create(owner); UUID selected = seed(owner.user(), core.id(), 10, "Earlier", Instant.now().minusSeconds(600));
            var frozen = row(selected); CountDownLatch start = new CountDownLatch(1);
            try (var workers = Executors.newFixedThreadPool(2)) {
                var save = workers.submit(() -> {
                    if (!start.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("Race coordination timed out");
                    return saveRequest(owner, core, "Winner", "Saved winner").andReturn().getResponse().getStatus();
                });
                var restore = workers.submit(() -> {
                    if (!start.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("Race coordination timed out");
                    return restore(owner, core, selected, core.etag(), "{\"confirmRestore\":true}").andReturn().getResponse().getStatus();
                });
                start.countDown(); int saved = save.get(30, TimeUnit.SECONDS), restored = restore.get(30, TimeUnit.SECONDS);
                assertThat(List.of(saved, restored)).containsExactlyInAnyOrder(200, 412);
                assertThat(jdbc.queryForObject("select revision from notes.note where note_id=?", Long.class, core.id())).isEqualTo(2);
                assertThat(jdbc.queryForObject("select title from notes.note where note_id=?", String.class, core.id())).isEqualTo(saved == 200 ? "Winner" : "Earlier");
                assertThat(row(selected)).isEqualTo(frozen);
                assertThat(jdbc.queryForObject("select count(*) from notes.note_version where note_id=? and title='Original' and markdown='Body'", Integer.class, core.id())).isEqualTo(1);
                assertThat(count(core.id())).isEqualTo(2);
            }
        }
    }

    private UUID seed(UUID owner, UUID note, long revision, String title, Instant time) {
        UUID id = jdbc.queryForObject("select uuidv7()", UUID.class);
        jdbc.update("insert into notes.note_version values (?,?,?,?,?,?,?,?)", id, note, owner, title, title + " body", revision, "policy", java.sql.Timestamp.from(time));
        return id;
    }
    private Map<String, Object> row(UUID version) { return jdbc.queryForMap("select * from notes.note_version where note_version_id=?", version); }
    private int count(UUID note) { return jdbc.queryForObject("select count(*) from notes.note_version where note_id=?", Integer.class, note); }
    private UUID onlyVersion(UUID note) { return jdbc.queryForObject("select note_version_id from notes.note_version where note_id=?", UUID.class, note); }
    private Core notesCore(NotesService.EtaggedNote value) { return new Core(value.note().id(), value.etag()); }
    private String path(Core core) { return "/api/notes/" + core.id(); }
    private String versionPath(Core core, UUID version) { return path(core) + "/versions/" + version; }
    private Core create(Browser owner) throws Exception {
        var result = mvc.perform(post("/api/notes").cookie(owner.cookie()).header("X-CSRF-TOKEN", owner.csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Original\",\"markdown\":\"Body\"}"))
                .andExpect(status().isCreated()).andReturn();
        return new Core(UUID.fromString(json.readTree(body(result)).get("id").asText()), result.getResponse().getHeader("ETag"));
    }
    private ResultActions saveRequest(Browser owner, Core core, String title, String markdown) throws Exception {
        return mvc.perform(put(path(core)).cookie(owner.cookie()).header("X-CSRF-TOKEN", owner.csrf()).header("If-Match", core.etag())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("title", title, "markdown", markdown))));
    }
    private Core save(Browser owner, Core core, String title, String markdown) throws Exception {
        var result = saveRequest(owner, core, title, markdown).andExpect(status().isOk()).andReturn();
        return new Core(core.id(), result.getResponse().getHeader("ETag"));
    }
    private ResultActions restore(Browser owner, Core core, UUID version, String etag, String content) throws Exception {
        var request = post(versionPath(core, version) + "/restore").cookie(owner.cookie()).header("X-CSRF-TOKEN", owner.csrf())
                .contentType(MediaType.APPLICATION_JSON).content(content);
        if (etag != null) request.header("If-Match", etag);
        return mvc.perform(request);
    }
    private UUID account() {
        UUID user = jdbc.queryForObject("select uuidv7()", UUID.class);
        String email = "version-" + user + "@example.test";
        jdbc.update("insert into identity.account(user_id,canonical_email,display_email,email_verified_at,account_state,created_at,updated_at) values (?,?,?,now(),'active',now(),now())", user, email, email);
        return user;
    }
    private Browser browser(UUID user, String role) throws Exception {
        Session session = sessions.createSession(); var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(new IdentitySessionPrincipal(user), null, List.of(new SimpleGrantedAuthority(role))));
        session.setAttribute("SPRING_SECURITY_CONTEXT", context); persist(session);
        Cookie cookie = new Cookie("SESSION", Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8)));
        var csrf = mvc.perform(get("/api/auth/csrf").cookie(cookie)).andExpect(status().isOk()).andReturn();
        return new Browser(user, csrf.getResponse().getCookie("SESSION") == null ? cookie : csrf.getResponse().getCookie("SESSION"), json.readTree(body(csrf)).get("csrfToken").asText());
    }
    @SuppressWarnings({"rawtypes", "unchecked"}) private void persist(Session session) { ((SessionRepository) sessions).save(session); }
    private static String body(MvcResult result) throws Exception { return result.getResponse().getContentAsString(); }
    private record Browser(UUID user, Cookie cookie, String csrf) { }
    private record Core(UUID id, String etag) { }
}
