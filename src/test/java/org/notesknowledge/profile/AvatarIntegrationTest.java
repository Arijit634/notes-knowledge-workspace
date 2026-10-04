package org.notesknowledge.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.http.Cookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.notesknowledge.identity.IdentitySessionPrincipal;
import org.notesknowledge.identity.spi.AccountDeletionProfileConsequence;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@Tag("DATABASE") @Tag("API") @Tag("SECURITY")
@Testcontainers @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc @Import(AvatarIntegrationTest.Storage.class) @ExtendWith(OutputCaptureExtension.class)
class AvatarIntegrationTest {
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18-trixie")
            .withDatabaseName("avatar_profile").withUsername("avatar_migrator").withPassword("synthetic-avatar-migrator-password");
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
    @Autowired AvatarService avatars;
    @Autowired AvatarAssetRepository assets;
    @Autowired PlatformTransactionManager manager;
    @Autowired AccountDeletionProfileConsequence deletion;
    @Autowired MultipartConfigElement multipartLimits;
    @Autowired Clock clock;
    @Value("${local.server.port}") int port;
    @MockitoBean org.notesknowledge.security.RateLimitPort rates;
    @MockitoSpyBean AvatarTransactions swaps;
    @MockitoSpyBean AvatarAssetRepository repository;

    @BeforeEach void reset() {
        org.mockito.Mockito.when(rates.evaluate(any())).thenReturn(new org.notesknowledge.security.RateLimitPort.Allowed());
        store.reset();
    }

    @ParameterizedTest @ValueSource(strings = {"png", "jpeg"})
    void validUploadCreatesAbsentRootAndReturnsOnlySafeCanonicalMetadata(String format) throws Exception {
        UUID user = account(); Browser owner = browser(user, "ROLE_USER");
        var response = upload(owner, image(format)).andExpect(status().isOk()).andReturn();
        var view = json.readTree(body(response));
        assertThat(view.get("displayName").asText()).isEmpty();
        assertThat(view.get("biography").asText()).isEmpty(); assertThat(view.get("handle").isNull()).isTrue();
        assertThat(view.get("avatar").properties()).extracting(java.util.Map.Entry::getKey)
                .containsExactlyInAnyOrder("mediaType", "width", "height", "byteSize", "createdAt");
        assertThat(view.get("avatar").get("mediaType").asText()).isEqualTo("image/" + format);
        assertThat(view.get("avatar").get("width").asInt()).isEqualTo(3);
        assertThat(view.get("avatar").get("height").asInt()).isEqualTo(2);
        assertThat(view.get("avatar").get("byteSize").asInt()).isEqualTo(store.bytes.values().iterator().next().length);
        String reference = selectedReference(user);
        assertThat(reference).matches("private-avatar/[0-9a-f]{64}").doesNotContain("fixture");
        assertThat(body(response)).doesNotContain(reference, "fixture", "object", "profileId", "userId", "avatarAssetId");
        assertThat(response.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        var read = mvc.perform(get("/api/me/profile").cookie(owner.cookie())).andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(body(read)).get("avatar")).isEqualTo(view.get("avatar"));
        assertThat(store.sawTransaction).isFalse();
    }

    @Test void replacementRetiresOldAssetAndPrivateProfilePutPreservesNewSelection() throws Exception {
        UUID user = account(); Browser owner = browser(user,"ROLE_USER");
        setProfile(owner);
        upload(owner,image("png")).andExpect(status().isOk()); String old = selectedReference(user);
        upload(owner,image("jpeg")).andExpect(status().isOk()); String current = selectedReference(user);
        assertThat(current).isNotEqualTo(old); assertThat(store.bytes).doesNotContainKey(old).containsKey(current);
        assertThat(jdbc.queryForObject("select state from profile.avatar_asset where object_reference=?", String.class, old)).isEqualTo("removed");
        assertThat(jdbc.queryForObject("select cleaned_at is not null from profile.avatar_asset where object_reference=?", Boolean.class, old)).isTrue();
        var updated = setProfile(owner).andExpect(status().isOk()).andReturn();
        assertThat(selectedReference(user)).isEqualTo(current);
        assertThat(json.readTree(body(updated)).get("avatar").get("mediaType").asText()).isEqualTo("image/jpeg");
        assertThat(body(updated)).doesNotContain(current,old,"fixture");
        assertThat(jdbc.queryForObject("select display_name||':'||biography||':'||public_handle_original from profile.profile where user_id=?", String.class,user))
                .isEqualTo("Reader:Introduction:Reader_"+user.toString().replace("-", "").substring(0,12));
    }

