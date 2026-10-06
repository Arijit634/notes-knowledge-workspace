package org.notesknowledge.notes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.notesknowledge.identity.IdentitySessionPrincipal;
import org.notesknowledge.security.RateLimitPort;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;

@Tag("DATABASE") @Tag("API") @Tag("SECURITY") @Tag("RETRIEVAL")
@Testcontainers @SpringBootTest @AutoConfigureMockMvc
@org.springframework.context.annotation.Import(PrivateNoteSearchIntegrationTest.TimeConfiguration.class)
class PrivateNoteSearchIntegrationTest {
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18-trixie")
            .withDatabaseName("ordinary_search").withUsername("search_migrator")
            .withPassword("synthetic-search-migrator-password");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("notes.cursor.active.version", () -> "test1");
        r.add("notes.cursor.active.key-base64", () -> "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=");
        r.add("identity.rate.key-base64", () -> "AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=");
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired SessionRepository<? extends Session> sessions;
    @Autowired TestClock clock;
    @MockitoBean RateLimitPort rates;
    @MockitoSpyBean NotesSearchRepository repository;
    @MockitoSpyBean(name="googleOidcProtocolAdapter") Object provider;
    @BeforeEach void resetState() {
        clock.offset = Duration.ZERO;
        reset(repository, rates);
        clearInvocations(provider);
        when(rates.evaluate(any())).thenReturn(new RateLimitPort.Allowed());
    }

    @Test void lexicalSignalsStemmingTyposShorthandUnicodeCodeUrlsAndAiOffAreUseful() throws Exception {
        var b = browser(account(), "ROLE_USER");
        var title = create(b,"Google travel notebook", "Running runners planning a journey. Café 東京 résumé `ClientFactory` https://example.test/saved/path");
        var body = create(b,"Other notebook", "Google travel notebook");
        var alias = create(b,"PlayStation", "Synthetic login reminder");
        String id = id(title);
        assertThat(ids(search(b, Map.of("query","google travel notebook")))).containsExactly(id,id(body));
        for (String q : List.of("running", "run", "goohle", "café", "東京", "ClientFactory", "https://example.test/saved/path")) {
            assertThat(ids(search(b,Map.of("query",q)))).as(q).contains(id);
        }
        assertThat(ids(search(b,Map.of("query","ps")))).contains(id(alias));
        var page = search(b,Map.of("query","café"));
        assertThat(page.get("items").get(0).get("matchLabels").toString()).contains("body");
        assertThat(page.toString()).doesNotContain("markdown", "ownerUserId", "search_text", "rank", "revision", "aiGeneration");
        assertThat(jdbc.queryForObject("select ai_enabled from notes.note where note_id=?::uuid",Boolean.class,id)).isFalse();
        verifyNoInteractions(provider);
        assertThat(jdbc.queryForObject("select count(*) from pg_tables where schemaname='knowledge'",Integer.class)).isEqualTo(3);
    }

    @Test void everyCandidateBudgetIsOwnerScopedAndLifecycleScoped() throws Exception {
        UUID owner = account(), foreign = account();
        var b = browser(owner,"ROLE_USER");
        String own = id(create(b,"Isolationsignal","Short"));
        // Stronger foreign content exceeds every signal budget; it cannot starve the owner's result.
        jdbc.update("""
                insert into notes.note (note_id,owner_user_id,title,markdown,lifecycle_state,ai_enabled,
                    revision,ai_generation,created_at,updated_at)
                select uuidv7(), ?, 'Isolationsignal Isolationsignal Isolationsignal', 'Isolationsignal',
                    'active',false,1,1,now(),now() from generate_series(1,125)
                """,foreign);
        assertThat(ids(search(b,Map.of("query","isolationsignal")))).containsExactly(own);
        assertThat(ids(search(browser(foreign,"ROLE_USER"),Map.of("query","isolationsignal","limit",50)))).hasSize(50);
        jdbc.update("update notes.note set lifecycle_state='archived' where note_id=?::uuid",own);
        assertThat(ids(search(b,Map.of("query","isolationsignal")))).isEmpty();
        assertThat(ids(search(b,Map.of("query","isolationsignal","lifecycle","archived")))).containsExactly(own);
        jdbc.update("update notes.note set lifecycle_state='trashed',pre_trash_state='archived',trashed_at=now() where note_id=?::uuid",own);
        assertThat(ids(search(b,Map.of("query","isolationsignal","lifecycle","trashed")))).containsExactly(own);
        jdbc.update("update notes.note set lifecycle_state='logically_deleted',pre_trash_state=null,trashed_at=null,deleted_at=now() where note_id=?::uuid",own);
        assertThat(ids(search(b,Map.of("query","isolationsignal","lifecycle","trashed")))).isEmpty();
    }

