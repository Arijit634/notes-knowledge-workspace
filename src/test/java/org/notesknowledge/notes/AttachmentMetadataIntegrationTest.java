package org.notesknowledge.notes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.notesknowledge.identity.IdentitySessionPrincipal;
import org.notesknowledge.security.RateLimitPort;
import org.notesknowledge.websupport.AttachmentCoreVersion;
import org.notesknowledge.websupport.OpaqueCursorCodec;
import org.notesknowledge.websupport.StrongCoreEtagCodec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("DATABASE") @Tag("API") @Tag("SECURITY")
@Testcontainers @SpringBootTest @AutoConfigureMockMvc
@Import(NotesEditorIntegrationTest.TimeConfiguration.class)
class AttachmentMetadataIntegrationTest {
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18-trixie")
            .withDatabaseName("attachment_metadata").withUsername("metadata_migrator")
            .withPassword("synthetic-metadata-password");
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
    @Autowired StrongCoreEtagCodec etags;
    @Autowired OpaqueCursorCodec cursors;
    @Autowired NotesEditorIntegrationTest.AdjustableClock clock;
    @MockitoBean RateLimitPort rates;
    @MockitoBean PrivateAttachmentObjectStore objects;
    @MockitoBean AttachmentMediaValidator validators;
    @MockitoSpyBean AttachmentRepository attachments;
    @MockitoSpyBean NotesRepository notes;
    private static final Instant CREATED = Instant.parse("2026-10-01T12:00:00.123Z");

    @BeforeEach void setup() {
        clock.offset = Duration.ZERO;
        org.mockito.Mockito.when(rates.evaluate(any())).thenReturn(new RateLimitPort.Allowed());
    }
    @AfterEach void metadataNeverAccessesObjectCustodyOrParsers() { verifyNoInteractions(objects, validators); }