    @Test void noAvatarDeleteIs204AndDoesNotCreateRoot() throws Exception {
        UUID user=account(); Browser owner=browser(user,"ROLE_USER");
        remove(owner).andExpect(status().isNoContent()); remove(owner).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select count(*) from profile.profile where user_id=?",Integer.class,user)).isZero();
        assertThat(store.deletes).isZero();
    }

    @Test void cleanupFailureDoesNotUndoRemovalAndReconciliationCompletesIt() throws Exception {
        UUID user=account(); Browser owner=browser(user,"ROLE_USER"); setProfile(owner);
        upload(owner,image("png")).andExpect(status().isOk()); String old=selectedReference(user);
        store.failDelete=true;
        remove(owner).andExpect(status().isNoContent()); remove(owner).andExpect(status().isNoContent());
        assertThat(selectedReference(user)).isNull(); assertThat(store.bytes).containsKey(old);
        assertThat(jdbc.queryForObject("select state='removed' and cleaned_at is null from profile.avatar_asset where object_reference=?",Boolean.class,old)).isTrue();
        assertThat(json.readTree(body(mvc.perform(get("/api/me/profile").cookie(owner.cookie())).andReturn())).get("avatar").isNull()).isTrue();
        store.failDelete=false; avatars.reconcile(null);
        assertThat(store.bytes).doesNotContainKey(old);
        assertThat(jdbc.queryForObject("select cleaned_at is not null from profile.avatar_asset where object_reference=?",Boolean.class,old)).isTrue();
        assertThat(store.sawTransaction).isFalse();
    }

    @Test void storageWriteFailureChangesNoAuthorityAndIsSanitized(CapturedOutput output) throws Exception {
        UUID user=account(); Browser owner=browser(user,"ROLE_USER");
        upload(owner,image("png")).andExpect(status().isOk()); String old=selectedReference(user);
        store.failWrite=true;
        var failed=upload(owner,image("jpeg")).andExpect(status().isServiceUnavailable()).andReturn();
        assertThat(selectedReference(user)).isEqualTo(old);
        assertThat(body(failed)).contains("service_unavailable").doesNotContain("SYNTHETIC_PRIVATE_DIAGNOSTIC",old,"fixture");
        assertThat(output.getAll()).doesNotContain("SYNTHETIC_PRIVATE_DIAGNOSTIC",old,"fixture.png");
        assertThat(store.bytes).hasSize(1);
    }

    @Test void dbSwapRollbackKeepsPreviousSelectionAndDeletesNewOrphan() throws Exception {
        UUID user=account(); Browser owner=browser(user,"ROLE_USER");
        upload(owner,image("png")).andExpect(status().isOk()); String old=selectedReference(user);
        org.mockito.Mockito.doAnswer(call -> { call.callRealMethod(); throw new DataAccessResourceFailureException("SYNTHETIC_PRIVATE_DIAGNOSTIC"); })
                .when(repository).select(any(),any(),any());
        upload(owner,image("jpeg")).andExpect(status().isServiceUnavailable());
        assertThat(selectedReference(user)).isEqualTo(old);
        assertThat(store.bytes).containsOnlyKeys(old);
        assertThat(jdbc.queryForObject("select count(*) from profile.avatar_asset a join profile.profile p using(profile_id) where p.user_id=?",Integer.class,user)).isEqualTo(1);
    }

    @Test void failedFirstSwapLeavesNoRootAndAgedOrphanIsBoundedlyReconciled() throws Exception {
        UUID user=account(); Browser owner=browser(user,"ROLE_USER"); store.failDelete=true;
        org.mockito.Mockito.doAnswer(call -> { call.callRealMethod(); throw new DataAccessResourceFailureException("SYNTHETIC_PRIVATE_DIAGNOSTIC"); })
                .when(repository).select(any(),any(),any());
        upload(owner,image("png")).andExpect(status().isServiceUnavailable());
        assertThat(jdbc.queryForObject("select count(*) from profile.profile where user_id=?",Integer.class,user)).isZero();
        assertThat(store.bytes).hasSize(1); String orphan=store.bytes.keySet().iterator().next();
        store.failDelete=false; avatars.reconcile(null); assertThat(store.bytes).containsKey(orphan);
        store.created.put(orphan,clock.instant().minusSeconds(90000));
        avatars.reconcile(null); assertThat(store.bytes).isEmpty();
        assertThat(store.lastLimit).isEqualTo(100); assertThat(store.sawTransaction).isFalse();
    }

