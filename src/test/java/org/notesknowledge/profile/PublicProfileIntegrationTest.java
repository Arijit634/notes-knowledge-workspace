package org.notesknowledge.profile;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import jakarta.servlet.http.Cookie;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.notesknowledge.identity.IdentitySessionPrincipal;
import org.notesknowledge.identity.spi.AccountDeletionProfileConsequence;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@Tag("DATABASE") @Tag("API") @Tag("SECURITY")
@Testcontainers @SpringBootTest @AutoConfigureMockMvc @Import(PublicProfileIntegrationTest.Storage.class)
class PublicProfileIntegrationTest {
    @Container static final PostgreSQLContainer postgres=new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18-trixie")
        .withDatabaseName("public_profile").withUsername("profile_migrator").withPassword("synthetic-profile-migrator-password");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",postgres::getJdbcUrl);r.add("spring.datasource.username",postgres::getUsername);r.add("spring.datasource.password",postgres::getPassword);
        r.add("identity.rate.key-base64",()->"AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=");
    }
    @Autowired MockMvc mvc;@Autowired JdbcTemplate jdbc;@Autowired ObjectMapper json;
    @Autowired SessionRepository<? extends Session> sessions;@Autowired PrivateStore privateStore;@Autowired PublicStore store;
    @Autowired PublicAvatarService avatars;@Autowired PublicProfileRepository repository;@Autowired Clock clock;
    @Autowired PlatformTransactionManager manager;@Autowired AccountDeletionProfileConsequence deletion;
    @MockitoBean org.notesknowledge.security.RateLimitPort rates;
    @MockitoSpyBean PublicProfileTransactions transactions;
    @MockitoSpyBean org.notesknowledge.PublicExposureCoordinator exposure;
    @BeforeEach void reset(){org.mockito.Mockito.when(rates.evaluate(any())).thenReturn(new org.notesknowledge.security.RateLimitPort.Allowed());
        privateStore.bytes.clear();privateStore.opens=0;store.bytes.clear();store.created.clear();store.failWrite=false;store.failDelete=false;store.onWrite=()->{};store.onOpen=()->{};}

    @Test void explicitProjectionHasOnlyAllowlistedFieldsAndRequiresHandle() throws Exception {
        var b=browser(account());activate(b).andExpect(status().isConflict());profile(b,null,"Private");activate(b).andExpect(status().isConflict());
        profile(b,handle(b),"Reader");var active=activate(b).andExpect(status().isOk()).andReturn();
        assertThat(body(active)).doesNotContain(b.user.toString(),"email","profileId","sourceAvatar","object_reference");
        assertThat(json.readTree(body(active)).get("avatarUrl").isNull()).isTrue();
        mvc.perform(get("/api/public/profiles/"+handle(b))).andExpect(status().isOk());
        profile(b,handle(b),"Later private name");var read=mvc.perform(get("/api/public/profiles/"+handle(b))).andReturn();
        assertThat(body(read)).contains("Reader").doesNotContain("Later private name");
        activate(b).andExpect(status().isOk());assertThat(body(mvc.perform(get("/api/public/profiles/"+handle(b))).andReturn())).contains("Later private name");
    }
    @Test void independentCopySurvivesPrivateReplacementRemovalAndCleanupUntilExplicitRefresh() throws Exception {
        var b=ready();upload(b,"png");String url=avatarUrl(activate(b).andExpect(status().isOk()).andReturn());
        byte[] bytes=mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        int opens=privateStore.opens;String oldPrivate=privateStore.bytes.keySet().iterator().next();
        upload(b,"jpeg");assertThat(privateStore.bytes).doesNotContainKey(oldPrivate);
        assertThat(mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).isEqualTo(bytes);
        assertThat(privateStore.opens).isEqualTo(opens);
        String newer=avatarUrl(activate(b).andExpect(status().isOk()).andReturn());assertThat(newer).isNotEqualTo(url);
        mvc.perform(get(url)).andExpect(status().isNotFound());mvc.perform(get(newer)).andExpect(status().isOk());
        mvc.perform(delete("/api/me/profile/avatar").cookie(b.cookie).header("X-CSRF-TOKEN",b.csrf)).andExpect(status().isNoContent());
        assertThat(privateStore.bytes).isEmpty();mvc.perform(get(newer)).andExpect(status().isOk());
        assertThat(json.readTree(body(activate(b).andExpect(status().isOk()).andReturn())).get("avatarUrl").isNull()).isTrue();
        mvc.perform(get(newer)).andExpect(status().isNotFound());
    }
    @Test void pairingMissingCopyAndInactiveAccountArePublicSafe404() throws Exception {
        var b=ready();upload(b,"png");String url=avatarUrl(activate(b).andExpect(status().isOk()).andReturn());
        var good=mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(good.getContentType()).isEqualTo("image/png");assertThat(good.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(good.getHeader("Cache-Control")).isEqualTo("no-store");assertThat(good.getHeader("Accept-Ranges")).isNull();
        assertThat(good.getContentLength()).isEqualTo(good.getContentAsByteArray().length);
        mvc.perform(get(url.replace(handle(b),"unknown_profile"))).andExpect(status().isNotFound());
        mvc.perform(get(url.replace(url.split("/")[6],UUID.randomUUID().toString()))).andExpect(status().isNotFound());
        var saved=new HashMap<>(store.bytes);store.bytes.clear();mvc.perform(get(url)).andExpect(status().isNotFound());store.bytes.putAll(saved);
        new TransactionTemplate(manager).executeWithoutResult(t->deletion.makeIneligible(b.user));
        mvc.perform(get(url)).andExpect(status().isNotFound());mvc.perform(get("/api/public/profiles/"+handle(b))).andExpect(status().isNotFound());
    }
    @Test void sourceChangedAfterCopyFailsClosedKeepingOldProjection() throws Exception {
        var b=ready();upload(b,"png");String old=avatarUrl(activate(b).andReturn());upload(b,"jpeg");
        store.onWrite=()->jdbc.update("update profile.profile set selected_avatar_id=null,updated_at=now() where user_id=?",b.user);
        activate(b).andExpect(status().isPreconditionFailed());mvc.perform(get(old)).andExpect(status().isOk());assertThat(store.bytes).hasSize(1);
    }
    @Test void copyFailureAndDatabaseRollbackKeepOldProjection() throws Exception {
        var b=ready();upload(b,"png");String old=avatarUrl(activate(b).andReturn());upload(b,"jpeg");
        store.failWrite=true;activate(b).andExpect(status().isServiceUnavailable());store.failWrite=false;
        mvc.perform(get(old)).andExpect(status().isOk());assertThat(store.bytes).hasSize(1);
        org.mockito.Mockito.doAnswer(c->{c.callRealMethod();throw new org.springframework.dao.DataAccessResourceFailureException("synthetic rollback");})
            .when(transactions).activate(any(),any(),any(),any());
        activate(b).andExpect(status().isServiceUnavailable());mvc.perform(get(old)).andExpect(status().isOk());assertThat(store.bytes).hasSize(1);
    }
    @Test void concurrentRefreshesCannotOverwriteNewerProjection() throws Exception {
        var b=ready();upload(b,"png");activate(b).andExpect(status().isOk());upload(b,"jpeg");var barrier=new CyclicBarrier(2);
        store.onWrite=()->{try{barrier.await(10,TimeUnit.SECONDS);}catch(Exception e){throw new AssertionError(e);}};
        try(var workers=Executors.newFixedThreadPool(2)) {
            var a=workers.submit(()->activate(b).andReturn().getResponse().getStatus());var c=workers.submit(()->activate(b).andReturn().getResponse().getStatus());
            assertThat(List.of(a.get(20,TimeUnit.SECONDS),c.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,412);
        }
        assertThat(jdbc.queryForObject("select projection_generation from profile.public_profile_projection where user_id=?",Long.class,b.user)).isEqualTo(2);
        assertThat(store.bytes).hasSize(1);
    }
    @Test void cleanupFailureCannotRestoreOldCopyAndReconciliationPreservesCurrentReference() throws Exception {
        var b=ready();upload(b,"png");String old=avatarUrl(activate(b).andReturn());upload(b,"jpeg");store.failDelete=true;
        String current=avatarUrl(activate(b).andExpect(status().isOk()).andReturn());mvc.perform(get(old)).andExpect(status().isNotFound());
        assertThat(store.bytes).hasSize(2);store.failDelete=false;store.created.replaceAll((k,v)->clock.instant().minusSeconds(90000));
        avatars.reconcile(null);assertThat(store.bytes).hasSize(1);mvc.perform(get(current)).andExpect(status().isOk());
    }
    @Test void unsafeActivationRequiresFullSessionAndCsrf() throws Exception {
        var b=ready();mvc.perform(put("/api/me/public-profile").cookie(b.cookie)).andExpect(status().isForbidden());
        mvc.perform(put("/api/me/public-profile")).andExpect(status().isForbidden());
        var pre=browser(account(),"ROLE_PRE_MFA");activate(pre).andExpect(status().isForbidden());
    }
    @Test void deliveryLeaseOrdersBytesBeforeCommittedInactivationAndThenDeniesOldUrl()throws Exception {
        var b=ready();upload(b,"png");String url=avatarUrl(activate(b).andReturn());
        var readEntered=new CountDownLatch(1);var releaseRead=new CountDownLatch(1);
        store.onOpen=()->{readEntered.countDown();await(releaseRead);};
        try(var workers=Executors.newFixedThreadPool(2)) {
            var read=workers.submit(()->mvc.perform(get(url)).andReturn());assertThat(readEntered.await(10,TimeUnit.SECONDS)).isTrue();
            var deny=workers.submit(()->new TransactionTemplate(manager).executeWithoutResult(t->deletion.makeIneligible(b.user)));
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).until(()->jdbc.queryForObject(
                "select exists(select 1 from pg_locks where locktype='advisory' and mode='ExclusiveLock' and not granted)",Boolean.class));
            assertThat(deny.isDone()).isFalse();releaseRead.countDown();assertThat(read.get(15,TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
            deny.get(15,TimeUnit.SECONDS);store.onOpen=()->{};mvc.perform(get(url)).andExpect(status().isNotFound());
        }finally{releaseRead.countDown();}
    }
    @Test void activeCopiedHandleCannotBeStolenWhenPrivateOwnerChangesHandle()throws Exception {
        var first=ready();activate(first).andExpect(status().isOk());String old=handle(first);
        profile(first,"replacement_"+first.user.toString().replace("-","").substring(0,12),"New private handle");
        var next=browser(account());profile(next,old,"Another owner");activate(next).andExpect(status().isConflict());
        assertThat(body(mvc.perform(get("/api/public/profiles/"+old)).andExpect(status().isOk()).andReturn())).contains("Reader").doesNotContain("Another owner");
    }
    @Test void corruptOrUnboundedPrivateCopyCannotReplaceApprovedPublicAvatar()throws Exception {
        var b=ready();upload(b,"png");String old=avatarUrl(activate(b).andReturn());upload(b,"jpeg");
        String selected=privateStore.bytes.keySet().iterator().next();byte[] canonical=privateStore.bytes.get(selected);
        privateStore.bytes.put(selected,new byte[AvatarValidator.OUTPUT_BYTES+1]);activate(b).andExpect(status().isServiceUnavailable());
        privateStore.bytes.put(selected,new byte[canonical.length]);activate(b).andExpect(status().isServiceUnavailable());
        mvc.perform(get(old)).andExpect(status().isOk());assertThat(store.bytes).hasSize(1);
    }
    private static void await(CountDownLatch latch){try{if(!latch.await(15,TimeUnit.SECONDS))throw new AssertionError("Synthetic coordination timed out");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
    private Browser ready()throws Exception{var b=browser(account());profile(b,handle(b),"Reader");return b;}
    private String handle(Browser b){return "reader_"+b.user.toString().replace("-","").substring(0,12);}
    private void profile(Browser b,String handle,String name)throws Exception{mvc.perform(put("/api/me/profile").cookie(b.cookie).header("X-CSRF-TOKEN",b.csrf)
        .contentType("application/json").content(json.writeValueAsString(new HashMap<String,Object>(){{put("displayName",name);put("biography","Synthetic biography");put("handle",handle);}}))).andExpect(status().isOk());}
    private void upload(Browser b,String format)throws Exception{mvc.perform(multipart("/api/me/profile/avatar").file(new MockMultipartFile("file","synthetic."+format,"image/"+format,AvatarImages.image(format)))
        .with(r->{r.setMethod("PUT");return r;}).cookie(b.cookie).header("X-CSRF-TOKEN",b.csrf)).andExpect(status().isOk());}
    private ResultActions activate(Browser b)throws Exception{return mvc.perform(put("/api/me/public-profile").cookie(b.cookie).header("X-CSRF-TOKEN",b.csrf));}
    private String avatarUrl(MvcResult r)throws Exception{return json.readTree(body(r)).get("avatarUrl").asText();}
    private String body(MvcResult r)throws Exception{return r.getResponse().getContentAsString();}
    private UUID account(){var id=jdbc.queryForObject("select uuidv7()",UUID.class);String email="public-profile-"+id+"@example.test";
        jdbc.update("insert into identity.account(user_id,canonical_email,display_email,email_verified_at,account_state,created_at,updated_at) values(?,?,?,now(),'active',now(),now())",id,email,email);return id;}
    private Browser browser(UUID user)throws Exception{return browser(user,"ROLE_USER");}
    private Browser browser(UUID user,String role)throws Exception{
        Session session=sessions.createSession();var context=SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(new IdentitySessionPrincipal(user),null,List.of(new SimpleGrantedAuthority(role))));
        session.setAttribute("SPRING_SECURITY_CONTEXT",context);save(session);
        Cookie cookie=new Cookie("SESSION",Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8)));
        var r=mvc.perform(get("/api/auth/csrf").cookie(cookie)).andExpect(status().isOk()).andReturn();
        return new Browser(cookie,json.readTree(body(r)).get("csrfToken").asText(),user);
    }
    @SuppressWarnings({"rawtypes","unchecked"}) private void save(Session s){((SessionRepository)sessions).save(s);}
    record Browser(Cookie cookie,String csrf,UUID user){ }
    static class PrivateStore implements AvatarObjectStore {
        final ConcurrentHashMap<String,byte[]> bytes=new ConcurrentHashMap<>();int opens;
        private void outside(){assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();}
        public void write(String ref,byte[] data){outside();assertThat(bytes.putIfAbsent(ref,data.clone())).isNull();}
        public InputStream open(String ref,long size){outside();opens++;var data=bytes.get(ref);return data==null?null:new ByteArrayInputStream(data);}
        public void delete(String ref){outside();bytes.remove(ref);}
        public List<StoredObject> inventoryBefore(Instant cutoff,String after,int limit){outside();return List.of();}
    }
    static class PublicStore implements PublicAvatarObjectStore {
        final ConcurrentHashMap<String,byte[]> bytes=new ConcurrentHashMap<>();final ConcurrentHashMap<String,Instant> created=new ConcurrentHashMap<>();
        volatile boolean failWrite,failDelete;volatile Runnable onWrite=()->{},onOpen=()->{};
        private void outside(){assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();}
        public void write(String ref,byte[] data){outside();assertThat(bytes.putIfAbsent(ref,data.clone())).isNull();created.put(ref,Instant.now());onWrite.run();if(failWrite)throw new IllegalStateException("synthetic storage failure");}
        public InputStream open(String ref,long size){outside();var data=bytes.get(ref);onOpen.run();return data==null?null:new ByteArrayInputStream(data);}
        public void delete(String ref){outside();if(failDelete)throw new IllegalStateException("synthetic cleanup failure");bytes.remove(ref);created.remove(ref);}
        public List<StoredObject> inventoryBefore(Instant cutoff,String after,int limit){outside();return created.entrySet().stream()
            .filter(e->e.getValue().isBefore(cutoff)&&(after==null||e.getKey().compareTo(after)>0)).sorted(Map.Entry.comparingByKey()).limit(limit)
            .map(e->new StoredObject(e.getKey(),e.getValue())).toList();}
    }
    @TestConfiguration(proxyBeanMethods=false) static class Storage {
        @Bean @Primary PrivateStore privateAvatar(){return new PrivateStore();}
        @Bean @Primary PublicStore publicAvatar(){return new PublicStore();}
    }
}