    @Test void emptyCollectionIsNoStoreAndRequiresNeitherCsrfNorRecentAuth() throws Exception {
        UUID owner = account(), note = note(owner); Cookie cookie = browser(owner, "ROLE_USER");
        var response = mvc.perform(get(path(note)).cookie(cookie)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")).andReturn();
        assertThat(body(response).get("items").isEmpty()).isTrue();
        assertThat(body(response).get("nextCursor").isNull()).isTrue();
        assertThat(response.getResponse().getHeader("ETag")).isNull();
    }

    @ParameterizedTest @ValueSource(strings = {"image", "audio", "video", "pdf"})
    void safeCoreIsSharedByListAndReadWithExactRevisionEtag(String kind) throws Exception {
        UUID owner = account(), note = note(owner), id = attachment(owner, note, kind, CREATED);
        Cookie cookie = browser(owner, "ROLE_USER");
        var snapshot = row(id);
        var read = mvc.perform(get(path(note) + "/" + id).cookie(cookie)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("ETag", etags.encode(new AttachmentCoreVersion(id, 1)))).andReturn();
        var core = body(read);
        assertThat(core.properties()).extracting(Map.Entry::getKey).containsExactlyInAnyOrder(
                "id", "noteId", "mediaKind", "displayFilename", "mediaType", "sizeBytes", "width", "height",
                "durationSeconds", "pageCount", "storageState", "validationState", "cleanupState", "createdAt", "updatedAt");
        assertThat(core.get("id").asText()).isEqualTo(id.toString());
        assertThat(core.get("noteId").asText()).isEqualTo(note.toString());
        assertThat(core.get("mediaKind").asText()).isEqualTo(kind);
        assertThat(core.get("displayFilename").asText()).isEqualTo("synthetic." + kind);
        assertThat(core.get("sizeBytes").asLong()).isEqualTo(100);
        assertThat(core.get("createdAt").asText()).isEqualTo(CREATED.toString());
        assertThat(core.get("updatedAt")).isEqualTo(core.get("createdAt"));
        assertThat(core.get("storageState").asText()).isEqualTo("stored");
        assertThat(core.get("validationState").asText()).isEqualTo("accepted");
        assertThat(core.get("cleanupState").asText()).isEqualTo("retained");
        assertThat(read.getResponse().getContentAsString()).doesNotContain("private-attachment/", "processingGeneration", "revision");
        var page = mvc.perform(get(path(note)).cookie(cookie)).andExpect(status().isOk()).andReturn();
        assertThat(body(page).get("items").get(0)).isEqualTo(core);
        // No conditional-cache behavior is selected: If-None-Match still returns the no-store core.
        mvc.perform(get(path(note) + "/" + id).cookie(cookie).header("If-None-Match", read.getResponse().getHeader("ETag")))
                .andExpect(status().isOk());
        assertThat(row(id)).isEqualTo(snapshot);
    }

    @Test void paginationTraversesEveryRowOnceWithMillisecondTiesAndFixedOrdering() throws Exception {
        UUID owner = account(), note = note(owner); Cookie cookie = browser(owner, "ROLE_USER");
        List<UUID> tied = new ArrayList<>();
        for (int i = 0; i < 7; i++) tied.add(attachment(owner, note, "image", CREATED));
        tied.sort(java.util.Comparator.comparing(UUID::toString).reversed());
        UUID newest = attachment(owner, note, "pdf", CREATED.plusMillis(1));
        UUID oldest = attachment(owner, note, "audio", CREATED.minusMillis(1));
        List<UUID> expected = new ArrayList<>(); expected.add(newest); expected.addAll(tied); expected.add(oldest);
        List<UUID> observed = new ArrayList<>(); String cursor = null;
        do {
            var request = get(path(note)).cookie(cookie).param("limit", "2");
            if (cursor != null) request.param("cursor", cursor);
            var response = mvc.perform(request).andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "no-store")).andReturn();
            var page = body(response); assertThat(page.get("items").size()).isBetween(1, 2);
            page.get("items").forEach(item -> observed.add(UUID.fromString(item.get("id").asText())));
            cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asText();
            assertThat(observed.size()).isLessThanOrEqualTo(expected.size());
        } while (cursor != null);
        assertThat(observed).containsExactlyElementsOf(expected).doesNotHaveDuplicates();
    }

    @Test void defaultAndMaximumPageLimitsAreBounded() throws Exception {
        UUID owner = account(), note = note(owner); Cookie cookie = browser(owner, "ROLE_USER");
        // Synthetic relational fixtures exercise pagination independently of upload's application quota.
        for (int i = 0; i < 21; i++) attachment(owner, note, "image", CREATED.plusMillis(i));
        var first = body(mvc.perform(get(path(note)).cookie(cookie)).andExpect(status().isOk()).andReturn());
        assertThat(first.get("items").size()).isEqualTo(20); assertThat(first.get("nextCursor").isNull()).isFalse();
        var maximum = body(mvc.perform(get(path(note)).cookie(cookie).param("limit", "100")).andExpect(status().isOk()).andReturn());
        assertThat(maximum.get("items").size()).isEqualTo(21); assertThat(maximum.get("nextCursor").isNull()).isTrue();
        org.mockito.Mockito.verify(attachments).retainedPage(owner, note, null, null, 21);
        org.mockito.Mockito.verify(attachments).retainedPage(owner, note, null, null, 101);
    }

    @ParameterizedTest @ValueSource(strings = {"0", "-1", "101", "nonnumeric", "", "2147483648"})
    void rejectsInvalidLimits(String limit) throws Exception {
        UUID owner = account(), note = note(owner);
        mvc.perform(get(path(note)).cookie(browser(owner, "ROLE_USER")).param("limit", limit)).andExpect(status().isBadRequest());
    }

    @ParameterizedTest @ValueSource(strings = {"ownerUserId", "sort", "objectReference"})
    void rejectsUnknownQueryKeys(String key) throws Exception {
        UUID owner = account(), note = note(owner);
        mvc.perform(get(path(note)).cookie(browser(owner, "ROLE_USER")).param(key, "synthetic")).andExpect(status().isBadRequest());
    }

    @Test void rejectsMalformedTamperedOversizedAndExpiredCursors() throws Exception {
        UUID owner = account(), note = note(owner); Cookie cookie = browser(owner, "ROLE_USER");
        attachment(owner, note, "image", CREATED); attachment(owner, note, "image", CREATED);
        String cursor = firstCursor(cookie, note);
        int middle = cursor.length() / 2;
        String tampered = cursor.substring(0, middle) + (cursor.charAt(middle) == 'A' ? 'B' : 'A') + cursor.substring(middle + 1);
        for (String invalid : List.of("malformed", "", tampered, "x".repeat(2049))) {
            mvc.perform(get(path(note)).cookie(cookie).param("cursor", invalid)).andExpect(status().isBadRequest());
        }
        String expired = cursors.encode(context(owner, note, "NOTE_ATTACHMENTS", "CREATED_DESC"),
                position(), Duration.ofSeconds(1));
        clock.offset = Duration.ofSeconds(2);
        mvc.perform(get(path(note)).cookie(cookie).param("cursor", expired)).andExpect(status().isBadRequest());
    }

    @Test void rejectsOtherOwnerNoteRouteSortAndTupleWithoutTreatingCursorAsAuthority() throws Exception {
        UUID owner = account(), note = note(owner), otherOwner = account(), otherNote = note(otherOwner);
        Cookie cookie = browser(owner, "ROLE_USER"), otherCookie = browser(otherOwner, "ROLE_USER");
        attachment(owner, note, "image", CREATED); attachment(owner, note, "image", CREATED);
        String cursor = firstCursor(cookie, note);
        mvc.perform(get(path(otherNote)).cookie(otherCookie).param("cursor", cursor)).andExpect(status().isBadRequest());
        mvc.perform(get(path(note(owner))).cookie(cookie).param("cursor", cursor)).andExpect(status().isBadRequest());
        for (var context : List.of(context(owner, note, "NOTE_VERSIONS", "CREATED_DESC"),
                context(owner, note, "NOTE_ATTACHMENTS", "CREATED_ASC"))) {
            mvc.perform(get(path(note)).cookie(cookie).param("cursor", cursors.encode(context, position(), Duration.ofHours(24))))
                    .andExpect(status().isBadRequest());
        }
        String wrongTuple = cursors.encode(context(owner, note, "NOTE_ATTACHMENTS", "CREATED_DESC"),
                new OpaqueCursorCodec.OrderingTuple(List.of(new OpaqueCursorCodec.SignedLongValue(1))), Duration.ofHours(24));
        mvc.perform(get(path(note)).cookie(cookie).param("cursor", wrongTuple)).andExpect(status().isBadRequest());
        for (String invalid : List.of(cursor, "malformed", "x".repeat(2049))) {
            mvc.perform(get(path(note)).cookie(otherCookie).param("cursor", invalid)).andExpect(status().isNotFound());
        }
    }

    @Test void ownerAndExactNoteTuplePrecedeAttachmentLookup() throws Exception {
        UUID owner = account(), note = note(owner), id = attachment(owner, note, "image", CREATED);
        UUID outsider = account(); Cookie cookie = browser(owner, "ROLE_USER"), other = browser(outsider, "ROLE_USER");
        for (UUID missingNote : List.of(note, uuid())) {
            mvc.perform(get(path(missingNote)).cookie(other)).andExpect(status().isNotFound());
            mvc.perform(get(path(missingNote) + "/" + id).cookie(other)).andExpect(status().isNotFound());
        }
        mvc.perform(get(path(note(owner)) + "/" + id).cookie(cookie)).andExpect(status().isNotFound());
        mvc.perform(get(path(note) + "/" + uuid()).cookie(cookie)).andExpect(status().isNotFound());
        mvc.perform(get(path(note(outsider)) + "/" + id).cookie(other)).andExpect(status().isNotFound());
        var order = org.mockito.Mockito.inOrder(notes, attachments);
        mvc.perform(get(path(note) + "/" + id).cookie(cookie)).andExpect(status().isOk());
        order.verify(notes).find(owner, note); order.verify(attachments).findRetained(owner, note, id);
        org.mockito.Mockito.verify(attachments, org.mockito.Mockito.never()).findRetained(org.mockito.ArgumentMatchers.eq(outsider),
                org.mockito.ArgumentMatchers.eq(note), any());
    }

    @ParameterizedTest @ValueSource(strings = {"active", "archived", "trashed"})
    void retainedParentLifecycleRemainsPrivatelyReadable(String lifecycle) throws Exception {
        UUID owner = account(), note = note(owner), id = attachment(owner, note, "image", CREATED);
        setLifecycle(note, lifecycle); Cookie cookie = browser(owner, "ROLE_USER");
        mvc.perform(get(path(note)).cookie(cookie)).andExpect(status().isOk());
        mvc.perform(get(path(note) + "/" + id).cookie(cookie)).andExpect(status().isOk());
    }

    @Test void everyPageReauthorizesAndLogicallyDeletedParentDeniesAllMetadata() throws Exception {
        UUID owner = account(), note = note(owner), id = attachment(owner, note, "image", CREATED);
        attachment(owner, note, "pdf", CREATED); Cookie cookie = browser(owner, "ROLE_USER");
        String cursor = firstCursor(cookie, note); setLifecycle(note, "logically_deleted");
        mvc.perform(get(path(note)).cookie(cookie).param("cursor", cursor)).andExpect(status().isNotFound());
        mvc.perform(get(path(note) + "/" + id).cookie(cookie)).andExpect(status().isNotFound());
    }

    @ParameterizedTest @ValueSource(strings = {"pending", "deleted"})
    void cleanupDenialExcludesRowsAndRejectsSingleReads(String cleanup) throws Exception {
        UUID owner = account(), note = note(owner), id = attachment(owner, note, "pdf", CREATED);
        jdbc.update("""
                update notes.attachment set cleanup_state=?,removed_at=created_at,
                    cleaned_at=case when ?='deleted' then created_at else null end,revision=2 where attachment_id=?
                """, cleanup, cleanup, id);
        Cookie cookie = browser(owner, "ROLE_USER");
        var page = body(mvc.perform(get(path(note)).cookie(cookie)).andExpect(status().isOk()).andReturn());
        assertThat(page.get("items").isEmpty()).isTrue();
        mvc.perform(get(path(note) + "/" + id).cookie(cookie)).andExpect(status().isNotFound());
    }

    @Test void retainedPendingAndFailedValidationMetadataIsNotSuppressedAndEtagAdvances() throws Exception {
        UUID owner = account(), note = note(owner), id = attachment(owner, note, "pdf", CREATED);
        Cookie cookie = browser(owner, "ROLE_USER");
        String first = mvc.perform(get(path(note) + "/" + id).cookie(cookie)).andExpect(status().isOk()).andReturn().getResponse().getHeader("ETag");
        jdbc.update("update notes.attachment set storage_state='pending',validation_state='pending',revision=2 where attachment_id=?", id);
        for (long revision : List.of(2L, 3L)) {
            if (revision == 3) jdbc.update("update notes.attachment set storage_state='failed',validation_state='rejected',revision=3 where attachment_id=?", id);
            var snapshot = row(id);
            var response = mvc.perform(get(path(note) + "/" + id).cookie(cookie)).andExpect(status().isOk())
                    .andExpect(header().string("ETag", etags.encode(new AttachmentCoreVersion(id, revision)))).andReturn();
            assertThat(response.getResponse().getHeader("ETag")).isNotEqualTo(first);
            assertThat(body(response).get("storageState").asText()).isEqualTo(revision == 2 ? "pending" : "failed");
            assertThat(body(response).get("validationState").asText()).isEqualTo(revision == 2 ? "pending" : "rejected");
            assertThat(body(mvc.perform(get(path(note)).cookie(cookie)).andExpect(status().isOk()).andReturn()).get("items").size()).isEqualTo(1);
            assertThat(row(id)).isEqualTo(snapshot);
        }
    }

    @Test void missingOrRestrictedSessionAndSuspensionCannotReadMetadata() throws Exception {
        UUID owner = account(), note = note(owner), id = attachment(owner, note, "image", CREATED);
        Cookie pending = browser(owner, "ROLE_MFA_PENDING"), user = browser(owner, "ROLE_USER");
        for (String url : List.of(path(note), path(note) + "/" + id)) {
            mvc.perform(get(url)).andExpect(status().isUnauthorized());
            mvc.perform(get(url).cookie(pending)).andExpect(status().isForbidden());
        }
        jdbc.update("update identity.account set account_state='suspended' where user_id=?", owner);
        for (String url : List.of(path(note), path(note) + "/" + id)) {
            assertThat(mvc.perform(get(url).cookie(user)).andReturn().getResponse().getStatus()).isIn(401, 403);
        }
    }

    @Test void databaseFailuresRemainSanitizedServiceUnavailable() throws Exception {
        UUID owner = account(), note = note(owner), id = attachment(owner, note, "pdf", CREATED);
        Cookie cookie = browser(owner, "ROLE_USER");
        doThrow(new DataAccessResourceFailureException("SYNTHETIC_PRIVATE_SQL_DETAIL")).when(attachments).findRetained(owner, note, id);
        doThrow(new DataAccessResourceFailureException("SYNTHETIC_PRIVATE_SQL_DETAIL")).when(attachments).retainedPage(owner, note, null, null, 21);
        for (String url : List.of(path(note), path(note) + "/" + id)) {
            var failure = mvc.perform(get(url).cookie(cookie)).andExpect(status().isServiceUnavailable()).andReturn();
            assertThat(body(failure).get("code").asText()).isEqualTo("service_unavailable");
            assertThat(failure.getResponse().getContentAsString()).doesNotContain("SYNTHETIC_PRIVATE_SQL_DETAIL", "Exception", "objectReference");
        }
    }

    @Test void deleteAndAiProcessingRemainUnavailable() throws Exception {
        UUID owner = account(), note = note(owner), id = attachment(owner, note, "pdf", CREATED);
        Cookie cookie = browser(owner, "ROLE_USER");
        mvc.perform(delete(path(note) + "/" + id).cookie(cookie)).andExpect(status().isForbidden());
        mvc.perform(get("/api/notes/" + note + "/ai-processing").cookie(cookie)).andExpect(status().isForbidden());
        assertThat(row(id).get("cleanup_state")).isEqualTo("retained");
    }

    private String firstCursor(Cookie cookie, UUID note) throws Exception {
        return body(mvc.perform(get(path(note)).cookie(cookie).param("limit", "1")).andExpect(status().isOk()).andReturn())
                .get("nextCursor").asText();
    }
    private OpaqueCursorCodec.ExpectedCursorContext context(UUID owner, UUID note, String route, String sort) throws Exception {
        return new OpaqueCursorCodec.ExpectedCursorContext(new OpaqueCursorCodec.RouteFamily(route),
                new OpaqueCursorCodec.ScopeFingerprint(hash(owner.toString())),
                new OpaqueCursorCodec.FilterFingerprint(hash(note.toString())), new OpaqueCursorCodec.SortCode(sort));
    }
    private OpaqueCursorCodec.OrderingTuple position() { return new OpaqueCursorCodec.OrderingTuple(List.of(
            new OpaqueCursorCodec.EpochMillisValue(CREATED.toEpochMilli()), new OpaqueCursorCodec.UuidValue(uuid()))); }
    private String hash(String input) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
    }
    private JsonNode body(MvcResult response) throws Exception { return json.readTree(response.getResponse().getContentAsString()); }
    private String path(UUID note) { return "/api/notes/" + note + "/attachments"; }
    private UUID uuid() { return jdbc.queryForObject("select uuidv7()", UUID.class); }
    private UUID account() {
        UUID id = uuid(); String email = "metadata-" + id + "@example.test";
        jdbc.update("insert into identity.account(user_id,canonical_email,display_email,email_verified_at,account_state,created_at,updated_at) values(?,?,?,now(),'active',now(),now())", id, email, email);
        return id;
    }
    private UUID note(UUID owner) {
        UUID id = uuid();
        jdbc.update("insert into notes.note(note_id,owner_user_id,title,markdown,lifecycle_state,pinned,revision,ai_enabled,ai_generation,created_at,updated_at) values(?,?,'Synthetic','Synthetic','active',false,1,false,1,now(),now())", id, owner);
        return id;
    }
    private UUID attachment(UUID owner, UUID note, String kind, Instant created) {
        UUID id = uuid(); String reference = "private-attachment/" + id.toString().replace("-", "").repeat(2);
        String type = switch (kind) { case "image" -> "image/png"; case "audio" -> "audio/wav"; case "video" -> "video/mp4"; default -> "application/pdf"; };
        jdbc.update("""
                insert into notes.attachment(attachment_id,note_id,owner_user_id,object_reference,display_filename,media_kind,media_type,
                    size_bytes,width,height,duration_seconds,page_count,storage_state,validation_state,cleanup_state,revision,processing_generation,created_at,updated_at)
                values(?,?,?,?,?,?,?,100,?,?,?,?,'stored','accepted','retained',1,1,?,?)
                """, id, note, owner, reference, "synthetic." + kind, kind, type,
                kind.equals("image") || kind.equals("video") ? 16 : null,
                kind.equals("image") || kind.equals("video") ? 12 : null,
                kind.equals("audio") || kind.equals("video") ? 1.0 : null,
                kind.equals("pdf") ? 1 : null, Timestamp.from(created), Timestamp.from(created));
        return id;
    }
    private void setLifecycle(UUID note, String lifecycle) {
        jdbc.update("""
                update notes.note set lifecycle_state=?,revision=revision+1,
                    pre_trash_state=case when ?='trashed' then 'active' else null end,
                    trashed_at=case when ?='trashed' then now() else null end,
                    deleted_at=case when ?='logically_deleted' then now() else null end where note_id=?
                """, lifecycle, lifecycle, lifecycle, lifecycle, note);
    }
    private Map<String, Object> row(UUID id) { return jdbc.queryForMap("select * from notes.attachment where attachment_id=?", id); }
    private Cookie browser(UUID user, String role) {
        Session session = sessions.createSession(); var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(new IdentitySessionPrincipal(user), null,
                List.of(new SimpleGrantedAuthority(role))));
        session.setAttribute("SPRING_SECURITY_CONTEXT", context); save(session);
        return new Cookie("SESSION", Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8)));
    }
    @SuppressWarnings({"rawtypes", "unchecked"}) private void save(Session session) { ((SessionRepository)sessions).save(session); }
}
