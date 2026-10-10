package org.notesknowledge.knowledge;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
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
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/** Honest lexical baseline: no manufactured semantic vectors and no relevance pass threshold. */
@Tag("DATABASE") @Tag("API") @Tag("SECURITY") @Tag("RETRIEVAL") @Tag("EVALUATION")
@Testcontainers @SpringBootTest @AutoConfigureMockMvc
class FrozenQualityLexicalIntegrationTest {
    @Container static final PostgreSQLContainer postgres=new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18-trixie")
        .withDatabaseName("frozen_quality").withUsername("synthetic_migrator").withPassword("synthetic-quality-password");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",postgres::getJdbcUrl);r.add("spring.datasource.username",postgres::getUsername);r.add("spring.datasource.password",postgres::getPassword);
        r.add("notes.cursor.active.version",()->"test1");r.add("notes.cursor.active.key-base64",()->"AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=");
        r.add("identity.rate.key-base64",()->"AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=");
    }
    @Autowired JdbcTemplate jdbc;@Autowired MockMvc mvc;@Autowired ObjectMapper json;
    @Autowired SessionRepository<? extends Session> sessions;
    @MockitoBean RateLimitPort rates;
    @Test void evaluatesEveryFrozenQueryWithoutAnyProviderAndKeepsAiOffLexicallyVisible()throws Exception {
        when(rates.evaluate(any())).thenReturn(new RateLimitPort.Allowed());
        UUID owner=account("quality-owner"),foreign=account("quality-other");var ids=new LinkedHashMap<UUID,String>();
        for(var note:FrozenQualityCorpus.notes()) {
            UUID id=jdbc.queryForObject("select uuidv7()",UUID.class);ids.put(id,note.id());insert(owner,id,note);
            insert(foreign,jdbc.queryForObject("select uuidv7()",UUID.class),note);
        }
        Session session=sessions.createSession();var security=SecurityContextHolder.createEmptyContext();
        security.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(new IdentitySessionPrincipal(owner),null,List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        session.setAttribute("SPRING_SECURITY_CONTEXT",security);save(session);
        Cookie cookie=new Cookie("SESSION",Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8)));
        var bootstrap=mvc.perform(get("/api/auth/csrf").cookie(cookie)).andReturn().getResponse();assertThat(bootstrap.getStatus()).isEqualTo(200);
        if(bootstrap.getCookie("SESSION")!=null)cookie=bootstrap.getCookie("SESSION");String csrf=json.readTree(bootstrap.getContentAsString()).get("csrfToken").asText();
        var cases=new ArrayList<Map<String,Object>>();
        for(var query:FrozenQualityCorpus.queries()) {
            long started=System.nanoTime();var response=mvc.perform(post("/api/notes/search").cookie(cookie).header("X-CSRF-TOKEN",csrf)
                .contentType("application/json").content(json.writeValueAsBytes(Map.of("query",query.text(),"limit",50)))).andReturn().getResponse();
            assertThat(response.getStatus()).as(query.id()).isEqualTo(200);var ranking=new ArrayList<String>();
            for(var item:json.readTree(response.getContentAsString()).get("items")) {
                UUID id=UUID.fromString(item.get("id").asText());assertThat(ids).containsKey(id);ranking.add(ids.get(id));
            }
            var row=new LinkedHashMap<String,Object>();row.put("queryId",query.id());row.put("split",query.split());row.put("category",query.category());
            row.put("ranking",ranking);row.put("elapsedMs",(System.nanoTime()-started)/1e6);row.put("gold",query.gold());
            row.put("metrics",query.gold().isEmpty()?null:FrozenRetrievalMetrics.score(ranking,query.gold()));
            row.put("earliestMissingStage",query.gold().isEmpty()?"not-applicable-unanswerable":ranking.containsAll(query.gold().keySet())?"none":"lexical-candidate-retrieval");cases.add(row);
            if(query.id().equals("q40"))assertThat(ranking).contains("n81");
        }
        assertThat(jdbc.queryForObject("select count(*) from knowledge.private_derived_segment",Integer.class)).isZero();
        Path output=Path.of("target/retrieval-evaluation/frozen-quality-lexical.json");Files.createDirectories(output.getParent());
        Files.writeString(output,json.writeValueAsString(Map.of("seed",FrozenQualityCorpus.SEED,"notes",81,"queries",48,"providerCalls",0,"foreignResults",0,
            "qualification","lexical-only; natural-language misses are measured, not suppressed","cases",cases)));
    }
    UUID account(String alias){return jdbc.queryForObject("insert into identity.account(user_id,canonical_email,display_email,email_verified_at,account_state,created_at,updated_at) values(uuidv7(),?,?,now(),'active',now(),now()) returning user_id",UUID.class,alias+"@example.test",alias+"@example.test");}
    void insert(UUID owner,UUID id,FrozenQualityCorpus.Note note){jdbc.update("insert into notes.note(note_id,owner_user_id,title,markdown,lifecycle_state,ai_enabled,revision,ai_generation,created_at,updated_at) values(?,?,?,?,'active',?,1,1,now(),now())",id,owner,note.title(),note.body(),note.aiEnabled());}
    @SuppressWarnings({"rawtypes","unchecked"}) void save(Session session){((SessionRepository)sessions).save(session);}
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
}
