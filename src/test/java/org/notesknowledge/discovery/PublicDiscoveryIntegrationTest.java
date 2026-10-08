package org.notesknowledge.discovery;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.notesknowledge.PublicDiscoveryFixtures;
import org.notesknowledge.identity.IdentitySessionPrincipal;
import org.notesknowledge.security.RateLimitPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.session.*;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.*;

@Tag("DATABASE") @Tag("API") @Tag("SECURITY") @Tag("RETRIEVAL")
@Testcontainers @SpringBootTest @AutoConfigureMockMvc
class PublicDiscoveryIntegrationTest {
    @Container static final PostgreSQLContainer postgres=new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18-trixie").withDatabaseName("discovery_synthetic").withUsername("synthetic_migrator").withPassword("synthetic-discovery-password");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",postgres::getJdbcUrl);r.add("spring.datasource.username",postgres::getUsername);r.add("spring.datasource.password",postgres::getPassword);
        r.add("identity.rate.key-base64",()->"AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=");
    }
    @Autowired MockMvc mvc;@Autowired JdbcTemplate jdbc;@Autowired ObjectMapper json;@Autowired Clock clock;
    @Autowired SessionRepository<? extends Session> sessions;@Autowired PlatformTransactionManager manager;
    @MockitoBean RateLimitPort rates;
    @BeforeEach void reset(){PublicDiscoveryFixtures.retireAll(jdbc);org.mockito.Mockito.when(rates.evaluate(any())).thenReturn(new RateLimitPort.Allowed());}
    private PublicDiscoveryFixtures.PublicRow publication(String title,String body,String tag){return PublicDiscoveryFixtures.publication(jdbc,title,body,tag,clock.instant().minusSeconds(3600));}
    @Test void onlyCurrentCopiedPublicStateIsRankedBeforeAndAfterDenial()throws Exception {
        var good=publication("Public observatory","Public astronomy", "science");
        var stale=publication("Stale observatory","Old public snapshot", "science");
        jdbc.update("update publishing.publication set publication_generation=2,snapshot_revision=2,title='New public' where publication_id=?",stale.id());
        var deleted=publication("Deleted author","Public astronomy", "science");
        jdbc.update("update identity.account set account_state='logically_deleted',updated_at=now() where user_id=?",deleted.owner());
        var hidden=publication("Hidden profile","Public astronomy", "science");
        jdbc.update("update profile.public_profile_projection set active=false,projection_generation=2 where public_profile_projection_id=?",hidden.author());
        UUID privateNote=jdbc.queryForObject("insert into notes.note(note_id,owner_user_id,title,markdown,lifecycle_state,revision,ai_enabled,ai_generation,created_at,updated_at) values(uuidv7(),?,'PRIVATE_DISCOVERY_CANARY','Private observatory hidden words','active',1,false,1,now(),now()) returning note_id",UUID.class,good.owner());
        var found=read("/api/public/search?q=observatory");
        assertThat(found.get("items").size()).isEqualTo(1);assertThat(found.toString()).contains(good.id().toString()).doesNotContain(stale.id().toString(),deleted.id().toString(),hidden.id().toString(),privateNote.toString(),good.owner().toString(),"PRIVATE_DISCOVERY_CANARY","source_note","object_reference");
        jdbc.update("update publishing.publication set availability='unpublished',publication_generation=2,unpublished_at=now() where publication_id=?",good.id());
        // A maliciously retained active index does not constitute authority.
        assertThat(read("/api/public/search?q=observatory").get("items").size()).isZero();
        assertThat(read("/api/public/explore").get("items").size()).isZero();
    }
    @Test void tagOnlyCombinedNormalizationFuzzyAndTruthfulProviderUnavailable()throws Exception {
        var movie=publication("Google films","Watch interstellar later https://example.test/a?q=C++", "Films");
        publication("Google work","Other public text", "work");
        var normalized=json.readTree(mvc.perform(get("/api/public/search").param("tag"," Ｆｉｌｍｓ ")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(normalized.get("items").get(0).get("id").asText()).isEqualTo(movie.id().toString());
        var found=read("/api/public/search?q=goohle&tag=films");assertThat(found.get("items").size()).isEqualTo(1);
        assertThat(found.get("rankingMode").asText()).isEqualTo("lexical_fuzzy");assertThat(found.get("semanticAvailable").asBoolean()).isFalse();assertThat(found.get("exhaustive").asBoolean()).isFalse();
        assertThat(read("/api/public/search?q=interstellar&tag=work").get("items").size()).isZero();
        mvc.perform(get("/api/public/search")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/public/search").param("q","x".repeat(401))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/public/search").param("tag","x".repeat(65))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/public/search").param("q","films").param("provider","invented")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/public/explore").param("sort","random")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/public/explore").param("limit","101")).andExpect(status().isBadRequest());
    }
    @Test void latestPaginationAndScopedCursorRejectTamperFilterAndStaleGeneration()throws Exception {
        var a=publication("A","Copied public", "films");var b=publication("B","Copied public", "films");
        var first=read("/api/public/explore?limit=1");String cursor=first.get("nextCursor").asText();
        var second=read("/api/public/explore?limit=1&cursor="+cursor);
        assertThat(first.get("items").get(0).get("id").asText()).isEqualTo(b.id().toString());assertThat(second.get("items").get(0).get("id").asText()).isEqualTo(a.id().toString());
        mvc.perform(get("/api/public/explore").param("sort","trending").param("cursor",cursor)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/public/search").param("tag","films").param("cursor",cursor)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/public/explore").param("cursor",cursor+"x")).andExpect(status().isBadRequest());
        jdbc.update("update publishing.publication set publication_generation=2,snapshot_revision=2 where publication_id=?",b.id());
        mvc.perform(get("/api/public/explore").param("cursor",cursor)).andExpect(status().isBadRequest());
    }
    @Test void trendingUsesSaturatedAggregatesWithStableTies()throws Exception {
        var a=publication("Ordinary","Public", "films");var b=publication("Popular","Public", "films");
        jdbc.update("update discovery.publication_projection set like_count=10,view_count=100 where publication_id=?",b.id());
        assertThat(read("/api/public/explore?sort=trending").get("items").get(0).get("id").asText()).isEqualTo(b.id().toString());
        assertThat(PublicDiscoveryService.trending(Long.MAX_VALUE,Long.MAX_VALUE,clock.instant(),clock.instant())).isFinite();
        assertThat(PublicDiscoveryService.trending(Long.MAX_VALUE,Long.MAX_VALUE,clock.instant(),clock.instant())).isEqualTo(PublicDiscoveryService.trending(10000,100000,clock.instant(),clock.instant()));
        assertThat(read("/api/public/explore?sort=trending").toString()).doesNotContain("confidence","uniqueViewers",a.owner().toString());
    }
    @Test void likesAreIdempotentIsolatedAndViewerCompositionIsNotCached()throws Exception {
        var p=publication("Public","Copied content","films");var a=browser(PublicDiscoveryFixtures.account(jdbc));var b=browser(PublicDiscoveryFixtures.account(jdbc));
        mutate(a,p.id(),true);mutate(a,p.id(),true);mutate(b,p.id(),true);
        assertThat(jdbc.queryForObject("select count(*) from discovery.publication_like where publication_id=?",Integer.class,p.id())).isEqualTo(2);
        assertThat(readPublic(p.id(),a).get("viewerLiked").asBoolean()).isTrue();
        mutate(a,p.id(),false);mutate(a,p.id(),false);
        assertThat(readPublic(p.id(),a).get("viewerLiked").asBoolean()).isFalse();assertThat(readPublic(p.id(),b).get("viewerLiked").asBoolean()).isTrue();
        assertThat(read("/api/public/publications/"+p.id()).has("viewerLiked")).isFalse();
        assertThat(jdbc.queryForObject("select like_count from discovery.publication_projection where publication_id=?",Long.class,p.id())).isEqualTo(1);
        mvc.perform(put(likePath(p.id())).cookie(a.cookie)).andExpect(status().isForbidden());
        mvc.perform(put(likePath(p.id())).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())).andExpect(status().isUnauthorized());
        jdbc.update("update publishing.publication set availability='unpublished',publication_generation=2,unpublished_at=now() where publication_id=?",p.id());
        mvc.perform(delete(likePath(p.id())).cookie(a.cookie).header("X-CSRF-TOKEN",a.csrf)).andExpect(status().isNotFound());
    }
    @Test void concurrentDuplicateLikeCommitsOneRelationAndOneCount()throws Exception {
        var p=publication("Concurrent","Public", "films");var browser=browser(PublicDiscoveryFixtures.account(jdbc));var ready=new CountDownLatch(2);var release=new CountDownLatch(1);
        try(var workers=Executors.newFixedThreadPool(2)) {
            var tasks=new ArrayList<Future<Integer>>();for(int i=0;i<2;i++)tasks.add(workers.submit(()->{ready.countDown();await(release);return mvc.perform(put(likePath(p.id())).cookie(browser.cookie).header("X-CSRF-TOKEN",browser.csrf)).andReturn().getResponse().getStatus();}));
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();release.countDown();
            assertThat(tasks.get(0).get(15,TimeUnit.SECONDS)).isEqualTo(204);assertThat(tasks.get(1).get(15,TimeUnit.SECONDS)).isEqualTo(204);
        }finally{release.countDown();}
        assertThat(jdbc.queryForObject("select count(*) from discovery.publication_like where publication_id=?",Integer.class,p.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select like_count from discovery.publication_projection where publication_id=?",Long.class,p.id())).isEqualTo(1);
    }
    @Test void denialWinningPublicationLockPreventsRacingLikeVisibility()throws Exception {
        var p=publication("Race","Public", "films");var browser=browser(PublicDiscoveryFixtures.account(jdbc));var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var workers=Executors.newFixedThreadPool(2)) {
            var deny=workers.submit(()->new TransactionTemplate(manager).executeWithoutResult(t->{jdbc.queryForObject("select publication_id from publishing.publication where publication_id=? for update",UUID.class,p.id());locked.countDown();await(release);
                jdbc.update("update publishing.publication set availability='unpublished',publication_generation=2,unpublished_at=now() where publication_id=?",p.id());jdbc.update("update discovery.publication_projection set active=false,publication_generation=2 where publication_id=?",p.id());}));
            assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();var like=workers.submit(()->mvc.perform(put(likePath(p.id())).cookie(browser.cookie).header("X-CSRF-TOKEN",browser.csrf)).andReturn().getResponse().getStatus());
            release.countDown();deny.get(15,TimeUnit.SECONDS);assertThat(like.get(15,TimeUnit.SECONDS)).isEqualTo(404);
        }finally{release.countDown();}
        assertThat(read("/api/public/explore").get("items").size()).isZero();
    }
    @Test void rateControlUnavailableFailsSafelyAndThrottlingHasRetryAfter()throws Exception {
        org.mockito.Mockito.when(rates.evaluate(any())).thenReturn(new RateLimitPort.ControlUnavailable());mvc.perform(get("/api/public/explore")).andExpect(status().isServiceUnavailable());
        org.mockito.Mockito.when(rates.evaluate(any())).thenReturn(new RateLimitPort.Throttled(7));var r=mvc.perform(get("/api/public/search").param("q","films")).andExpect(status().isTooManyRequests()).andReturn();assertThat(r.getResponse().getHeader("Retry-After")).isEqualTo("7");
    }
    private JsonNode read(String path)throws Exception {var r=mvc.perform(get(path)).andExpect(status().isOk()).andReturn();assertThat(r.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");return json.readTree(r.getResponse().getContentAsString());}
    private JsonNode readPublic(UUID id,Browser b)throws Exception{return json.readTree(mvc.perform(get("/api/public/publications/"+id).cookie(b.cookie)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());}
    private void mutate(Browser b,UUID id,boolean like)throws Exception{mvc.perform((like?put(likePath(id)):delete(likePath(id))).cookie(b.cookie).header("X-CSRF-TOKEN",b.csrf)).andExpect(status().isNoContent());}
    private static String likePath(UUID id){return "/api/public/publications/"+id+"/like";}
    private Browser browser(UUID user)throws Exception {
        Session s=sessions.createSession();var context=SecurityContextHolder.createEmptyContext();context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(new IdentitySessionPrincipal(user),null,List.of(new SimpleGrantedAuthority("ROLE_USER"))));s.setAttribute("SPRING_SECURITY_CONTEXT",context);save(s);
        var cookie=new Cookie("SESSION",Base64.getEncoder().encodeToString(s.getId().getBytes(StandardCharsets.UTF_8)));var r=mvc.perform(get("/api/auth/csrf").cookie(cookie)).andExpect(status().isOk()).andReturn();return new Browser(cookie,json.readTree(r.getResponse().getContentAsString()).get("csrfToken").asText());
    }
    @SuppressWarnings({"rawtypes","unchecked"}) private void save(Session s){((SessionRepository)sessions).save(s);}
    record Browser(Cookie cookie,String csrf){@Override public String toString(){return "SyntheticBrowser[REDACTED]";}}
    private static void await(CountDownLatch latch){try{if(!latch.await(15,TimeUnit.SECONDS))throw new AssertionError("Synthetic barrier timed out");}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new AssertionError(failure);}}
}