    @Test void tagsAreDistinctSignalAndAllTagsFilterTracksReplacementImmediately() throws Exception {
        var b = browser(account(),"ROLE_USER");
        var n = create(b,"Unrelated title","Unrelated body");
        var tagged = tags(b,id(n),n.getResponse().getHeader("ETag"),List.of("Travel","Japan"));
        var page = search(b,Map.of("query","travel","tags",List.of("TRAVEL","japan")));
        assertThat(ids(page)).containsExactly(id(n));
        assertThat(page.get("items").get(0).get("matchLabels").toString()).contains("tag");
        assertThat(ids(search(b,Map.of("query","travel","tags",List.of("Travel","Missing"))))).isEmpty();
        tags(b,id(n),tagged.getResponse().getHeader("ETag"),List.of("Cooking"));
        assertThat(ids(search(b,Map.of("query","travel")))).isEmpty();
        assertThat(ids(search(b,Map.of("query","cooking")))).containsExactly(id(n));
    }

    @Test void currentProjectionFollowsSaveAndCheckpointRestoreWithoutSearchingHistory() throws Exception {
        var b = browser(account(),"ROLE_USER");
        var n = create(b,"Orchidword", "# Original **orchidword**");
        String id = id(n);
        UUID v = jdbc.queryForObject("""
                insert into notes.note_version(note_version_id,note_id,owner_user_id,title,markdown,
                    source_revision,checkpoint_kind,created_at)
                select uuidv7(),note_id,owner_user_id,title,markdown,revision,'policy',now()
                from notes.note where note_id=?::uuid returning note_version_id
                """,UUID.class,id);
        var saved = mvc.perform(put("/api/notes/{id}",id).cookie(b.cookie).header("X-CSRF-TOKEN",b.csrf)
                .header("If-Match",n.getResponse().getHeader("ETag")).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("title","Violetword","markdown","Violetword"))))
                .andExpect(status().isOk()).andReturn();
        assertThat(ids(search(b,Map.of("query","orchidword")))).isEmpty();
        assertThat(ids(search(b,Map.of("query","violetword")))).containsExactly(id);
        mvc.perform(post("/api/notes/{id}/versions/{v}/restore",id,v).cookie(b.cookie)
                .header("X-CSRF-TOKEN",b.csrf).header("If-Match",saved.getResponse().getHeader("ETag"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"confirmRestore\":true}"))
                .andExpect(status().isOk());
        assertThat(ids(search(b,Map.of("query","orchidword")))).containsExactly(id);
        assertThat(ids(search(b,Map.of("query","violetword")))).isEmpty();
        assertThat(jdbc.queryForObject("select revision from notes.note where note_id=?::uuid",Long.class,id)).isEqualTo(3L);
    }

    @Test void snippetsAreBoundedPlainTextAndSourceOffsetsAreNotApiAuthority() throws Exception {
        var b=browser(account(),"ROLE_USER");
        create(b,"Snippet","filler ".repeat(100)+" **needleword** <script>unsafeMarkup</script> after");
        var item=search(b,Map.of("query","needleword")).get("items").get(0);
        assertThat(item.get("snippet").asText()).hasSizeLessThanOrEqualTo(240).contains("needleword").doesNotContain("<script>","**");
        assertThat(item.toString()).doesNotContain("sourceStart","sourceEnd");
    }

    @Test void maximumBodyCreateSaveRestoreAndFiftyHitPageRemainBounded() throws Exception {
        var b=browser(account(),"ROLE_USER");
        String large=NotesSearchMigrationTest.largeBody("largeprefixneedle");
        var note=create(b,"Large fixture",large);
        String id=id(note);
        UUID version=jdbc.queryForObject("""
                insert into notes.note_version(note_version_id,note_id,owner_user_id,title,markdown,source_revision,checkpoint_kind,created_at)
                select uuidv7(),note_id,owner_user_id,title,markdown,revision,'policy',now() from notes.note where note_id=?::uuid
                returning note_version_id
                """,UUID.class,id);
        String savedBody=NotesSearchMigrationTest.largeBody("savedprefixneedle");
        var saved=mvc.perform(put("/api/notes/{id}",id).cookie(b.cookie).header("X-CSRF-TOKEN",b.csrf)
                .header("If-Match",note.getResponse().getHeader("ETag")).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("title","Large fixture","markdown",savedBody))))
                .andExpect(status().isOk()).andReturn();
        assertThat(jdbc.queryForObject("select markdown from notes.note where note_id=?::uuid",String.class,id)).isEqualTo(savedBody);
        assertThat(ids(search(b,Map.of("query","savedprefixneedle")))).containsExactly(id);
        mvc.perform(post("/api/notes/{id}/versions/{v}/restore",id,version).cookie(b.cookie)
                .header("X-CSRF-TOKEN",b.csrf).header("If-Match",saved.getResponse().getHeader("ETag"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"confirmRestore\":true}"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select markdown from notes.note where note_id=?::uuid",String.class,id)).isEqualTo(large);
        assertThat(ids(search(b,Map.of("query","largeprefixneedle")))).containsExactly(id);
        assertThat(ids(search(b,Map.of("query","beyondprojectionneedle")))).isEmpty();
        jdbc.update("""
                insert into notes.note(note_id,owner_user_id,title,markdown,lifecycle_state,ai_enabled,revision,ai_generation,created_at,updated_at)
                select uuidv7(),?,'Large fixture',?,'active',false,1,1,now(),now() from generate_series(1,49)
                """,b.user,large);
        var page=search(b,Map.of("query","largeprefixneedle","limit",50));
        assertThat(page.get("items")).hasSize(50);
        page.get("items").forEach(item -> assertThat(item.get("snippet").asText())
                .hasSizeLessThanOrEqualTo(240).contains("largeprefixneedle").doesNotContain("beyondprojectionneedle"));
        assertThat(page.toString()).doesNotContain("markdown","ownerUserId").hasSizeLessThan(40_000);
    }

    @Test void keysetPaginationIsOpaqueBoundToOwnerAndNormalizedRequestAndExpires() throws Exception {
        var b=browser(account(),"ROLE_USER");
        for(int i=0;i<5;i++) create(b,"Paginationword","Same text");
        var seen=new HashSet<String>();
        JsonNode page=search(b,Map.of("query","paginationword","limit",1));
        String first=page.get("nextCursor").asText();
        assertThat(first).doesNotContain("paginationword",b.user.toString());
        while(true) {
            for(String id:ids(page)) assertThat(seen.add(id)).isTrue();
            if(page.get("nextCursor").isNull()) break;
            page=search(b,Map.of("query","Paginationword","limit",1,"cursor",page.get("nextCursor").asText()));
        }
        assertThat(seen).hasSize(5);
        for(Map<String,Object> invalid:List.<Map<String,Object>>of(
                Map.of("query","different","cursor",first),
                Map.of("query","paginationword","cursor",first,"lifecycle","trashed"),
                Map.of("query","paginationword","cursor",first,"tags",List.of("Other")),
                Map.of("query","paginationword","cursor",first.substring(0,first.length()-5)+"AAAAA"))) {
            request(b,invalid).andExpect(status().isBadRequest());
        }
        request(browser(account(),"ROLE_USER"),Map.of("query","paginationword","cursor",first)).andExpect(status().isBadRequest());
        clock.offset=Duration.ofMinutes(16);
        request(b,Map.of("query","paginationword","cursor",first)).andExpect(status().isBadRequest());
    }

    @Test void continuationNeverReturnsANowIneligibleRow() throws Exception {
        var b=browser(account(),"ROLE_USER");
        for(int i=0;i<3;i++) create(b,"Currentnessword","Same");
        var page=search(b,Map.of("query","currentnessword","limit",1));
        String displayed=ids(page).getFirst();
        jdbc.update("update notes.note set lifecycle_state='logically_deleted',deleted_at=now() where owner_user_id=? and note_id<>?::uuid",b.user,displayed);
        assertThat(ids(search(b,Map.of("query","currentnessword","limit",1,"cursor",page.get("nextCursor").asText())))).isEmpty();
    }

    @Test void securityRejectsBeforeCandidateRetrievalAndIneligibleAccountCannotSearch() throws Exception {
        var b=browser(account(),"ROLE_USER");
        mvc.perform(post("/api/notes/search").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"private marker\"}")).andExpect(status().isUnauthorized());
        var restricted=browser(account(),"ROLE_PRE_MFA");
        request(restricted,Map.of("query","private marker")).andExpect(status().isForbidden());
        mvc.perform(post("/api/notes/search").cookie(b.cookie).contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"private marker\"}")).andExpect(status().isForbidden());
        jdbc.update("update identity.account set account_state='suspended' where user_id=?",b.user);
        request(b,Map.of("query","private marker")).andExpect(status().isForbidden());
        verify(repository,never()).search(any(),any(),any(),any(),anyInt());
        verifyNoInteractions(provider);
    }

    @Test void invalidAndExcessiveRequestsNeverEnterSearchSqlOrEchoPrivateText() throws Exception {
        var b=browser(account(),"ROLE_USER");
        for(Map<String,Object> input:List.<Map<String,Object>>of(Map.of(),Map.of("query"," "),Map.of("query",17),
                Map.of("query","private marker","ownerUserId",b.user.toString()),Map.of("query","private marker","mode","semantic"),
                Map.of("query","private marker","limit","2"),Map.of("query","private marker","tags",List.of(1)),
                Map.of("query","x".repeat(257)),Map.of("query","private marker","lifecycle","logically_deleted"),
                Map.of("query","private marker","sort","rank; delete"))) {
            var response=request(b,input).andExpect(status().isUnprocessableContent()).andReturn().getResponse();
            assertThat(response.getContentAsString()).doesNotContain("private marker",b.user.toString(),"rank; delete");
        }
        for(int limit:List.of(0,51)) request(b,Map.of("query","private marker","limit",limit)).andExpect(status().isBadRequest());
        for(String cursor:List.of("not-a-cursor","x".repeat(2049))) request(b,Map.of("query","private marker","cursor",cursor)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/notes/search").cookie(b.cookie).header("X-CSRF-TOKEN",b.csrf).contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/notes/search").cookie(b.cookie).header("X-CSRF-TOKEN",b.csrf).contentType(MediaType.APPLICATION_JSON)
                .content(" ".repeat(8193))).andExpect(status().isContentTooLarge());
        verify(repository,never()).search(any(),any(),any(),any(),anyInt());
    }

    @Test void rateRejectionAndDatabaseFailureAreTruthfulSanitizedAndFailClosed() throws Exception {
        var b=browser(account(),"ROLE_USER");
        when(rates.evaluate(any())).thenReturn(new RateLimitPort.Throttled(17));
        var throttled=request(b,Map.of("query","private marker")).andExpect(status().isTooManyRequests()).andReturn();
        assertThat(throttled.getResponse().getHeader("Retry-After")).isEqualTo("17");
        when(rates.evaluate(any())).thenReturn(new RateLimitPort.ControlUnavailable());
        request(b,Map.of("query","private marker")).andExpect(status().isServiceUnavailable());
        verify(repository,never()).search(any(),any(),any(),any(),anyInt());
        when(rates.evaluate(any())).thenReturn(new RateLimitPort.Allowed());
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("private marker SQL host-path"))
                .when(repository).search(any(),any(),any(),any(),anyInt());
        var failed=request(b,Map.of("query","private marker")).andExpect(status().isServiceUnavailable()).andReturn();
        assertThat(failed.getResponse().getContentAsString()).doesNotContain("private marker","SQL","host-path","Exception");
    }

    @Test void rateKeysDoNotDependOnPrivateQueryText() throws Exception {
        var b=browser(account(),"ROLE_USER");
        search(b,Map.of("query","first marker")); search(b,Map.of("query","second marker"));
        var capture=org.mockito.ArgumentCaptor.forClass(RateLimitPort.Request.class);
        verify(rates,times(4)).evaluate(capture.capture());
        var calls=capture.getAllValues();
        assertThat(calls.get(0).controlClass().value()).isEqualTo("NOTE_SEARCH");
        assertThat(calls.get(1).controlClass().value()).isEqualTo("NOTE_SEARCH_GLOBAL");
        assertThat(calls.get(0).enforcementKey().value()).isEqualTo(calls.get(2).enforcementKey().value());
        assertThat(calls.get(1).enforcementKey().value()).isEqualTo(calls.get(3).enforcementKey().value());
    }

    @Test void indexesAndRealPostgresOwnerAccessAreAvailableWithoutNewRelations() {
        assertThat(jdbc.queryForObject("select current_setting('server_version')",String.class)).startsWith("18.");
        assertThat(jdbc.queryForList("select extname from pg_extension",String.class)).contains("pg_trgm").doesNotContain("vector");
        assertThat(jdbc.queryForList("select indexname from pg_indexes where schemaname='notes'",String.class))
                .contains("ix_note_search_simple","ix_note_search_english","ix_note_search_trigram","ix_note_search_title_trigram");
        assertThat(jdbc.queryForObject("select count(*) from pg_tables where schemaname in ('identity','notes','profile','knowledge','publishing','discovery','moderation')",Integer.class)).isEqualTo(22);
        UUID owner=account();
        var transaction=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.support.JdbcTransactionManager(jdbc.getDataSource()));
        transaction.executeWithoutResult(s -> {
            jdbc.execute("set local enable_seqscan=off"); // Indexability evidence only, never a production planner setting.
            String plan=String.join("\n",jdbc.queryForList("explain select note_id from notes.note where owner_user_id=? and lifecycle_state='active' and search_simple @@ plainto_tsquery('simple','needle')",String.class,owner));
            assertThat(plan).contains("Index", "owner_user_id", "lifecycle_state");
            String trigram=String.join("\n",jdbc.queryForList("explain select note_id from notes.note where search_text %> 'needle'",String.class));
            assertThat(trigram).contains("ix_note_search_trigram");
        });
    }

    private org.springframework.test.web.servlet.ResultActions request(Browser b,Map<String,Object> body) throws Exception {
        return mvc.perform(post("/api/notes/search").cookie(b.cookie).header("X-CSRF-TOKEN",b.csrf)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }
    private JsonNode search(Browser b,Map<String,Object> body) throws Exception {
        var result=request(b,body).andExpect(status().isOk()).andReturn();
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        return json.readTree(result.getResponse().getContentAsString());
    }
    private List<String> ids(JsonNode page) {
        var ids=new ArrayList<String>(); page.get("items").forEach(item -> ids.add(item.get("id").asText())); return ids;
    }
    private MvcResult create(Browser b,String title,String markdown) throws Exception {
        return mvc.perform(post("/api/notes").cookie(b.cookie).header("X-CSRF-TOKEN",b.csrf).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("title",title,"markdown",markdown,"aiEnabled",false))))
                .andExpect(status().isCreated()).andReturn();
    }
    private String id(MvcResult n) throws Exception { return json.readTree(n.getResponse().getContentAsString()).get("id").asText(); }
    private MvcResult tags(Browser b,String id,String etag,List<String> tags) throws Exception {
        return mvc.perform(put("/api/notes/{id}/tags",id).cookie(b.cookie).header("X-CSRF-TOKEN",b.csrf).header("If-Match",etag)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("tags",tags))))
                .andExpect(status().isOk()).andReturn();
    }
    private UUID account() {
        UUID id=jdbc.queryForObject("select uuidv7()",UUID.class);
        String email="search-"+id+"@example.test";
        jdbc.update("insert into identity.account(user_id,canonical_email,display_email,email_verified_at,account_state,created_at,updated_at) values(?,?,?,now(),'active',now(),now())",id,email,email);
        return id;
    }
    private Browser browser(UUID owner,String role) throws Exception {
        Session session=sessions.createSession();
        var context=SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(new IdentitySessionPrincipal(owner),null,List.of(new SimpleGrantedAuthority(role))));
        session.setAttribute("SPRING_SECURITY_CONTEXT",context); save(session);
        Cookie cookie=new Cookie("SESSION",Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8)));
        var csrf=mvc.perform(get("/api/auth/csrf").cookie(cookie)).andExpect(status().isOk()).andReturn();
        return new Browser(owner,csrf.getResponse().getCookie("SESSION")==null?cookie:csrf.getResponse().getCookie("SESSION"),json.readTree(csrf.getResponse().getContentAsString()).get("csrfToken").asText());
    }
    @SuppressWarnings({"rawtypes","unchecked"}) private void save(Session s) { ((SessionRepository)sessions).save(s); }
    record Browser(UUID user,Cookie cookie,String csrf) { }
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false)
    static class TimeConfiguration {
        @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary TestClock testClock() { return new TestClock(); }
    }
    static class TestClock extends Clock {
        Duration offset=Duration.ZERO;
        public ZoneId getZone() { return java.time.ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return Instant.now().plus(offset); }
    }
}
