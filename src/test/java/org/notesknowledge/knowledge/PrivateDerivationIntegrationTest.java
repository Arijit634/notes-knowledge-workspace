package org.notesknowledge.knowledge;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.notesknowledge.LeaseOwner;
import org.notesknowledge.identity.IdentitySessionPrincipal;
import org.notesknowledge.knowledge.spi.PrivateAiSourceCurrentness;
import org.notesknowledge.security.RateLimitPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@Tag("DATABASE") @Tag("API") @Tag("SECURITY") @Tag("RETRIEVAL") @Tag("EVALUATION")
@Testcontainers @SpringBootTest @AutoConfigureMockMvc
@org.springframework.context.annotation.Import(org.notesknowledge.notes.DerivationMediaFixtures.class)
class PrivateDerivationIntegrationTest {
    @Container static final PostgreSQLContainer postgres=new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18-trixie")
        .withDatabaseName("private_derivation").withUsername("synthetic_migrator").withPassword("synthetic-derivation-password");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",postgres::getJdbcUrl);r.add("spring.datasource.username",postgres::getUsername);r.add("spring.datasource.password",postgres::getPassword);
        r.add("identity.rate.key-base64",()->"AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=");
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired SessionRepository<? extends Session> sessions;
    @Autowired PlatformTransactionManager transactions;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder passwords;
    @Autowired KnowledgeWorkRepository workRepository;
    @Autowired KnowledgeWorkService work;
    @Autowired ProcessingPolicyService policies;
    @Autowired DerivationExecutor executor;
    @Autowired DerivationReconciler reconciler;
    @MockitoSpyBean PrivateRepresentationRepository representations;
    @Autowired ExactPrivateVectorSearch search;
    @Autowired KnowledgeInvalidationApi invalidation;
    @MockitoSpyBean AiProcessingGate gate;
    @MockitoBean AiDerivationProperties configuration;
    @MockitoBean KnowledgePolicyProperties policyConfiguration;
    @MockitoBean TextEmbeddingPort embeddings;
    @MockitoBean MediaUnderstandingPort media;
    @MockitoBean RateLimitPort rates;
    UUID owner,note,policy;
    Browser browser;
    String code;
    @BeforeEach void prepare() throws Exception {
        reset(configuration,policyConfiguration,embeddings,media,rates,gate,representations);
        when(configuration.configured()).thenReturn(true);when(configuration.provider()).thenReturn("synthetic");
        when(configuration.embeddingModel()).thenReturn("synthetic-embedding");when(configuration.mediaModel()).thenReturn("synthetic-media");
        when(configuration.modelRevision()).thenReturn("v1");when(configuration.configurationId()).thenReturn("synthetic-v1");
        when(configuration.adapterVersion()).thenReturn("synthetic-v1");when(configuration.region()).thenReturn("synthetic");when(configuration.tier()).thenReturn("synthetic");
        when(configuration.dimension()).thenReturn(8);when(configuration.operator()).thenReturn("cosine");when(configuration.normalization()).thenReturn("none");
        when(configuration.approvedPolicyFingerprint()).thenReturn("a".repeat(64));
        when(embeddings.available()).thenReturn(true);when(media.available()).thenReturn(true);
        when(embeddings.embed(any(),any())).thenAnswer(call->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            List<String> texts=call.getArgument(1);return texts.stream().map(t->new float[]{1,0,0,0,0,0,0,0}).toList();});
        when(rates.evaluate(any())).thenReturn(new RateLimitPort.Allowed());
        jdbc.update("update knowledge.knowledge_work_intent set state='obsolete',lease_owner=null,lease_token=null,lease_until=null,next_attempt_at=null,updated_at=clock_timestamp() where state in ('queued','claimed','retry_wait')");
        code="synthetic-"+UUID.randomUUID();when(policyConfiguration.code()).thenReturn(code);
        owner=account();note=note(owner,true);browser=browser(owner);policy=policy(1,"a");ack(owner,policy);
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    @Test void notePipelineActivatesAllSegmentsAtomicallyAndProjectionDoesNotChangeEtag() throws Exception {
        String before=noteEtag();var claim=claim(noteExpected(),"note");
        assertProjection("processing");executor.execute(claim);assertProjection("ready");
        assertThat(noteEtag()).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from knowledge.private_derived_segment where owner_user_id=?",Integer.class,owner)).isPositive();
        assertThat(state(claim)).isEqualTo("completed");
        activate();assertThat(search.search(request(),"note",vector(),10)).hasSize(1);
        verify(embeddings).embed(any(),any());verifyNoInteractions(media);
    }
    @Test void disableBeforeFinalDispatchCapturesNoProviderContent() throws Exception {
        var claim=claim(noteExpected(),"note");
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call->{if(calls.incrementAndGet()==3)disable();return call.callRealMethod();}).when(gate).issue(any());
        executor.execute(claim);verify(embeddings,never()).embed(any(),any());
        assertThat(readyCount()).isZero();assertThat(state(claim)).isEqualTo("obsolete");assertProjection("excluded");
    }
    @ParameterizedTest @ValueSource(strings={"disable","save","tag","pin","archive","trash","suspend","policy","lineage"})
    void mutationDuringCapturedProviderNeverActivatesStaleResult(String mutation) throws Exception {
        var claim=claim(noteExpected(),"note");var started=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(call->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            started.countDown();assertThat(release.await(15,TimeUnit.SECONDS)).isTrue();return List.of(vector());}).when(embeddings).embed(any(),any());
        // doAnswer avoids invoking the existing capture stub during restubbing.
        try(var pool=Executors.newSingleThreadExecutor()) {
            var result=pool.submit(()->executor.execute(claim));assertThat(started.await(15,TimeUnit.SECONDS)).isTrue();
            switch(mutation) {
                case "disable" -> disable();
                case "save" -> mutate("put","",Map.of("title","Changed","markdown","Changed body"));
                case "tag" -> mutate("put","/tags",Map.of("tags",List.of("synthetic")));
                case "pin" -> mutate("put","/pin",null);
                case "archive" -> mutate("post","/archive",null);
                case "trash" -> mutate("post","/trash",null);
                case "suspend" -> jdbc.update("update identity.account set account_state='suspended' where user_id=?",owner);
                case "policy" -> policy(2,"b");
                case "lineage" -> when(configuration.configurationId()).thenReturn("synthetic-v2");
            }
            release.countDown();result.get(20,TimeUnit.SECONDS);
        }
        assertThat(readyCount()).isZero();assertThat(state(claim)).isEqualTo("obsolete");
        if(mutation.equals("disable"))assertProjection("excluded");
    }
    @Test void leaseReclaimFreshTokenFencesOldProviderResult() throws Exception {
        var a=claim(noteExpected(),"note");var started=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(call->{started.countDown();assertThat(release.await(15,TimeUnit.SECONDS)).isTrue();return List.of(vector());}).when(embeddings).embed(any(),any());
        try(var pool=Executors.newSingleThreadExecutor()) {
            var result=pool.submit(()->executor.execute(a));assertThat(started.await(15,TimeUnit.SECONDS)).isTrue();
            jdbc.update("update knowledge.knowledge_work_intent set lease_until=clock_timestamp()-interval '1 second' where knowledge_work_intent_id=?",a.intent().id());
            var b=work.reclaim(new LeaseOwner("synthetic-b"),1).getFirst();assertThat(b.token()).isNotEqualTo(a.token());
            release.countDown();result.get(20,TimeUnit.SECONDS);assertThat(readyCount()).isZero();
            doReturn(List.of(vector())).when(embeddings).embed(any(),any());executor.execute(b);
            assertThat(readyCount()).isEqualTo(1);assertThat(state(b)).isEqualTo("completed");
        }
    }
    @Test void wrongDimensionHasNoPartialRootAndDoesNotRepeatTerminalFailure() {
        var claim=claim(noteExpected(),"note");doReturn(List.of(new float[]{1,0})).when(embeddings).embed(any(),any());
        executor.execute(claim);assertThat(readyCount()).isZero();assertThat(state(claim)).isEqualTo("failed");
        reconcileAll();assertThat(jdbc.queryForObject("select count(*) from knowledge.knowledge_work_intent where owner_user_id=?",Integer.class,owner)).isEqualTo(1);
    }
    @Test void leaseLostAfterRootInsertRollsBackTheEntireCompareAndActivateTransaction() throws Exception {
        var a=claim(noteExpected(),"note");var inserted=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(call->{call.callRealMethod();inserted.countDown();assertThat(release.await(15,TimeUnit.SECONDS)).isTrue();return null;})
            .when(representations).activate(any(),any(),any(),any());
        try(var pool=Executors.newSingleThreadExecutor()) {
            var result=pool.submit(()->executor.execute(a));assertThat(inserted.await(15,TimeUnit.SECONDS)).isTrue();
            // Another connection cannot see a partially activated root.
            assertThat(readyCount()).isZero();
            jdbc.update("update knowledge.knowledge_work_intent set lease_until=clock_timestamp()-interval '1 second' where knowledge_work_intent_id=?",a.intent().id());
            var b=work.reclaim(new LeaseOwner("synthetic-b"),1).getFirst();assertThat(b.token()).isNotEqualTo(a.token());
            release.countDown();result.get(20,TimeUnit.SECONDS);
            assertThat(readyCount()).isZero();assertThat(state(b)).isEqualTo("claimed");
            reset(representations);executor.execute(b);assertThat(readyCount()).isEqualTo(1);assertThat(state(b)).isEqualTo("completed");
        } finally {release.countDown();}
    }
    @Test void transientFailureIsDurableRetryAndOrdinaryNoteStillReadable() throws Exception {
        var claim=claim(noteExpected(),"note");doThrow(new DerivationFailure(KnowledgeWork.Failure.QUOTA)).when(embeddings).embed(any(),any());
        executor.execute(claim);assertThat(state(claim)).isEqualTo("retry_wait");assertThat(readyCount()).isZero();assertThat(noteEtag()).isNotBlank();
    }
    @Test void unavailableDatabaseGateDefersWithoutProviderCaptureOrFalseCompletion() {
        var claim=claim(noteExpected(),"note");
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("SYNTHETIC_PRIVATE_DIAGNOSTIC")).when(gate).issue(any());
        executor.execute(claim);verify(embeddings,never()).embed(any(),any());verify(media,never()).describe(any(),any(),any());
        assertThat(readyCount()).isZero();assertThat(state(claim)).isEqualTo("claimed");
    }
    @Test void missingAcknowledgementAndSuspendedAccountCannotDispatch() {
        UUID newPolicy=policy(2,"a");var claim=claimWithLineage(noteExpected(),lineageForPolicy(newPolicy,2,"note"));
        executor.execute(claim);verify(embeddings,never()).embed(any(),any());assertThat(readyCount()).isZero();
        ack(owner,newPolicy);jdbc.update("update identity.account set account_state='suspended' where user_id=?",owner);
        var second=claimWithLineage(noteExpected(),lineageForPolicy(newPolicy,2,"note"));executor.execute(second);
        verify(embeddings,never()).embed(any(),any());
    }
    @Test void reconciliationFindsLateAcknowledgementAndNewLineageWithoutRelabelingOldVectors() {
        when(configuration.configured()).thenReturn(false);reconcileAll();
        assertThat(jdbc.queryForObject("select count(*) from knowledge.knowledge_work_intent where owner_user_id=?",Integer.class,owner)).isZero();
        when(configuration.configured()).thenReturn(true);reconcileAll();executor.execute(work.claim(new LeaseOwner("synthetic"),10).stream().filter(c->c.intent().expected().owner().equals(owner)).findFirst().orElseThrow());
        String old=jdbc.queryForObject("select lineage_id from knowledge.private_derived_representation where owner_user_id=? and state='ready'",String.class,owner);
        when(configuration.configurationId()).thenReturn("synthetic-v2");reconcileAll();
        var b=work.claim(new LeaseOwner("synthetic"),10).stream().filter(c->c.intent().expected().owner().equals(owner)).findFirst().orElseThrow();
        assertThat(b.intent().targetLineageId()).isNotEqualTo(old);executor.execute(b);
        assertThat(jdbc.queryForObject("select state from knowledge.private_derived_representation where owner_user_id=? and lineage_id=?",String.class,owner,old)).isEqualTo("obsolete");
    }
    @Test void scopeFirstRankingRejectsCloserForeignAndStaleSources() throws Exception {
        doReturn(List.of(new float[]{0,1,0,0,0,0,0,0})).when(embeddings).embed(any(),any());
        var c=claim(noteExpected(),"note");executor.execute(c);
        doReturn(List.of(vector())).when(embeddings).embed(any(),any());
        UUID foreign=account(),foreignNote=note(foreign,true);ack(foreign,policy);
        var foreignExpected=new PrivateAiSourceCurrentness.Expected(foreign,foreignNote,null,1,1,null);
        var fc=claim(foreignExpected,"note");executor.execute(fc);
        activate();var candidates=search.search(request(),"note",vector(),1);
        assertThat(candidates).hasSize(1);assertThat(candidates.getFirst().expected().owner()).isEqualTo(owner);
        var named=new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc);
        var rootIds=jdbc.queryForList("select derived_representation_id from knowledge.private_derived_representation where owner_user_id=? and state='ready'",UUID.class,owner);
        var params=Map.<String,Object>of("owner",owner,"lineage",c.intent().targetLineageId(),"dimension",8,"policy",policy,
            "parents",rootIds,"vector",PrivateRepresentationRepository.vectorLiteral(vector()),"limit",1);
        var raw=named.queryForList(ExactPrivateVectorSearch.candidateSql("<=>"),params);
        assertThat(raw).hasSize(1);assertThat(raw).allSatisfy(row->assertThat(row.get("owner_user_id")).isEqualTo(owner));
        var plan=named.queryForList("explain (analyze,buffers) "+ExactPrivateVectorSearch.candidateSql("<=>"),params,String.class);
        assertThat(String.join("\n",plan)).contains("eligible").doesNotContain("hnsw","ivfflat");
        // Direct fixture revision change proves currentness is also checked before ranking, not solely by root state.
        jdbc.update("update notes.note set revision=revision+1 where note_id=?",note);
        assertThat(search.search(request(),"note",vector(),10)).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings={"save","tag","pin","archive","trash","disable","bulk","version"})
    void ownerMutationMakesReadyRootsAndActiveWorkUnreachableBeforeSuccess(String mutation) throws Exception {
        executor.execute(claim(noteExpected(),"note"));assertThat(readyCount()).isEqualTo(1);
        var inFlight=claim(noteExpected(),"note");
        switch(mutation) {
            case "save" -> mutate("put","",Map.of("title","Changed","markdown","Changed body"));
            case "tag" -> mutate("put","/tags",Map.of("tags",List.of("synthetic")));
            case "pin" -> mutate("put","/pin",null);
            case "archive" -> mutate("post","/archive",null);
            case "trash" -> mutate("post","/trash",null);
            case "disable" -> disable();
            case "bulk" -> mvc.perform(post("/api/notes/ai-access-bulk").cookie(browser.cookie).header("X-CSRF-TOKEN",browser.csrf)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("aiEnabled",false,"scope","selected","confirm",true,"noteIds",List.of(note)))))
                .andExpect(status().isOk());
            case "version" -> {
                UUID version=jdbc.queryForObject("insert into notes.note_version(note_version_id,owner_user_id,note_id,source_revision,title,markdown,checkpoint_kind,created_at) values(uuidv7(),?,?,10,'Restored','Restored body','policy',clock_timestamp()) returning note_version_id",UUID.class,owner,note);
                mutate("post","/versions/"+version+"/restore",Map.of("confirmRestore",true));
            }
        }
        assertThat(readyCount()).isZero();assertThat(state(inFlight)).isEqualTo("obsolete");
        activate();assertThat(search.search(request(),"note",vector(),10)).isEmpty();
    }
    @Test void mixedBulkAiEnablePreservesAlreadyEnabledReadyRootAndActiveWork() throws Exception {
        executor.execute(claim(noteExpected(),"note"));assertThat(readyCount()).isEqualTo(1);
        var inFlight=claim(noteExpected(),"note");UUID disabled=note(owner,false);String before=noteEtag();
        mvc.perform(post("/api/notes/ai-access-bulk").cookie(browser.cookie).header("X-CSRF-TOKEN",browser.csrf)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("aiEnabled",true,"scope","selected","confirm",true,"noteIds",List.of(note,disabled)))))
                .andExpect(status().isOk());
        assertThat(readyCount()).isEqualTo(1);assertThat(state(inFlight)).isEqualTo("claimed");
        assertThat(noteEtag()).isEqualTo(before);
        assertThat(jdbc.queryForObject("select ai_enabled from notes.note where note_id=?",Boolean.class,disabled)).isTrue();
        assertThat(jdbc.queryForObject("select ai_generation from notes.note where note_id=?",Long.class,disabled)).isEqualTo(2);
    }
    @Test void restoreAndPermanentDeletionNeverReactivatePreTrashRoots() throws Exception {
        executor.execute(claim(noteExpected(),"note"));mutate("post","/trash",null);assertThat(readyCount()).isZero();
        mutate("post","/restore",null);activate();assertThat(search.search(request(),"note",vector(),10)).isEmpty();
        reconcileAll();var fresh=work.claim(new LeaseOwner("synthetic"),10).stream().filter(c->c.intent().expected().owner().equals(owner)).findFirst().orElseThrow();
        executor.execute(fresh);assertThat(readyCount()).isEqualTo(1);mutate("post","/trash",null);
        String password="Synthetic-derivation-delete-password-123!";
        jdbc.update("update identity.account set password_verifier=? where user_id=?",passwords.encode(password),owner);
        mvc.perform(post("/api/auth/reauth/password").cookie(browser.cookie).header("X-CSRF-TOKEN",browser.csrf)
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("password",password)))).andExpect(status().isNoContent());
        mvc.perform(delete("/api/notes/"+note).cookie(browser.cookie).header("X-CSRF-TOKEN",browser.csrf).header("If-Match",noteEtag())
            .contentType(MediaType.APPLICATION_JSON).content("{\"confirmPermanentDelete\":true}" )).andExpect(status().isNoContent());
        assertThat(readyCount()).isZero();activate();assertThat(search.search(request(),"note",vector(),10)).isEmpty();
    }
    @Test void exactCandidateContractDeniesWrongPolicyLineageAndAiGenerationAndKeepsStableTies() throws Exception {
        UUID second=note(owner,true);executor.execute(claim(noteExpected(),"note"));executor.execute(claim(new PrivateAiSourceCurrentness.Expected(owner,second,null,1,1,null),"note"));
        activate();var first=search.search(request(),"note",vector(),2);
        assertThat(first).hasSize(2);assertThat(search.search(request(),"note",vector(),2)).isEqualTo(first);
        when(configuration.configurationId()).thenReturn("synthetic-v2");assertThat(search.search(request(),"note",vector(),2)).isEmpty();
        when(configuration.configurationId()).thenReturn("synthetic-v1");
        jdbc.update("update notes.note set ai_generation=2 where owner_user_id=?",owner);assertThat(search.search(request(),"note",vector(),2)).isEmpty();
        UUID next=policy(2,"a");ack(owner,next);assertThat(search.search(request(),"note",vector(),2)).isEmpty();
    }
    @Test void missingProviderConfigurationDoesNotEnqueueAndReadyProjectionSurvivesTransientOutage() throws Exception {
        when(embeddings.available()).thenReturn(false);reconcileAll();
        assertThat(jdbc.queryForObject("select count(*) from knowledge.knowledge_work_intent where owner_user_id=?",Integer.class,owner)).isZero();
        when(embeddings.available()).thenReturn(true);executor.execute(claim(noteExpected(),"note"));
        when(embeddings.available()).thenReturn(false);assertProjection("ready");
    }
    @Test void statusIsNoStoreOwnerScopedRateControlledAndHasNoMutationValidator() throws Exception {
        mvc.perform(get("/api/notes/"+note+"/ai-processing")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/notes/not-a-uuid/ai-processing").cookie(browser.cookie)).andExpect(status().isBadRequest());
        var response=mvc.perform(get("/api/notes/"+note+"/ai-processing").cookie(browser.cookie)).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(response.getHeader("Cache-Control")).contains("no-store");assertThat(response.getHeader("ETag")).isNull();
        assertThat(response.getContentAsString()).doesNotContain("lease","generation","lineage","vector","ownerUserId");
        var other=browser(account());mvc.perform(get("/api/notes/"+note+"/ai-processing").cookie(other.cookie)).andExpect(status().isNotFound());
        when(rates.evaluate(any())).thenReturn(new RateLimitPort.Throttled(5));mvc.perform(get("/api/notes/"+note+"/ai-processing").cookie(browser.cookie)).andExpect(status().isTooManyRequests())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Retry-After","5"));
        when(rates.evaluate(any())).thenReturn(new RateLimitPort.ControlUnavailable());mvc.perform(get("/api/notes/"+note+"/ai-processing").cookie(browser.cookie)).andExpect(status().isServiceUnavailable());
    }
    @Test void expiredClaimIsNotReportedAsCurrentlyProcessing() throws Exception {
        var claim=claim(noteExpected(),"note");assertProjection("processing");
        jdbc.update("update knowledge.knowledge_work_intent set lease_until=clock_timestamp()-interval '1 second' where knowledge_work_intent_id=?",claim.intent().id());
        assertProjection("queued");work.reclaim(new LeaseOwner("synthetic"),1);assertProjection("processing");
    }
    @ParameterizedTest @ValueSource(strings={"image","audio","video","pdf"})
    void validatedStoredMediaProducesTypedTextSurrogateAndCompatibleVector(String kind) throws Exception {
        UUID attachment=upload(kind);
        var expected=new PrivateAiSourceCurrentness.Expected(owner,note,attachment,1,1,1L);
        doAnswer(call->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            var permit=(AiProcessingGate.SourceAiPermit)call.getArgument(0);assertThat(permit.source().modality()).isEqualTo(kind);
            assertThat((byte[])call.getArgument(1)).isNotEmpty();return mediaSegments(kind);
        }).when(media).describe(any(),any(),any());
        var claim=claim(expected,kind);executor.execute(claim);
        assertThat(state(claim)).isEqualTo("completed");
        assertThat(jdbc.queryForObject("select count(*) from knowledge.private_derived_segment where owner_user_id=?",Integer.class,owner)).isEqualTo(mediaSegments(kind).size());
        activate();var candidates=search.search(request(),kind,vector(),10);
        assertThat(candidates).hasSize(mediaSegments(kind).size());
        assertThat(candidates).allSatisfy(c->{assertThat(c.expected()).isEqualTo(expected);assertThat(c.segment().kind()).isIn(mediaSegments(kind).stream().map(DerivedSegment::kind).toList());});
        assertThat(noteEtag()).isNotBlank();
        var projection=json.readTree(mvc.perform(get("/api/notes/"+note+"/ai-processing").cookie(browser.cookie)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(projection.get("attachments").get(0).get("status").asText()).isEqualTo("ready");
    }
    @Test void pdfTextUsesBoundedDeterministicForkWithActualPageInsteadOfMediaProvider() throws Exception {
        UUID attachment=upload("pdf-text");executor.execute(claim(new PrivateAiSourceCurrentness.Expected(owner,note,attachment,1,1,1L),"pdf"));
        assertThat(readyCount()).isEqualTo(1);verify(media,never()).describe(any(),any(),any());
        assertThat(jdbc.queryForList("select page_number from knowledge.private_derived_segment where owner_user_id=?",Integer.class,owner)).containsOnly(1);
        assertThat(jdbc.queryForObject("select surrogate_text from knowledge.private_derived_segment where owner_user_id=?",String.class,owner)).contains("Synthetic PDF page evidence");
    }
    @ParameterizedTest @ValueSource(strings={"image","audio","video","pdf"})
    void aiOffMediaRemainsOwnerReadableWithoutAnyProviderOrVectors(String kind) throws Exception {
        UUID attachment=upload(kind);disable();
        var expected=new PrivateAiSourceCurrentness.Expected(owner,note,attachment,1,1,1L);executor.execute(claim(expected,kind));
        verify(embeddings,never()).embed(any(),any());verify(media,never()).describe(any(),any(),any());assertThat(readyCount()).isZero();
        mvc.perform(get("/api/notes/"+note+"/attachments/"+attachment+"/content").cookie(browser.cookie)).andExpect(status().isOk());
        assertProjection("excluded");
    }
    @Test void attachmentDeletionWhileProviderCapturedCannotActivateOrResurrectMetadata() throws Exception {
        UUID attachment=upload("image");var expected=new PrivateAiSourceCurrentness.Expected(owner,note,attachment,1,1,1L);
        var claim=claim(expected,"image");var captured=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(call->{captured.countDown();assertThat(release.await(15,TimeUnit.SECONDS)).isTrue();return mediaSegments("image");}).when(media).describe(any(),any(),any());
        try(var pool=Executors.newSingleThreadExecutor()) {
            var result=pool.submit(()->executor.execute(claim));assertThat(captured.await(15,TimeUnit.SECONDS)).isTrue();
            String path="/api/notes/"+note+"/attachments/"+attachment;
            String etag=mvc.perform(get(path).cookie(browser.cookie)).andExpect(status().isOk()).andReturn().getResponse().getHeader("ETag");
            mvc.perform(delete(path).cookie(browser.cookie).header("X-CSRF-TOKEN",browser.csrf).header("If-Match",etag)).andExpect(status().isNoContent());
            release.countDown();result.get(20,TimeUnit.SECONDS);
        }
        assertThat(readyCount()).isZero();assertThat(state(claim)).isEqualTo("obsolete");
        assertThat(json.readTree(mvc.perform(get("/api/notes/"+note+"/ai-processing").cookie(browser.cookie)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("attachments").size()).isZero();
    }
    @Test void invalidMediaTimeOrPageNeverPersistsUntrustedOutput() throws Exception {
        UUID attachment=upload("audio");var claim=claim(new PrivateAiSourceCurrentness.Expected(owner,note,attachment,1,1,1L),"audio");
        doReturn(List.of(new DerivedSegment("synthetic","transcript","",null,null,null,0.0,900.0,null,null,null,null))).when(media).describe(any(),any(),any());
        executor.execute(claim);assertThat(state(claim)).isEqualTo("failed");assertThat(readyCount()).isZero();verify(embeddings,never()).embed(any(),any());
    }
    private UUID upload(String kind) throws Exception {
        var file=org.notesknowledge.notes.DerivationMediaFixtures.media(kind);
        var response=mvc.perform(multipart("/api/notes/"+note+"/attachments").file(file).cookie(browser.cookie).header("X-CSRF-TOKEN",browser.csrf))
            .andExpect(status().isCreated()).andReturn().getResponse();return UUID.fromString(json.readTree(response.getContentAsString()).get("id").asText());
    }
    private List<DerivedSegment> mediaSegments(String kind) {
        return switch(kind) {
            case "image" -> List.of(new DerivedSegment("Synthetic whole image caption","whole_image","",null,null,null,null,null,null,null,null,null));
            case "audio" -> List.of(new DerivedSegment("Synthetic transcript","transcript","",null,null,null,0.0,0.5,null,null,null,null));
            case "video" -> List.of(new DerivedSegment("Synthetic transcript","transcript","",null,null,null,0.0,0.5,null,null,null,null),
                new DerivedSegment("Synthetic sampled scene","video_scene","",null,null,null,0.5,0.5,null,null,null,null));
            case "pdf" -> List.of(new DerivedSegment("Synthetic PDF text","pdf_text","",null,null,1,null,null,null,null,null,null));
            default -> throw new IllegalArgumentException("Unknown modality");
        };
    }
    private void reconcileAll(){org.notesknowledge.knowledge.spi.PrivateDerivationSource.Cursor cursor=null;int pages=0;do {cursor=reconciler.page(cursor);assertThat(++pages).isLessThan(100);}while(cursor!=null);}
    private float[] vector(){return new float[]{1,0,0,0,0,0,0,0};}
    private PrivateAiSourceCurrentness.Expected noteExpected(){return new PrivateAiSourceCurrentness.Expected(owner,note,null,1,1,null);}
    private EmbeddingLineage lineageForPolicy(UUID id,long version,String modality) {return EmbeddingLineage.create(configuration,new ProcessingPolicyService.AcknowledgedProcessingPolicy(id,version,"a".repeat(64)),modality);}
    private KnowledgeWork.Claim claim(PrivateAiSourceCurrentness.Expected e,String modality) {return claimWithLineage(e,lineageForPolicy(policy,1,modality));}
    private KnowledgeWork.Claim claimWithLineage(PrivateAiSourceCurrentness.Expected e,EmbeddingLineage l) {
        var tx=new TransactionTemplate(transactions);tx.execute(s->workRepository.enqueue(e.attachmentId()==null?KnowledgeWork.Kind.NOTE:KnowledgeWork.Kind.ATTACHMENT,e,l.id()));
        return work.claim(new LeaseOwner("synthetic-worker"),10).stream().filter(c->c.intent().expected().equals(e)).findFirst().orElseThrow();
    }
    private String state(KnowledgeWork.Claim c){return jdbc.queryForObject("select state from knowledge.knowledge_work_intent where knowledge_work_intent_id=?",String.class,c.intent().id());}
    private int readyCount(){return jdbc.queryForObject("select count(*) from knowledge.private_derived_representation where owner_user_id=? and state='ready'",Integer.class,owner);}
    private void disable() throws Exception {mutate("put","/ai-access",Map.of("aiEnabled",false));}
    private void mutate(String method,String suffix,Object body) throws Exception {
        var request=method.equals("put")?put("/api/notes/"+note+suffix):post("/api/notes/"+note+suffix);
        request.cookie(browser.cookie).header("X-CSRF-TOKEN",browser.csrf).header("If-Match",noteEtag());
        if(body!=null)request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        mvc.perform(request).andExpect(status().is2xxSuccessful());
    }
    private String noteEtag() throws Exception {return mvc.perform(get("/api/notes/"+note).cookie(browser.cookie)).andExpect(status().isOk()).andReturn().getResponse().getHeader("ETag");}
    private void assertProjection(String expected) throws Exception {
        var response=mvc.perform(get("/api/notes/"+note+"/ai-processing").cookie(browser.cookie)).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(json.readTree(response.getContentAsString()).get("note").get("status").asText()).isEqualTo(expected);
    }
    private UUID account(){UUID id=jdbc.queryForObject("select uuidv7()",UUID.class);String email="synthetic-"+id+"@example.test";
        jdbc.update("insert into identity.account(user_id,canonical_email,display_email,email_verified_at,account_state,created_at,updated_at) values(?,?,?,now(),'active',now(),now())",id,email,email);return id;}
    private UUID note(UUID user,boolean enabled){return jdbc.queryForObject("insert into notes.note(note_id,owner_user_id,title,markdown,lifecycle_state,ai_enabled,revision,ai_generation,created_at,updated_at) values(uuidv7(),?,'Synthetic','# Heading\nSynthetic body','active',?,1,1,clock_timestamp(),clock_timestamp()) returning note_id",UUID.class,user,enabled);}
    private UUID policy(long version,String fingerprint){return jdbc.queryForObject("insert into knowledge.processing_policy(policy_code,policy_version,policy_fingerprint,disclosure_revision,effective_at) values(?,?,?,'synthetic-1',clock_timestamp()-interval '1 minute') returning processing_policy_id",UUID.class,code,version,fingerprint.repeat(64));}
    private void ack(UUID user,UUID id){jdbc.update("insert into knowledge.processing_policy_acknowledgement(user_id,processing_policy_id,disclosure_revision) values(?,?,'synthetic-1')",user,id);}
    private Browser browser(UUID user) throws Exception {
        Session session=sessions.createSession();var context=SecurityContextHolder.createEmptyContext();context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(new IdentitySessionPrincipal(user),null,List.of(new SimpleGrantedAuthority("ROLE_USER"))));session.setAttribute("SPRING_SECURITY_CONTEXT",context);save(session);
        var cookie=new Cookie("SESSION",Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8)));
        var response=mvc.perform(get("/api/auth/csrf").cookie(cookie)).andExpect(status().isOk()).andReturn().getResponse();
        return new Browser(user,session.getId(),response.getCookie("SESSION")==null?cookie:response.getCookie("SESSION"),json.readTree(response.getContentAsString()).get("csrfToken").asText());
    }
    private void activate(){var context=SecurityContextHolder.createEmptyContext();context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(new IdentitySessionPrincipal(owner),null,List.of(new SimpleGrantedAuthority("ROLE_USER"))));SecurityContextHolder.setContext(context);}
    private MockHttpServletRequest request(){var request=new MockHttpServletRequest();request.setSession(new MockHttpSession(null,browser.sessionId));return request;}
    @SuppressWarnings({"rawtypes","unchecked"}) private void save(Session s){((SessionRepository)sessions).save(s);}
    private record Browser(UUID owner,String sessionId,Cookie cookie,String csrf) { }
}
