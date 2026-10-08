package org.notesknowledge.notes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.notesknowledge.identity.IdentitySessionPrincipal;
import org.notesknowledge.security.RateLimitPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@Tag("DATABASE") @Tag("API") @Tag("SECURITY")
@Testcontainers @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc @Import(AttachmentUploadIntegrationTest.Storage.class)
class AttachmentUploadIntegrationTest {
    @org.springframework.boot.test.web.server.LocalServerPort int port;
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18-trixie")
            .withDatabaseName("attachment_core").withUsername("attachment_migrator").withPassword("synthetic-attachment-password");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("identity.rate.key-base64", () -> "AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=");
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired SessionRepository<? extends Session> sessions;
    @Autowired MemoryStore store;
    @Autowired AttachmentUploadService uploads;
    @MockitoBean RateLimitPort rates;
    @MockitoSpyBean AttachmentTransactions transactions;
    @MockitoSpyBean AttachmentRepository repository;

    @BeforeEach void reset() {
        org.mockito.Mockito.when(rates.evaluate(any())).thenReturn(new RateLimitPort.Allowed());
        store.clear();
    }

    @ParameterizedTest @ValueSource(strings = {"image", "audio", "video", "pdf"})
    void acceptedKindsCommitSafeCoreWithoutChangingNoteRevision(String kind) throws Exception {
        UUID owner = account(), note = note(owner); Browser browser = browser(owner, "ROLE_USER");
        var response = upload(browser, note, media(kind)).andExpect(status().isCreated()).andReturn();
        var body = json.readTree(response.getResponse().getContentAsString());
        UUID id = UUID.fromString(body.get("id").asText());
        assertThat(body.get("noteId").asText()).isEqualTo(note.toString());
        assertThat(body.get("mediaKind").asText()).isEqualTo(kind);
        assertThat(body.get("storageState").asText()).isEqualTo("stored");
        assertThat(body.get("validationState").asText()).isEqualTo("accepted");
        assertThat(body.get("cleanupState").asText()).isEqualTo("retained");
        assertThat(body.properties()).extracting(java.util.Map.Entry::getKey).doesNotContain(
                "ownerUserId", "objectReference", "processingGeneration", "aiEnabled", "aiProcessing", "digest");
        assertThat(response.getResponse().getHeader("Location")).isEqualTo(path(note) + "/" + id);
        assertThat(response.getResponse().getHeader("ETag")).startsWith("\"").endsWith("\"").doesNotContain("W/");
        assertThat(response.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(jdbc.queryForObject("select revision from notes.note where note_id=?", Long.class, note)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select revision from notes.attachment where attachment_id=?", Long.class, id)).isEqualTo(1);
        String reference = jdbc.queryForObject("select object_reference from notes.attachment where attachment_id=?", String.class, id);
        assertThat(reference).matches("private-attachment/[0-9a-f]{64}").doesNotContain("synthetic");
        assertThat(store.bytes).containsOnlyKeys(reference);
        assertThat(store.sawTransaction).isFalse();
        mvc.perform(get(path(note)).cookie(browser.cookie())).andExpect(status().isOk());
        var read = mvc.perform(get(path(note) + "/" + id).cookie(browser.cookie())).andExpect(status().isOk()).andReturn();
        assertThat(read.getResponse().getHeader("ETag")).isEqualTo(response.getResponse().getHeader("ETag"));
        assertThat(json.readTree(read.getResponse().getContentAsString())).isEqualTo(body);
        var content = mvc.perform(get(path(note) + "/" + id + "/content").cookie(browser.cookie()))
                .andExpect(status().isOk()).andReturn();
        assertThat(content.getResponse().getContentAsByteArray()).isEqualTo(store.bytes.get(reference));
        assertThat(content.getResponse().getHeader("ETag")).isEqualTo(response.getResponse().getHeader("ETag"));
    }

    @Test void authorityAndCsrfFailBeforeStaging() throws Exception {
        UUID owner = account(), note = note(owner); Browser browser = browser(owner, "ROLE_USER"), anonymous = csrf(null);
        upload(anonymous, note, media("image")).andExpect(status().isUnauthorized());
        upload(browser(account(), "ROLE_MFA_PENDING"), note, media("image")).andExpect(status().isForbidden());
        mvc.perform(multipart(path(note)).file(media("image")).cookie(browser.cookie())).andExpect(status().isForbidden());
        mvc.perform(multipart(path(note)).file(media("image")).cookie(browser.cookie()).header("X-CSRF-TOKEN", "synthetic-stale-proof"))
                .andExpect(status().isForbidden());
        upload(browser(account(), "ROLE_USER"), note, media("image")).andExpect(status().isNotFound());
        jdbc.update("update identity.account set account_state='suspended' where user_id=?", owner);
        assertThat(upload(browser, note, media("image")).andReturn().getResponse().getStatus()).isIn(401, 403);
        assertThat(store.bytes).isEmpty(); assertThat(count(note)).isZero();
    }

    @Test void multipartAuthorityFieldsAndDuplicateFilesAreRejected() throws Exception {
        UUID owner = account(), note = note(owner); Browser browser = browser(owner, "ROLE_USER");
        mvc.perform(multipart(path(note)).file(media("image")).param("ownerUserId", owner.toString())
                .cookie(browser.cookie()).header("X-CSRF-TOKEN", browser.csrf())).andExpect(status().isBadRequest());
        mvc.perform(multipart(path(note)).file(media("image")).file(media("image"))
                .cookie(browser.cookie()).header("X-CSRF-TOKEN", browser.csrf())).andExpect(status().isBadRequest());
        assertThat(store.bytes).isEmpty(); assertThat(count(note)).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {"session", "account", "note"})
    void revalidatesAuthorityAfterStoredBytesAndBeforeCommit(String boundary) throws Exception {
        UUID owner = account(), note = note(owner); Browser browser = browser(owner, "ROLE_USER");
        store.afterWrite = () -> {
            assertThat(count(note)).isZero();
            if (boundary.equals("session")) sessions.deleteById(new String(Base64.getDecoder().decode(browser.cookie().getValue()), StandardCharsets.UTF_8));
            if (boundary.equals("account")) jdbc.update("update identity.account set account_state='suspended' where user_id=?", owner);
            if (boundary.equals("note")) jdbc.update("update notes.note set lifecycle_state='trashed',pre_trash_state='active',trashed_at=now(),revision=revision+1 where note_id=?", note);
        };
        assertThat(upload(browser, note, media("audio")).andReturn().getResponse().getStatus()).isEqualTo(boundary.equals("note") ? 409 : 401);
        assertThat(count(note)).isZero(); assertThat(store.bytes).isEmpty();
    }

    @Test void storeAndDatabaseFailuresAreSanitizedAndLeaveNoAuthoritativeRow() throws Exception {
        UUID owner = account(), note = note(owner); Browser browser = browser(owner, "ROLE_USER");
        store.failWrite = true;
        var failure = upload(browser, note, media("audio")).andExpect(status().isServiceUnavailable()).andReturn();
        assertThat(failure.getResponse().getContentAsString()).doesNotContain("SYNTHETIC_PRIVATE_DIAGNOSTIC", "synthetic.wav", "private-attachment", "Exception");
        assertThat(count(note)).isZero(); assertThat(store.bytes).isEmpty();
        store.failWrite = false;
        org.mockito.Mockito.doThrow(new DataAccessResourceFailureException("SYNTHETIC_PRIVATE_DIAGNOSTIC"))
                .when(repository).create(any(), any(), any(), any(), any(), any(), any());
        upload(browser, note, media("audio")).andExpect(status().isServiceUnavailable());
        assertThat(count(note)).isZero(); assertThat(store.bytes).isEmpty();
    }

    @Test void invalidMediaCleansStagingAndOrphanReconciliationIsLexicallyBounded() throws Exception {
        UUID owner = account(), note = note(owner); Browser browser = browser(owner, "ROLE_USER");
        store.failDelete = true;
        upload(browser, note, new MockMultipartFile("file", "synthetic.png", "image/png", new byte[]{(byte)137,80,78,71,13,10,26,10}))
                .andExpect(status().isUnprocessableContent());
        assertThat(count(note)).isZero(); assertThat(store.bytes).hasSize(1);
        store.created.replaceAll((key, time) -> Instant.now().minusSeconds(90_000));
        store.failDelete = false;
        assertThat(uploads.reconcile(null)).isNull(); assertThat(store.bytes).isEmpty(); assertThat(store.lastLimit).isEqualTo(100);
        upload(browser, note, media("audio")).andExpect(status().isCreated());
        store.created.replaceAll((key, time) -> Instant.now().minusSeconds(90_000));
        uploads.reconcile(null); assertThat(store.bytes).hasSize(1);
    }

    @Test void streamLimitDoesNotTrustMultipartLengthOrFilenameAndRateFailuresCreateNoRow() throws Exception {
        UUID owner = account(), note = note(owner); Browser browser = browser(owner, "ROLE_USER");
        byte[] oversized = new byte[AttachmentMediaValidator.IMAGE_BYTES + 1];
        System.arraycopy(new byte[]{(byte)137,80,78,71,13,10,26,10}, 0, oversized, 0, 8);
        var file = new MockMultipartFile("file", "synthetic.png", "image/png", oversized) { @Override public long getSize() { return 0; } };
        upload(browser, note, file).andExpect(status().isContentTooLarge());
        upload(browser, note, new MockMultipartFile("file", "bad\nname.wav", "audio/wav", AttachmentMediaValidatorTest.wav(16000,1,16000)))
                .andExpect(status().isUnprocessableContent());
        upload(browser, note, new MockMultipartFile("file", "x".repeat(252) + ".wav", "audio/wav", AttachmentMediaValidatorTest.wav(16000,1,16000)))
                .andExpect(status().isUnprocessableContent());
        org.mockito.Mockito.when(rates.evaluate(any())).thenReturn(new RateLimitPort.Throttled(5));
        var rate = upload(browser, note, media("audio")).andExpect(status().isTooManyRequests()).andReturn();
        assertThat(rate.getResponse().getHeader("Retry-After")).isEqualTo("5");
        org.mockito.Mockito.when(rates.evaluate(any())).thenReturn(new RateLimitPort.ControlUnavailable());
        upload(browser, note, media("audio")).andExpect(status().isServiceUnavailable());
        assertThat(count(note)).isZero(); assertThat(store.bytes).isEmpty();
    }

    @Test void finalQuotaSerializationPreventsConcurrentOvercommit() throws Exception {
        UUID owner = account(), note = note(owner); Browser first = browser(owner, "ROLE_USER"), second = browser(owner, "ROLE_USER");
        seed(owner, note, 19, 1, "image");
        var entered = new CountDownLatch(2);
        org.mockito.Mockito.doAnswer(call -> {
            entered.countDown(); if (!entered.await(10, TimeUnit.SECONDS)) throw new AssertionError("Concurrent finalization did not enter");
            return call.callRealMethod();
        }).when(transactions).accept(any(), any(), any(), any(), any(), any());
        try (var workers = Executors.newFixedThreadPool(2)) {
            var one = workers.submit(() -> upload(first, note, media("image")).andReturn().getResponse().getStatus());
            var two = workers.submit(() -> upload(second, note, media("image")).andReturn().getResponse().getStatus());
            assertThat(List.of(one.get(20, TimeUnit.SECONDS), two.get(20, TimeUnit.SECONDS))).containsExactlyInAnyOrder(201, 422);
        }
        assertThat(count(note)).isEqualTo(20); assertThat(store.bytes).hasSize(1);
    }

    @Test void authoritativeNoteAndOwnerByteAndCountQuotasAreChecked() throws Exception {
        UUID owner = account(), full = note(owner), room = note(owner); Browser browser = browser(owner, "ROLE_USER");
        seed(owner, full, 2, 25 * 1024 * 1024, "video");
        upload(browser, full, media("audio")).andExpect(status().isUnprocessableContent());
        for (int i = 0; i < 3; i++) seed(owner, note(owner), 2, 25 * 1024 * 1024, "video");
        upload(browser, room, media("audio")).andExpect(status().isUnprocessableContent());
        UUID other = account(), target = note(other); Browser otherBrowser = browser(other, "ROLE_USER");
        for (int i = 0; i < 10; i++) seed(other, note(other), 20, 1, "image");
        upload(otherBrowser, target, media("audio")).andExpect(status().isUnprocessableContent());
        assertThat(count(target)).isZero(); assertThat(store.bytes).isEmpty();
    }

    @Test void twoUploadAdmissionAndInFlightReconciliationCannotRace() throws Exception {
        UUID owner = account(), note = note(owner);
        Browser first = browser(owner, "ROLE_USER"), second = browser(owner, "ROLE_USER"), third = browser(owner, "ROLE_USER");
        var staged = new CountDownLatch(2); var release = new CountDownLatch(1);
        store.afterWrite = () -> {
            store.created.replaceAll((reference, time) -> Instant.now().minusSeconds(90_000));
            staged.countDown();
            try { if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Staging gate timed out"); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
        };
        try (var workers = Executors.newFixedThreadPool(3)) {
            var one = workers.submit(() -> upload(first, note, media("audio")).andReturn().getResponse().getStatus());
            var two = workers.submit(() -> upload(second, note, media("audio")).andReturn().getResponse().getStatus());
            try {
                assertThat(staged.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(count(note)).isZero(); assertThat(store.bytes).hasSize(2);
                upload(third, note, media("audio")).andExpect(status().isTooManyRequests());
                var reconciliationStarted = new CountDownLatch(1);
                var reconciliation = workers.submit(() -> { reconciliationStarted.countDown(); return uploads.reconcile(null); });
                assertThat(reconciliationStarted.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> reconciliation.get(200, TimeUnit.MILLISECONDS))
                        .isInstanceOf(java.util.concurrent.TimeoutException.class);
                assertThat(store.lastLimit).isZero(); assertThat(store.bytes).hasSize(2);
                release.countDown();
                assertThat(one.get(15, TimeUnit.SECONDS)).isEqualTo(201);
                assertThat(two.get(15, TimeUnit.SECONDS)).isEqualTo(201);
                assertThat(reconciliation.get(15, TimeUnit.SECONDS)).isNull();
                assertThat(count(note)).isEqualTo(2); assertThat(store.bytes).hasSize(2);
            } finally { release.countDown(); }
        }
    }

    @Test void realServletFileAndRequestBoundsRejectBeforeStorage() throws Exception {
        UUID owner = account(), note = note(owner); Browser browser = browser(owner, "ROLE_USER");
        try (var client = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build()) {
            for (int length : new int[]{25 * 1024 * 1024 + 1, 26 * 1024 * 1024 + 1}) {
                var payload = new java.io.ByteArrayOutputStream();
                payload.write("--synthetic-boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"synthetic.mp4\"\r\nContent-Type: video/mp4\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                payload.write(new byte[length]); payload.write("\r\n--synthetic-boundary--\r\n".getBytes(StandardCharsets.US_ASCII));
                var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + path(note)))
                        .timeout(java.time.Duration.ofSeconds(20)).header("Cookie", "SESSION=" + browser.cookie().getValue())
                        .header("X-CSRF-TOKEN", browser.csrf()).header("Content-Type", "multipart/form-data; boundary=synthetic-boundary")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(payload.toByteArray())).build();
                var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(413);
                assertThat(response.body()).contains("request_too_large").doesNotContain("synthetic.mp4", "Exception", "private-attachment");
            }
        }
        assertThat(count(note)).isZero(); assertThat(store.bytes).isEmpty();
    }

    @Test void databaseConstrainsOwnerIdentityMetadataStateAndRestrictiveForeignKeys() throws Exception {
        UUID owner = account(), note = note(owner); Browser browser = browser(owner, "ROLE_USER");
        var response = upload(browser, note, media("image")).andExpect(status().isCreated()).andReturn();
        UUID id = UUID.fromString(json.readTree(response.getResponse().getContentAsString()).get("id").asText());
        for (String patch : List.of("{\"media_kind\":\"text\"}", "{\"width\":0}", "{\"width\":null}",
                "{\"revision\":0}", "{\"processing_generation\":0}", "{\"storage_state\":\"public\"}",
                "{\"cleanup_state\":\"deleted\"}", "{\"duration_seconds\":1}", "{\"size_bytes\":5242881}",
                "{\"owner_user_id\":\"" + account() + "\"}")) {
            assertThatThrownBy(() -> cloneInvalid(id, patch)).isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThatThrownBy(() -> jdbc.update("delete from notes.note where note_id=?", note)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update notes.attachment set object_reference='private-attachment/'||repeat('f',64) where attachment_id=?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                insert into notes.attachment select (jsonb_populate_record(null::notes.attachment,
                    to_jsonb(a) || jsonb_build_object('attachment_id',uuidv7()))).* from notes.attachment a where attachment_id=?
                """, id)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForList("select confdeltype::text from pg_constraint where conrelid='notes.attachment'::regclass and contype='f'", String.class)).containsExactly("r");
        assertThat(jdbc.queryForObject("select count(*) from pg_tables where schemaname in ('identity','profile','notes','knowledge','publishing','discovery','moderation')", Integer.class)).isEqualTo(35);
        assertThat(jdbc.queryForObject("show server_version_num", Integer.class)).isGreaterThanOrEqualTo(180000);
        jdbc.execute("create role attachment_runtime login password 'synthetic-attachment-runtime-password'");
        jdbc.execute("grant usage on schema notes to attachment_runtime");
        jdbc.execute("grant select,insert,update,delete on notes.attachment to attachment_runtime");
        var runtime = new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                postgres.getJdbcUrl(), "attachment_runtime", "synthetic-attachment-runtime-password"));
        assertThat(runtime.queryForObject("select has_schema_privilege(current_user,'notes','CREATE')", Boolean.class)).isFalse();
        assertThat(runtime.queryForObject("select count(*) from notes.attachment", Integer.class)).isPositive();
        assertThatThrownBy(() -> runtime.execute("create table notes.synthetic_forbidden(id integer)"))
                .isInstanceOfSatisfying(org.springframework.jdbc.BadSqlGrammarException.class,
                        failure -> assertThat(failure.getSQLException().getSQLState()).isEqualTo("42501"));
        assertThatThrownBy(() -> runtime.update("update notes.attachment set size_bytes=size_bytes+1 where attachment_id=?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void attachmentCoreTimestampChangesRequireRevisionAndCannotMoveBackwards() throws Exception {
        UUID owner = account(), note = note(owner);
        var response = upload(browser(owner, "ROLE_USER"), note, media("image"))
                .andExpect(status().isCreated()).andReturn();
        UUID id = UUID.fromString(json.readTree(response.getResponse().getContentAsString()).get("id").asText());
        assertThatThrownBy(() -> jdbc.update("""
                update notes.attachment set updated_at=updated_at+interval '1 second' where attachment_id=?
                """, id)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("""
                update notes.attachment set updated_at=updated_at+interval '1 second',revision=revision+1
                where attachment_id=?
                """, id);
        assertThat(jdbc.queryForObject("select revision from notes.attachment where attachment_id=?", Long.class, id)).isEqualTo(2);
        assertThatThrownBy(() -> jdbc.update("""
                update notes.attachment set updated_at=updated_at-interval '1 second',revision=revision+1
                where attachment_id=?
                """, id)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("update notes.attachment set processing_generation=processing_generation+1 where attachment_id=?", id);
        assertThat(jdbc.queryForObject("select revision from notes.attachment where attachment_id=?", Long.class, id)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select processing_generation from notes.attachment where attachment_id=?", Long.class, id)).isEqualTo(2);
        assertThatThrownBy(() -> jdbc.update("""
                update notes.attachment set cleanup_state='pending',removed_at=updated_at where attachment_id=?
                """, id)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("""
                update notes.attachment set cleanup_state='pending',removed_at=updated_at,revision=revision+1
                where attachment_id=?
                """, id);
        assertThat(jdbc.queryForObject("select revision from notes.attachment where attachment_id=?", Long.class, id)).isEqualTo(3);
    }

    private void cloneInvalid(UUID id, String patch) {
        jdbc.update("""
                insert into notes.attachment select (jsonb_populate_record(null::notes.attachment,
                    to_jsonb(a) || jsonb_build_object('attachment_id',uuidv7(), 'object_reference','private-attachment/'||replace(uuidv7()::text,'-','')||replace(uuidv7()::text,'-',''))
                    || ?::jsonb)).* from notes.attachment a where attachment_id=?
                """, patch, id);
    }
    private void seed(UUID owner, UUID note, int count, long bytes, String kind) {
        jdbc.update("""
                insert into notes.attachment (attachment_id,note_id,owner_user_id,object_reference,display_filename,media_kind,media_type,
                    size_bytes,width,height,duration_seconds,storage_state,validation_state,cleanup_state,revision,processing_generation,created_at,updated_at)
                select uuidv7(),?,?, 'private-attachment/'||replace(uuidv7()::text,'-','')||replace(uuidv7()::text,'-',''),
                    'synthetic',?,?,?,16,16,?,'stored','accepted','retained',1,1,now(),now() from generate_series(1,?)
                """, note, owner, kind, kind.equals("image") ? "image/png" : "video/mp4", bytes, kind.equals("image") ? null : 1.0, count);
    }
    private UUID account() {
        UUID id = jdbc.queryForObject("select uuidv7()", UUID.class); String email = "attachment-" + id + "@example.test";
        jdbc.update("insert into identity.account(user_id,canonical_email,display_email,email_verified_at,account_state,created_at,updated_at) values(?,?,?,now(),'active',now(),now())", id, email, email); return id;
    }
    private UUID note(UUID owner) {
        UUID id = jdbc.queryForObject("select uuidv7()", UUID.class);
        jdbc.update("insert into notes.note(note_id,owner_user_id,title,markdown,lifecycle_state,pinned,revision,ai_enabled,ai_generation,created_at,updated_at) values(?,?,'Synthetic','Synthetic','active',false,1,false,1,now(),now())", id, owner); return id;
    }
    private int count(UUID note) { return jdbc.queryForObject("select count(*) from notes.attachment where note_id=?", Integer.class, note); }
    private String path(UUID note) { return "/api/notes/" + note + "/attachments"; }
    private org.springframework.test.web.servlet.ResultActions upload(Browser browser, UUID note, MockMultipartFile file) throws Exception {
        return mvc.perform(multipart(path(note)).file(file).cookie(browser.cookie()).header("X-CSRF-TOKEN", browser.csrf()));
    }
    private MockMultipartFile media(String kind) throws Exception {
        var bytes = new java.io.ByteArrayOutputStream(); String extension, type;
        switch (kind) {
            case "image" -> { javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(16,12,java.awt.image.BufferedImage.TYPE_INT_RGB), "png", bytes); extension = "png"; type = "image/png"; }
            case "audio" -> { bytes.write(AttachmentMediaValidatorTest.wav(16000,1,16000)); extension = "wav"; type = "audio/wav"; }
            case "video" -> { bytes.write(AttachmentParserPreflightTest.supportedMp4()); extension = "mp4"; type = "video/mp4"; }
            case "pdf" -> { try(var document = new org.apache.pdfbox.pdmodel.PDDocument()) { document.addPage(new org.apache.pdfbox.pdmodel.PDPage()); document.save(bytes); } extension = "pdf"; type = "application/pdf"; }
            default -> throw new IllegalArgumentException("Synthetic media kind");
        }
        return new MockMultipartFile("file", "synthetic." + extension, type, bytes.toByteArray());
    }
    private Browser browser(UUID user, String role) throws Exception {
        Session session = sessions.createSession(); var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(new IdentitySessionPrincipal(user), null, List.of(new SimpleGrantedAuthority(role))));
        session.setAttribute("SPRING_SECURITY_CONTEXT", context); save(session);
        return csrf(new Cookie("SESSION", Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8))));
    }
    private Browser csrf(Cookie cookie) throws Exception {
        var request = get("/api/auth/csrf"); if (cookie != null) request.cookie(cookie);
        var response = mvc.perform(request).andExpect(status().isOk()).andReturn();
        Cookie next = response.getResponse().getCookie("SESSION");
        return new Browser(next == null ? cookie : next, json.readTree(response.getResponse().getContentAsString()).get("csrfToken").asText());
    }
    @SuppressWarnings({"rawtypes", "unchecked"}) private void save(Session session) { ((SessionRepository)sessions).save(session); }
    private record Browser(Cookie cookie, String csrf) { }

    static class MemoryStore implements PrivateAttachmentObjectStore {
        final ConcurrentHashMap<String, byte[]> bytes = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, Instant> created = new ConcurrentHashMap<>();
        volatile boolean failWrite, failDelete, sawTransaction;
        volatile int lastLimit;
        volatile boolean failOpen, ignoreRangeBound;
        volatile int opens, closes, maxReadRequest, shortAfter = -1, failReadAfter = -1;
        volatile long lastOffset, lastLength;
        volatile Runnable afterWrite = () -> { };
        void clear() { bytes.clear(); created.clear(); failWrite = false; failDelete = false; sawTransaction = false; lastLimit = 0; afterWrite = () -> { };
            failOpen = false; ignoreRangeBound = false; opens = 0; closes = 0; maxReadRequest = 0; shortAfter = -1; failReadAfter = -1; lastOffset = 0; lastLength = 0; }
        private void outside() { if (TransactionSynchronizationManager.isActualTransactionActive()) { sawTransaction = true; throw new AssertionError("Storage I/O in transaction"); } }
        public InputStream openRange(String reference, long offset, long length) {
            outside(); opens++; lastOffset = offset; lastLength = length;
            if (failOpen) throw new IllegalStateException("SYNTHETIC_PRIVATE_DIAGNOSTIC");
            byte[] payload = bytes.get(reference);
            if (payload == null || offset < 0 || length <= 0 || offset >= payload.length || length > payload.length - offset)
                throw new IllegalStateException("SYNTHETIC_PRIVATE_DIAGNOSTIC");
            int start = Math.toIntExact(offset), end = ignoreRangeBound ? payload.length : Math.toIntExact(offset + length);
            return new InputStream() {
                int position = start, consumed;
                boolean closed;
                public int read() throws java.io.IOException {
                    byte[] one = new byte[1]; int count = read(one, 0, 1); return count < 0 ? -1 : Byte.toUnsignedInt(one[0]);
                }
                public int read(byte[] buffer, int off, int count) throws java.io.IOException {
                    outside(); maxReadRequest = Math.max(maxReadRequest, count);
                    if (closed) throw new java.io.IOException("SYNTHETIC_PRIVATE_DIAGNOSTIC");
                    if (failReadAfter >= 0 && consumed >= failReadAfter) throw new java.io.IOException("SYNTHETIC_PRIVATE_DIAGNOSTIC");
                    int available = end - position;
                    if (shortAfter >= 0) available = Math.min(available, shortAfter - consumed);
                    if (failReadAfter >= 0) available = Math.min(available, failReadAfter - consumed);
                    if (available <= 0) return -1;
                    int read = Math.min(count, available);
                    System.arraycopy(payload, position, buffer, off, read); position += read; consumed += read; return read;
                }
                public void close() { outside(); if (!closed) { closes++; closed = true; } }
            };
        }
        public void write(String reference, InputStream source, long size) {
            outside();
            try {
                byte[] payload = source.readNBytes(AttachmentMediaValidator.VIDEO_BYTES + 1);
                if (payload.length != size) throw new AssertionError("Bounded size differs");
                if (bytes.putIfAbsent(reference, payload) != null) throw new AssertionError("Create-only write violated");
                created.put(reference, Instant.now()); afterWrite.run();
                if (failWrite) throw new IllegalStateException("SYNTHETIC_PRIVATE_DIAGNOSTIC");
            } catch (java.io.IOException failure) { throw new AssertionError("Synthetic stream failed"); }
        }
        public void delete(String reference) { outside(); if (failDelete) throw new IllegalStateException("SYNTHETIC_PRIVATE_DIAGNOSTIC"); bytes.remove(reference); created.remove(reference); }
        public List<StoredObject> inventoryBefore(Instant cutoff, String after, int limit) {
            outside(); lastLimit = limit;
            return created.entrySet().stream().filter(e -> e.getValue().isBefore(cutoff) && (after == null || e.getKey().compareTo(after) > 0))
                    .sorted(java.util.Map.Entry.comparingByKey()).limit(limit).map(e -> new StoredObject(e.getKey(), e.getValue())).toList();
        }
    }
    @TestConfiguration(proxyBeanMethods = false) static class Storage { @Bean @Primary MemoryStore syntheticAttachmentStore() { return new MemoryStore(); } }
}