    @Test void reconciliationNeverDeletesPersistedCurrentObjects() throws Exception {
        UUID user=account(); Browser owner=browser(user,"ROLE_USER");
        upload(owner,image("png")).andExpect(status().isOk()); String current=selectedReference(user);
        store.created.put(current,clock.instant().minusSeconds(90000));
        avatars.reconcile(null); assertThat(store.bytes).containsKey(current);
    }

    @Test void concurrentReplacementsHaveOneCoherentCurrentSelection() throws Exception {
        UUID user=account(); Browser one=browser(user,"ROLE_USER"),two=browser(user,"ROLE_USER");
        var ready=new CyclicBarrier(2);
        org.mockito.Mockito.doAnswer(call -> { ready.await(10,TimeUnit.SECONDS); return call.callRealMethod(); })
                .when(swaps).replace(any(),any(),any(),any());
        try(var workers=Executors.newFixedThreadPool(2)) {
            var first=workers.submit(() -> upload(one,image("png")).andReturn().getResponse().getStatus());
            var second=workers.submit(() -> upload(two,image("jpeg")).andReturn().getResponse().getStatus());
            assertThat(List.of(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS))).containsExactly(200,200);
        }
        assertThat(store.bytes).containsOnlyKeys(selectedReference(user));
        assertThat(jdbc.queryForObject("select count(*) from profile.avatar_asset a join profile.profile p using(profile_id) where p.user_id=? and a.state='validated'",Integer.class,user)).isEqualTo(1);
        assertThat(store.sawTransaction).isFalse();
    }

    @Test void staleEligibilityAfterStagingDeniesSwapAndCleansBytes() throws Exception {
        UUID user=account(); Browser owner=browser(user,"ROLE_USER");
        org.mockito.Mockito.doAnswer(call -> {
            jdbc.update("update identity.account set account_state='suspended' where user_id=?",user);
            return call.callRealMethod();
        }).when(swaps).replace(any(),any(),any(),any());
        upload(owner,image("png")).andExpect(status().isUnauthorized());
        assertThat(store.bytes).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from profile.profile where user_id=?",Integer.class,user)).isZero();
    }

    @Test void fullOwnerSessionAndCsrfAreRequiredWithoutInventingRecentAuth() throws Exception {
        Browser owner=browser(account(),"ROLE_USER"),pending=browser(account(),"ROLE_MFA_PENDING"),anonymous=csrf(null);
        upload(anonymous,image("png")).andExpect(status().isUnauthorized());
        remove(anonymous).andExpect(status().isUnauthorized());
        upload(pending,image("png")).andExpect(status().isForbidden()); remove(pending).andExpect(status().isForbidden());
        mvc.perform(multipart("/api/me/profile/avatar").file(image("png")).with(r -> {r.setMethod("PUT");return r;}).cookie(owner.cookie()))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/me/profile/avatar").cookie(owner.cookie())).andExpect(status().isForbidden());
        UUID suspended=account(); Browser inactive=browser(suspended,"ROLE_USER");
        jdbc.update("update identity.account set account_state='suspended' where user_id=?",suspended);
        // Invalidation can reject the obsolete CSRF proof before anonymous authority.
        assertThat(upload(inactive,image("png")).andReturn().getResponse().getStatus()).isIn(401,403);
        assertThat(store.bytes).isEmpty();
    }

    @Test void framingAndCallerAuthorityAreRejected() throws Exception {
        Browser owner=browser(account(),"ROLE_USER");
        mvc.perform(multipart("/api/me/profile/avatar").file(image("png")).param("userId",account().toString())
                .with(r -> {r.setMethod("PUT");return r;}).cookie(owner.cookie()).header("X-CSRF-TOKEN",owner.csrf())).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/me/profile/avatar").file(new MockMultipartFile("wrong","fixture.png","image/png",AvatarImages.image("png")))
                .with(r -> {r.setMethod("PUT");return r;}).cookie(owner.cookie()).header("X-CSRF-TOKEN",owner.csrf())).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/me/profile/avatar").file(image("png")).file(image("png"))
                .with(r -> {r.setMethod("PUT");return r;}).cookie(owner.cookie()).header("X-CSRF-TOKEN",owner.csrf())).andExpect(status().isBadRequest());
    }

    @Test void apiValidationAndIndependentStreamBoundHaveTruthfulStatuses() throws Exception {
        Browser owner=browser(account(),"ROLE_USER");
        upload(owner,new MockMultipartFile("file","spoof.png","image/png","<svg/>".getBytes(StandardCharsets.US_ASCII))).andExpect(status().isUnsupportedMediaType());
        upload(owner,new MockMultipartFile("file","bad.png","image/png",new byte[] {(byte)137,80,78,71,13,10,26,10})).andExpect(status().isUnprocessableContent());
        upload(owner,new MockMultipartFile("file","bound.png","image/png",AvatarImages.dimensions(4096,4096))).andExpect(status().isUnprocessableContent());
        upload(owner,new MockMultipartFile("file","bad\nname.png","image/png",AvatarImages.image("png"))).andExpect(status().isUnprocessableContent());
        var falseLength=new MockMultipartFile("file","bound.png","image/png",new byte[AvatarValidator.SOURCE_BYTES+1]) {
            @Override public long getSize() {return 0;}
        };
        upload(owner,falseLength).andExpect(status().isContentTooLarge()); assertThat(store.bytes).isEmpty();
    }

    @Test void realServletMultipartBoundRejectsOversizedFileAndRequest() throws Exception {
        assertThat(multipartLimits.getMaxFileSize()).isEqualTo(5242880);
        assertThat(multipartLimits.getMaxRequestSize()).isEqualTo(6291456);
        Browser owner=browser(account(),"ROLE_USER");
        try(var client=HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build()) {
            for(int bytes:new int[] {5242881,6291457}) {
                var out=new java.io.ByteArrayOutputStream();
                out.write("--synthetic-boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"fixture.png\"\r\nContent-Type: image/png\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                out.write(new byte[bytes]); out.write("\r\n--synthetic-boundary--\r\n".getBytes(StandardCharsets.US_ASCII));
                var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/me/profile/avatar"))
                        .timeout(java.time.Duration.ofSeconds(15)).header("Cookie","SESSION="+owner.cookie().getValue())
                        .header("X-CSRF-TOKEN",owner.csrf()).header("Content-Type","multipart/form-data; boundary=synthetic-boundary")
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build();
                var response=client.send(request,HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(413);
                assertThat(response.body()).contains("request_too_large").doesNotContain("fixture.png", "Exception", "object_reference");
            }
        }
    }

    @Test void dbConstraintsRestrictOwnerStateIdentityAndCleanupEvidence() throws Exception {
        UUID one=account(),two=account(); Browser first=browser(one,"ROLE_USER"),second=browser(two,"ROLE_USER");
        upload(first,image("png")).andExpect(status().isOk()); upload(second,image("jpeg")).andExpect(status().isOk());
        UUID asset=selectedId(one),other=selectedId(two);
        assertThatThrownBy(() -> jdbc.update("update profile.profile set selected_avatar_id=? where user_id=?",other,one)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("delete from profile.avatar_asset where avatar_asset_id=?",asset)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("delete from profile.profile where user_id=?",one)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update profile.avatar_asset set state='unknown' where avatar_asset_id=?",asset)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update profile.avatar_asset set state='removed',removed_at=now() where avatar_asset_id=?",asset)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update profile.avatar_asset set profile_id=(select profile_id from profile.profile where user_id=?) where avatar_asset_id=?",two,asset)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update profile.avatar_asset set object_reference=? where avatar_asset_id=?",selectedReference(two),asset)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                insert into profile.avatar_asset(avatar_asset_id,profile_id,object_reference,state,
                    media_type,byte_size,width,height,display_filename,created_at)
                select uuidv7(),profile_id,object_reference,state,media_type,byte_size,width,height,display_filename,created_at
                from profile.avatar_asset where avatar_asset_id=?
                """,asset)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForList("select confdeltype::text from pg_constraint where conrelid='profile.avatar_asset'::regclass and contype='f'",String.class)).containsExactly("r");
        remove(first).andExpect(status().isNoContent());
        assertThatThrownBy(() -> jdbc.update("update profile.profile set selected_avatar_id=? where user_id=?",asset,one)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update profile.avatar_asset set state='validated',removed_at=null,cleaned_at=null where avatar_asset_id=?",asset)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("select to_regclass('profile.public_profile_projection') is null and to_regclass('notes.attachment') is null",Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from pg_tables where schemaname in ('identity','profile','notes','knowledge','publishing','discovery','moderation')",Integer.class)).isEqualTo(18);
    }

    @Test void accountDeletionConsequenceIsAtomicAndDoesNoStorageIo() throws Exception {
        UUID user=account(); Browser owner=browser(user,"ROLE_USER"); upload(owner,image("png")).andExpect(status().isOk());
        UUID selected=selectedId(user); String reference=selectedReference(user); int deletes=store.deletes;
        var tx=new TransactionTemplate(manager);
        assertThatThrownBy(() -> tx.executeWithoutResult(state -> {
            deletion.makeIneligible(user);
            assertThat(selectedId(user)).isNull();
            throw new IllegalStateException("synthetic-consequence-rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(selectedId(user)).isEqualTo(selected); assertThat(store.deletes).isEqualTo(deletes);
        tx.executeWithoutResult(state -> deletion.makeIneligible(user));
        assertThat(selectedId(user)).isNull(); assertThat(store.deletes).isEqualTo(deletes);
        assertThat(store.bytes).containsKey(reference);
        avatars.reconcile(null); assertThat(store.bytes).doesNotContainKey(reference); assertThat(store.sawTransaction).isFalse();
    }

    @Test void sessionRevocationDuringStagingRejectsSwapWithoutPersistingAvatar() throws Exception {
        UUID user=account(); Browser owner=browser(user,"ROLE_USER");
        org.mockito.Mockito.doAnswer(call -> {
            String id=new String(Base64.getDecoder().decode(owner.cookie().getValue()),StandardCharsets.UTF_8);
            sessions.deleteById(id);
            return call.callRealMethod();
        }).when(swaps).replace(any(),any(),any(),any());
        upload(owner,image("png")).andExpect(status().isUnauthorized());
        assertThat(store.bytes).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from profile.profile where user_id=?",Integer.class,user)).isZero();
    }

    @Test void orphanReconciliationWaitsForInFlightStagingAndSwap() throws Exception {
        UUID user=account(); Browser owner=browser(user,"ROLE_USER");
        var staged=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.doAnswer(call -> {
            store.created.replaceAll((reference,time)->clock.instant().minusSeconds(90000));
            staged.countDown();
            if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("Swap release timed out");
            return call.callRealMethod();
        }).when(swaps).replace(any(),any(),any(),any());
        try(var workers=Executors.newFixedThreadPool(2)) {
            var upload=workers.submit(() -> upload(owner,image("png")).andReturn().getResponse().getStatus());
            assertThat(staged.await(10,TimeUnit.SECONDS)).isTrue();
            var entered=new java.util.concurrent.CountDownLatch(1);
            var worker=new java.util.concurrent.atomic.AtomicReference<Thread>();
            var reconciliation=workers.submit(() -> {worker.set(Thread.currentThread());entered.countDown();return avatars.reconcile(null);});
            assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(worker.get().getState()!=Thread.State.WAITING && System.nanoTime()<deadline)Thread.onSpinWait();
            assertThat(worker.get().getState()).isEqualTo(Thread.State.WAITING);
            assertThat(reconciliation.isDone()).isFalse();
            release.countDown();
            assertThat(upload.get(15,TimeUnit.SECONDS)).isEqualTo(200);
            reconciliation.get(15,TimeUnit.SECONDS);
            assertThat(store.bytes).containsOnlyKeys(selectedReference(user));
        } finally {release.countDown();}
    }

    private org.springframework.test.web.servlet.ResultActions upload(Browser browser,MockMultipartFile file) throws Exception {
        return mvc.perform(multipart("/api/me/profile/avatar").file(file).with(r -> {r.setMethod("PUT");return r;})
                .cookie(browser.cookie()).header("X-CSRF-TOKEN",browser.csrf()));
    }
    private org.springframework.test.web.servlet.ResultActions remove(Browser browser) throws Exception {
        return mvc.perform(delete("/api/me/profile/avatar").cookie(browser.cookie()).header("X-CSRF-TOKEN",browser.csrf()));
    }
    private org.springframework.test.web.servlet.ResultActions setProfile(Browser browser) throws Exception {
        String handle="Reader_"+browser.user().toString().replace("-", "").substring(0,12);
        return mvc.perform(put("/api/me/profile").cookie(browser.cookie()).header("X-CSRF-TOKEN",browser.csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"displayName\":\"Reader\",\"biography\":\"Introduction\",\"handle\":\""+handle+"\"}"));
    }
    private MockMultipartFile image(String format) throws Exception {return new MockMultipartFile("file","fixture."+format,"image/"+format,AvatarImages.image(format));}
    private UUID selectedId(UUID user) {return jdbc.queryForObject("select selected_avatar_id from profile.profile where user_id=?",UUID.class,user);}
    private String selectedReference(UUID user) {
        return jdbc.query("select a.object_reference from profile.profile p join profile.avatar_asset a on a.avatar_asset_id=p.selected_avatar_id where p.user_id=?",
                (rs,index)->rs.getString(1),user).stream().findFirst().orElse(null);
    }
    private UUID account() {
        UUID id=jdbc.queryForObject("select uuidv7()",UUID.class);String email="avatar-"+id+"@example.test";
        jdbc.update("insert into identity.account(user_id,canonical_email,display_email,email_verified_at,account_state,created_at,updated_at) values(?,?,?,now(),'active',now(),now())",id,email,email);return id;
    }
    private Browser browser(UUID user,String role) throws Exception {
        Session session=sessions.createSession();var context=SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(new IdentitySessionPrincipal(user),null,List.of(new SimpleGrantedAuthority(role))));
        session.setAttribute("SPRING_SECURITY_CONTEXT",context);save(session);
        Cookie cookie=new Cookie("SESSION",Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8)));
        Browser proof=csrf(cookie);return new Browser(proof.cookie(),proof.csrf(),user);
    }
    private Browser csrf(Cookie cookie) throws Exception {
        var request=get("/api/auth/csrf");if(cookie!=null)request.cookie(cookie);
        var response=mvc.perform(request).andExpect(status().isOk()).andReturn();
        Cookie next=response.getResponse().getCookie("SESSION");return new Browser(next==null?cookie:next,json.readTree(body(response)).get("csrfToken").asText(),null);
    }
    @SuppressWarnings({"rawtypes","unchecked"}) private void save(Session session) {((SessionRepository)sessions).save(session);}
    private String body(MvcResult result) throws Exception {return result.getResponse().getContentAsString();}
    private record Browser(Cookie cookie,String csrf,UUID user) { }

    static class MemoryStore implements AvatarObjectStore {
        final ConcurrentHashMap<String,byte[]> bytes=new ConcurrentHashMap<>();
        final ConcurrentHashMap<String,Instant> created=new ConcurrentHashMap<>();
        volatile boolean failWrite,failDelete,sawTransaction;
        volatile int deletes,lastLimit;
        void reset() {bytes.clear();created.clear();failWrite=false;failDelete=false;sawTransaction=false;deletes=0;lastLimit=0;}
        private void outside() {
            if(TransactionSynchronizationManager.isActualTransactionActive()) {sawTransaction=true;throw new AssertionError("Storage I/O inside database transaction");}
        }
        public void write(String reference,byte[] data) {
            outside();if(bytes.putIfAbsent(reference,data.clone())!=null)throw new AssertionError("Not create-only");
            created.put(reference,Instant.now());
            if(failWrite)throw new IllegalStateException("SYNTHETIC_PRIVATE_DIAGNOSTIC");
        }
        public void delete(String reference) {outside();deletes++;if(failDelete)throw new IllegalStateException("SYNTHETIC_PRIVATE_DIAGNOSTIC");bytes.remove(reference);created.remove(reference);}
        public List<StoredObject> inventoryBefore(Instant cutoff,String after,int limit) {
            outside();lastLimit=limit;return created.entrySet().stream().filter(e->e.getValue().isBefore(cutoff)&&(after==null||e.getKey().compareTo(after)>0))
                    .sorted(java.util.Map.Entry.comparingByKey()).limit(limit).map(e->new StoredObject(e.getKey(),e.getValue())).toList();
        }
    }
    @TestConfiguration(proxyBeanMethods=false) static class Storage {@Bean @Primary MemoryStore syntheticAvatarStore() {return new MemoryStore();}}
}
