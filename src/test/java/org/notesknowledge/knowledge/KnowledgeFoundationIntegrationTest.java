package org.notesknowledge.knowledge;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.notesknowledge.LeaseOwner;
import org.notesknowledge.knowledge.spi.PrivateKnowledgeSource;
import org.notesknowledge.knowledge.spi.PrivateLexicalSearch;
import org.notesknowledge.knowledge.spi.PrivateAiSourceCurrentness;
import org.notesknowledge.identity.IdentitySessionPrincipal;
import org.notesknowledge.security.RateLimitPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@Tag("DATABASE") @Tag("API") @Tag("SECURITY") @Tag("RETRIEVAL")
@Testcontainers @SpringBootTest @AutoConfigureMockMvc
class KnowledgeFoundationIntegrationTest {
    @Container static final PostgreSQLContainer postgres=new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18-trixie")
            .withDatabaseName("knowledge_foundation").withUsername("knowledge_migrator").withPassword("synthetic-knowledge-password");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",postgres::getJdbcUrl);r.add("spring.datasource.username",postgres::getUsername);r.add("spring.datasource.password",postgres::getPassword);
        r.add("identity.rate.key-base64",()->"AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=");
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired SessionRepository<? extends Session> sessions;
    @Autowired PlatformTransactionManager transactions;
    @Autowired DeterministicUrlExtraction extraction;
    @Autowired PrivateLexicalSearch lexical;
    @Autowired KnowledgeWorkService work;
    @Autowired ProcessingPolicyService policies;
    @MockitoSpyBean PrivateKnowledgeSource sources;
    @MockitoSpyBean ProcessingPolicyRepository policyRepository;
    @MockitoBean KnowledgePolicyProperties configuration;
    @MockitoBean RateLimitPort rates;
    String code;
    final PrivateKnowledgeSource.Scope active=new PrivateKnowledgeSource.Scope(Set.of(PrivateKnowledgeSource.Lifecycle.ACTIVE));
    @BeforeEach void prepare() {
        reset(sourceSpy(),policyRepository,rates,configuration);
        when(rates.evaluate(any())).thenReturn(new RateLimitPort.Allowed());
        code="synthetic-"+UUID.randomUUID();when(configuration.code()).thenReturn(code);
        // Only this dedicated synthetic container. No policy/ack deletion bypasses immutability.
        jdbc.update("update knowledge.knowledge_work_intent set state='obsolete',lease_owner=null,lease_token=null,lease_until=null,next_attempt_at=null,updated_at=clock_timestamp() where state in ('queued','claimed','retry_wait')");
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext();RequestContextHolder.resetRequestAttributes(); }

    @Test void fullBodyDeepUrlsTitleDuplicatesAndAiOffAreExhaustiveAcrossPages() throws Exception {
        UUID owner=account();var b=browser(owner,"ROLE_USER");
        for(int i=0;i<12;i++) note(owner,"Note "+i,"https://example.test/saved/"+i,false);
        String prefix="word ".repeat(199980);
        String body=prefix+"[deep](https://example.test/deep?x=1#part) HTTPS://Example.test/saved/0";
        body=body+" ".repeat(1_000_000-body.length());
        UUID deep=note(owner,"https://example.test/title",body,false);
        note(account(),"Foreign","https://foreign.test/never",true);
        activate(b);
        var result=extraction.extract(active);
        assertThat(result.coverage()).isEqualTo(DeterministicUrlExtraction.Coverage.COMPLETE);
        assertThat(result.inspectedSources()).isEqualTo(13);
        assertThat(result.items()).hasSize(14);
        assertThat(result.items().stream().map(DeterministicUrlExtraction.Item::value)).contains("https://example.test/deep?x=1#part","https://example.test/title").noneMatch(s->s.contains("foreign"));
        var occurrence=result.items().stream().filter(i->i.value().contains("deep")).findFirst().orElseThrow().occurrences().getFirst();
        assertThat(occurrence.noteId()).isEqualTo(deep);assertThat(occurrence.offset()).isGreaterThan(990_000);
        assertThat(body.substring(occurrence.offset(),occurrence.offset()+occurrence.length())).isEqualTo(occurrence.display());
        assertThat(result.items().stream().filter(i->i.value().endsWith("saved/0")).findFirst().orElseThrow().occurrences()).hasSize(2);
        assertThat(jdbc.queryForObject("select count(*) from knowledge.knowledge_work_intent",Long.class)).isGreaterThanOrEqualTo(0L);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        verify(sourceSpy(),times(3)).readCurrent(any(),any(),any());
    }
    @Test void explicitLifecycleScopeIncludesTrashButNeverLogicalDeletion() throws Exception {
        UUID owner=account();activate(browser(owner,"ROLE_USER"));
        var n=note(owner,"Archived","https://example.test/archived",false);
        jdbc.update("update notes.note set lifecycle_state='archived' where note_id=?",n);
        assertThat(extraction.extract(active).items()).isEmpty();
        var archived=new PrivateKnowledgeSource.Scope(Set.of(PrivateKnowledgeSource.Lifecycle.ARCHIVED));
        assertThat(extraction.extract(archived).items()).hasSize(1);
        jdbc.update("update notes.note set lifecycle_state='trashed',pre_trash_state='archived',trashed_at=clock_timestamp() where note_id=?",n);
        var trash=new PrivateKnowledgeSource.Scope(Set.of(PrivateKnowledgeSource.Lifecycle.TRASHED));
        assertThat(extraction.extract(trash).items()).hasSize(1);
        jdbc.update("update notes.note set lifecycle_state='logically_deleted',pre_trash_state=null,trashed_at=null,deleted_at=clock_timestamp() where note_id=?",n);
        assertThat(extraction.extract(trash).items()).isEmpty();
    }
    @Test void capsAreExplicitAndDoNotClaimComplete() throws Exception {
        UUID owner=account();activate(browser(owner,"ROLE_USER"));
        var n=note(owner,"Results",java.util.stream.IntStream.range(0,501).mapToObj(i->"https://example.test/"+i).collect(java.util.stream.Collectors.joining(" ")),false);
        var capped=extraction.extract(active);
        assertThat(capped.coverage()).isEqualTo(DeterministicUrlExtraction.Coverage.TRUNCATED);assertThat(capped.items()).hasSize(500);assertThat(capped.resultsTruncated()).isTrue();
        jdbc.update("update notes.note set markdown=?,revision=revision+1 where note_id=?","https://example.test/one ".repeat(2001),n);
        var provenance=extraction.extract(active);
        assertThat(provenance.coverage()).isEqualTo(DeterministicUrlExtraction.Coverage.TRUNCATED);
        assertThat(provenance.items().getFirst().occurrences()).hasSize(2000);assertThat(provenance.recognizedOccurrences()).isEqualTo(2001);assertThat(provenance.provenanceTruncated()).isTrue();
    }
    @Test void postBoundaryNoteIsExcludedAndBoundaryIsBoundToCurrentOwner() throws Exception {
        UUID owner=account();var b=browser(owner,"ROLE_USER");activate(b);note(owner,"Before","https://example.test/before",false);
        var boundary=tx(()->sources.capture(active));
        note(owner,"After","https://example.test/after",false);
        assertThat(tx(()->sources.readCurrent(boundary,null,PrivateKnowledgeSource.Purpose.DETERMINISTIC_URL_EXTRACTION)).items()).hasSize(1);
        activate(browser(account(),"ROLE_USER"));
        assertThatThrownBy(()->tx(()->sources.readMetadata(boundary,null))).isInstanceOf(org.notesknowledge.websupport.ApiFailureException.class);
    }
    @Test void corpusCardinalityBoundIsTruthfulAndStopsBeforeUnboundedBodies() throws Exception {
        UUID owner=account();activate(browser(owner,"ROLE_USER"));
        jdbc.update("insert into notes.note(note_id,owner_user_id,title,markdown,lifecycle_state,ai_enabled,revision,ai_generation,created_at,updated_at) select uuidv7(),?,'Synthetic','','active',false,1,1,now(),now() from generate_series(1,10001)",owner);
        var result=extraction.extract(active);
        assertThat(result.coverage()).isEqualTo(DeterministicUrlExtraction.Coverage.TRUNCATED);assertThat(result.items()).isEmpty();
        assertThat(result.inspectedSources()).isBetween(0L,10000L);
    }
    @Test void sessionRevocationBeforeNextPageCannotLeakPreviouslyCollectedUrls() throws Exception {
        UUID owner=account();var b=browser(owner,"ROLE_USER");activate(b);note(owner,"Before","https://example.test/before",false);
        doAnswer(call->{sessions.deleteById(b.sessionId);return call.callRealMethod();}).when(sourceSpy()).readMetadata(any(),any());
        assertThatThrownBy(()->extraction.extract(active)).isInstanceOf(org.notesknowledge.websupport.ApiFailureException.class);
    }
    @Test void newlyVisibleSourceInsideCapturedFamilyBoundaryCannotBeSilentlyOmitted() throws Exception {
        UUID owner=account();activate(browser(owner,"ROLE_USER"));note(owner,"Existing","https://example.test/existing",false);
        doAnswer(call->{
            var n=note(owner,"Newly visible","https://example.test/inflight",false);
            // Test-only pre-boundary timestamp models a newly visible captured-family member, not a later source.
            jdbc.update("update notes.note set created_at=created_at-interval '1 minute' where note_id=?",n);
            return call.callRealMethod();
        }).when(sourceSpy()).readMetadata(any(),any());
        var result=extraction.extract(active);assertThat(result.coverage()).isEqualTo(DeterministicUrlExtraction.Coverage.CHANGED);assertThat(result.items()).isEmpty();
    }
    @Test void saveBetweenBodyAndMetadataPassInvalidatesAllRetainedResults() throws Exception {
        UUID owner=account();activate(browser(owner,"ROLE_USER"));var n=note(owner,"Before","https://example.test/before",false);
        doAnswer(call->{jdbc.update("update notes.note set markdown='https://example.test/after',revision=revision+1 where note_id=?",n);return call.callRealMethod();})
                .when(sourceSpy()).readMetadata(any(),any());
        var result=extraction.extract(active);assertThat(result.coverage()).isEqualTo(DeterministicUrlExtraction.Coverage.CHANGED);assertThat(result.items()).isEmpty();
    }
    @Test void concurrentTrashAfterMetadataPassIsCaughtByFinalAtomicFingerprint() throws Exception {
        UUID owner=account();activate(browser(owner,"ROLE_USER"));var n=note(owner,"Before","https://example.test/before",false);
        try(var pool=Executors.newSingleThreadExecutor()) {
            doAnswer(call->{
                // Last pass has already visited this row. Mutate on another connection before its final boundary check.
                pool.submit(()->jdbc.update("update notes.note set lifecycle_state='trashed',pre_trash_state='active',trashed_at=clock_timestamp(),revision=revision+1 where note_id=?",n)).get(10,TimeUnit.SECONDS);
                return call.callRealMethod();
            }).when(sourceSpy()).currentFingerprint(any());
            var result=extraction.extract(active);assertThat(result.coverage()).isEqualTo(DeterministicUrlExtraction.Coverage.CHANGED);assertThat(result.items()).isEmpty();
        }
    }
    @Test void disappearingNoteNeverReturnsStaleContent() throws Exception {
        UUID owner=account();activate(browser(owner,"ROLE_USER"));var n=note(owner,"Before","https://example.test/before",false);
        doAnswer(call->{jdbc.update("delete from notes.note where note_id=?",n);return call.callRealMethod();}).when(sourceSpy()).readMetadata(any(),any());
        assertThat(extraction.extract(active).coverage()).isEqualTo(DeterministicUrlExtraction.Coverage.CHANGED);
    }
    @Test void extractionRejectsAmbientLongTransactionAndSourceCallsRequireTransaction() throws Exception {
        activate(browser(account(),"ROLE_USER"));
        assertThatThrownBy(()->extraction.extract(activeWithinInvalidScope())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->tx(()->extraction.extract(active))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->sources.capture(active)).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }
    private PrivateKnowledgeSource.Scope activeWithinInvalidScope() { return new PrivateKnowledgeSource.Scope(Set.of()); }
    @Test void lexicalSpiReusesPrivateOwnerRankingAndRevisionWithoutAiGate() throws Exception {
        UUID owner=account();activate(browser(owner,"ROLE_USER"));var n=note(owner,"PlayStation","Synthetic saved ps reminder",false);
        note(account(),"PlayStation","ps ps ps",true);
        var result=tx(()->lexical.search(new PrivateLexicalSearch.Query("ps",PrivateKnowledgeSource.Lifecycle.ACTIVE,List.of())));
        assertThat(result).hasSize(1);assertThat(result.getFirst().noteId()).isEqualTo(n);assertThat(result.getFirst().revision()).isEqualTo(1);
    }
    @Test void anonymousRestrictedAndSuspendedCannotReadSources() throws Exception {
        UUID owner=account();activate(browser(owner,"ROLE_PRE_MFA"));
        assertThatThrownBy(()->extraction.extract(active)).isInstanceOf(org.notesknowledge.websupport.ApiFailureException.class);
        activate(browser(owner,"ROLE_USER"));jdbc.update("update identity.account set account_state='suspended' where user_id=?",owner);
        assertThatThrownBy(()->extraction.extract(active)).isInstanceOf(org.notesknowledge.websupport.ApiFailureException.class);
        SecurityContextHolder.clearContext();assertThatThrownBy(()->extraction.extract(active)).isInstanceOf(org.notesknowledge.websupport.ApiFailureException.class);
    }

    @Test void policyReadFailsClosedWithoutEffectivePolicyAndNoProviderProseIsInvented() throws Exception {
        var b=browser(account(),"ROLE_USER");getPolicy(b,503);
        policy(1);var body=getPolicy(b,200);
        assertThat(body.get("acknowledgementRequired").asBoolean()).isTrue();
        assertThat(body.toString()).doesNotContain("provider","terms","secret","ownerUserId","UserId");
        when(configuration.code()).thenReturn(null);getPolicy(b,503);
    }
    @Test void exactAcknowledgementIsIdempotentImmutableAndNeverChangesNoteAiState() throws Exception {
        UUID owner=account();var b=browser(owner,"ROLE_USER");var n=note(owner,"Synthetic","Body",false);policy(1);
        var request=ackBody(getPolicy(b,200));postAck(b,request,204);postAck(b,request,204);
        assertThat(getPolicy(b,200).get("acknowledged").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from knowledge.processing_policy_acknowledgement where user_id=?",Integer.class,owner)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select ai_enabled from notes.note where note_id=?",Boolean.class,n)).isFalse();
        assertThatThrownBy(()->jdbc.update("update knowledge.processing_policy_acknowledgement set disclosure_revision='other' where user_id=?",owner)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("delete from knowledge.processing_policy_acknowledgement where user_id=?",owner)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test void rotationRequiresNewExactPolicyAndStaleDisplayIs409() throws Exception {
        var b=browser(account(),"ROLE_USER");policy(1);var previous=ackBody(getPolicy(b,200));postAck(b,previous,204);
        policy(2);assertThat(getPolicy(b,200).get("acknowledgementRequired").asBoolean()).isTrue();
        var response=postAck(b,previous,409);assertThat(response).contains("processing_policy_changed");
        postAck(b,ackBody(getPolicy(b,200)),204);assertThat(getPolicy(b,200).get("acknowledged").asBoolean()).isTrue();
    }
    @Test void futureAndRetiredPoliciesAreNotCurrent() throws Exception {
        var b=browser(account(),"ROLE_USER");
        UUID p=policy(1);jdbc.update("update knowledge.processing_policy set retired_at=clock_timestamp() where processing_policy_id=?",p);getPolicy(b,503);
        jdbc.update("insert into knowledge.processing_policy(policy_code,policy_version,policy_fingerprint,disclosure_revision,effective_at) values(?,2,?,'synthetic-1',clock_timestamp()+interval '1 day')",code,"a".repeat(64));getPolicy(b,503);
    }
    @Test void forgedExactIdentityDoesNotCreateAcknowledgementEvidence() throws Exception {
        var b=browser(account(),"ROLE_USER");policy(1);var original=ackBody(getPolicy(b,200));
        for(var change:List.of(Map.entry("processingPolicyId",UUID.randomUUID().toString()),Map.entry("policyFingerprint","b".repeat(64)),Map.entry("disclosureRevision","synthetic-2"))) {
            var bad=new HashMap<>(original);bad.put(change.getKey(),change.getValue());postAck(b,bad,409);
        }
        assertThat(jdbc.queryForObject("select count(*) from knowledge.processing_policy_acknowledgement where user_id=?",Integer.class,b.owner)).isZero();
    }
    @Test void selectedPolicyCannotRotateBeforeAcknowledgementTransactionCommits() throws Exception {
        var b=browser(account(),"ROLE_USER");policy(1);var request=ackBody(getPolicy(b,200));
        try(var pool=Executors.newSingleThreadExecutor()) {
            doAnswer(call->{
                assertThat(pool.submit(()->jdbc.queryForObject("select pg_try_advisory_xact_lock(hashtextextended(?,719))",Boolean.class,code)).get(10,TimeUnit.SECONDS)).isFalse();
                return call.callRealMethod();
            }).when(policyRepository).acknowledge(any(),any());
            postAck(b,request,204);
            assertThat(jdbc.queryForObject("select pg_try_advisory_xact_lock(hashtextextended(?,719))",Boolean.class,code)).isTrue();
        }
    }
    @Test void policySecurityAndCsrfAreExactAndFailClosed() throws Exception {
        UUID owner=account();policy(1);var b=browser(owner,"ROLE_USER");var body=ackBody(getPolicy(b,200));
        mvc.perform(get("/api/ai/processing-policy")).andExpect(status().isUnauthorized());
        getPolicy(browser(account(),"ROLE_PRE_MFA"),403);
        mvc.perform(post("/api/ai/processing-policy/acknowledgements").cookie(b.cookie).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andExpect(status().isForbidden());
        jdbc.update("update identity.account set account_state='suspended' where user_id=?",owner);getPolicy(b,401);
        mvc.perform(get("/api/ai/not-authorized").cookie(browser(account(),"ROLE_USER").cookie)).andExpect(status().isForbidden());
    }
    @Test void acknowledgementRejectsUnknownMalformedFalseMissingAndOversizedInputWithoutEcho() throws Exception {
        var b=browser(account(),"ROLE_USER");policy(1);var original=ackBody(getPolicy(b,200));
        for(String field:List.of("confirmAcknowledgement","policyVersion","processingPolicyId","policyFingerprint")) {
            var bad=new HashMap<>(original);bad.put(field,field.equals("confirmAcknowledgement")?false:"private-marker");postAck(b,bad,422);
        }
        var unknown=new HashMap<>(original);unknown.put("ownerUserId","private-marker");assertThat(postAck(b,unknown,422)).doesNotContain("private-marker");
        postAck(b,Map.of(),422);
        mvc.perform(post("/api/ai/processing-policy/acknowledgements").cookie(b.cookie).header("X-CSRF-TOKEN",b.csrf).contentType(MediaType.APPLICATION_JSON).content("{")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/ai/processing-policy/acknowledgements").cookie(b.cookie).header("X-CSRF-TOKEN",b.csrf).contentType(MediaType.APPLICATION_JSON).content(" ".repeat(4097))).andExpect(status().isContentTooLarge());
    }
    @Test void policyDatabaseFailureIsSanitized503() throws Exception {
        var b=browser(account(),"ROLE_USER");
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("synthetic-secret SQL host-path")) .when(policyRepository).current(any());
        assertThat(getPolicy(b,503).toString()).doesNotContain("synthetic-secret","SQL","host-path","Exception");
    }

    @Test void enqueueDedupeAndDifferentLineageAreDurableButDoNotScheduleWork() {
        var e=expectation(true);var first=work.enqueueIfAbsent(KnowledgeWork.Kind.NOTE,e);
        assertThat(work.enqueueIfAbsent(KnowledgeWork.Kind.NOTE,e).id()).isEqualTo(first.id());
        var newer=new PrivateAiSourceCurrentness.Expected(e.owner(),e.noteId(),null,2,2,null);
        assertThat(work.enqueueIfAbsent(KnowledgeWork.Kind.NOTE,newer).id()).isNotEqualTo(first.id());
        assertThat(first.state()).isEqualTo("queued");assertThat(first.attemptCount()).isZero();
        assertThatThrownBy(()->work.enqueueIfAbsent(KnowledgeWork.Kind.ATTACHMENT,e)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void concurrentEnqueueHasOneActiveRow() throws Exception {
        var e=expectation(true);try(var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);
            var a=pool.submit(()->{start.await();return work.enqueueIfAbsent(KnowledgeWork.Kind.NOTE,e);});
            var b=pool.submit(()->{start.await();return work.enqueueIfAbsent(KnowledgeWork.Kind.NOTE,e);});start.countDown();
            assertThat(a.get(10,TimeUnit.SECONDS).id()).isEqualTo(b.get(10,TimeUnit.SECONDS).id());
        }
    }
    @Test void boundedClaimsAreOrderedAndTwoClaimersNeverDuplicate() throws Exception {
        for(int i=0;i<8;i++) work.enqueueIfAbsent(KnowledgeWork.Kind.NOTE,expectation(true));
        assertThatThrownBy(()->work.claim(new LeaseOwner("worker"),11)).isInstanceOf(IllegalArgumentException.class);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);
            var a=pool.submit(()->{start.await();return work.claim(new LeaseOwner("first"),4);});
            var b=pool.submit(()->{start.await();return work.claim(new LeaseOwner("second"),4);});start.countDown();
            var left=a.get(10,TimeUnit.SECONDS);var right=b.get(10,TimeUnit.SECONDS);
            assertThat(left).hasSize(4);assertThat(right).hasSize(4);
            var ids=new HashSet<UUID>();left.forEach(c->assertThat(ids.add(c.intent().id())).isTrue());right.forEach(c->assertThat(ids.add(c.intent().id())).isTrue());
            left.forEach(c->{assertThat(c.token().value().version()).isEqualTo(7);assertThat(c.until()).isNotNull();assertThat(c.intent().attemptCount()).isEqualTo(1);});
        }
    }
    @Test void skipLockedDoesNotWaitForAnotherClaimTransaction() throws Exception {
        var first=work.enqueueIfAbsent(KnowledgeWork.Kind.NOTE,expectation(true));var second=work.enqueueIfAbsent(KnowledgeWork.Kind.NOTE,expectation(true));
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newSingleThreadExecutor()) {
            var blocker=pool.submit(()->tx(()->{
                jdbc.queryForObject("select knowledge_work_intent_id from knowledge.knowledge_work_intent where knowledge_work_intent_id=? for update",UUID.class,first.id());
                locked.countDown();try {assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();}catch(InterruptedException e){throw new IllegalStateException(e);}return true;
            }));
            assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();
            try {assertThat(work.claim(new LeaseOwner("other"),1).getFirst().intent().id()).isEqualTo(second.id());}
            finally {release.countDown();}blocker.get(10,TimeUnit.SECONDS);
        }
    }
    @Test void rollbackLeavesReadyWorkUnclaimedAndNoAmbientTransactionSpansCallerEffect() {
        var first=work.enqueueIfAbsent(KnowledgeWork.Kind.NOTE,expectation(true));
        var tx=new TransactionTemplate(transactions);tx.executeWithoutResult(s->{assertThat(work.claim(new LeaseOwner("rolled"),1)).hasSize(1);s.setRollbackOnly();});
        assertThat(state(first.id())).isEqualTo("queued");
        assertThat(work.claim(new LeaseOwner("real"),1)).hasSize(1);assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }
    @Test void reclaimIssuesFreshTokenAndFencesAllOldOperations() {
        work.enqueueIfAbsent(KnowledgeWork.Kind.NOTE,expectation(true));var old=work.claim(new LeaseOwner("first"),1).getFirst();
        assertThat(work.heartbeat(old)).isTrue();expire(old);
        assertThat(work.heartbeat(old)).isFalse();assertThat(work.retry(old)).isFalse();assertThat(work.complete(old)).isFalse();
        var fresh=work.reclaim(new LeaseOwner("second"),1).getFirst();assertThat(fresh.token()).isNotEqualTo(old.token());
        assertThat(work.obsolete(old)).isFalse();assertThat(work.fail(old,KnowledgeWork.Failure.INVALID_SOURCE)).isFalse();
        assertThat(work.obsolete(fresh)).isTrue();assertThat(work.complete(fresh)).isFalse();assertThat(work.heartbeat(fresh)).isFalse();
    }
    @Test void retryChargesAttemptOnceBoundsBackoffAndEventuallyFails() {
        work.enqueueIfAbsent(KnowledgeWork.Kind.NOTE,expectation(true));
        for(int attempt=1;attempt<=5;attempt++) {
            var c=work.claim(new LeaseOwner("retry"),1).getFirst();assertThat(c.intent().attemptCount()).isEqualTo(attempt);
            assertThat(work.retry(c)).isTrue();assertThat(work.retry(c)).isFalse();
            assertThat(jdbc.queryForObject("select attempt_count from knowledge.knowledge_work_intent where knowledge_work_intent_id=?",Integer.class,c.intent().id())).isEqualTo(attempt);
            if(attempt<5) {
                assertThat(state(c.intent().id())).isEqualTo("retry_wait");
                var delay=jdbc.queryForObject("select extract(epoch from next_attempt_at-updated_at) from knowledge.knowledge_work_intent where knowledge_work_intent_id=?",Double.class,c.intent().id());
                assertThat(delay).isBetween(1.9,300.0);
                jdbc.update("update knowledge.knowledge_work_intent set next_attempt_at=clock_timestamp()-interval '1 second' where knowledge_work_intent_id=?",c.intent().id());
            } else {assertThat(state(c.intent().id())).isEqualTo("failed");assertThat(work.reclaim(new LeaseOwner("later"),1)).isEmpty();}
        }
    }
    @Test void exhaustedAbandonedClaimBecomesFailedWithoutInfiniteRecovery() {
        work.enqueueIfAbsent(KnowledgeWork.Kind.NOTE,expectation(true));var c=work.claim(new LeaseOwner("first"),1).getFirst();
        for(int i=1;i<5;i++) {expire(c);c=work.reclaim(new LeaseOwner("next"),1).getFirst();}
        expire(c);assertThat(work.reclaim(new LeaseOwner("last"),1)).isEmpty();assertThat(state(c.intent().id())).isEqualTo("failed");
    }
    @Test void currentAccountSourcePolicyAndLeaseAreAllRequiredForCompletion() throws Exception {
        var e=expectation(true);var b=browser(e.owner(),"ROLE_USER");policy(1);postAck(b,ackBody(getPolicy(b,200)),204);
        work.enqueueIfAbsent(KnowledgeWork.Kind.NOTE,e);var c=work.claim(new LeaseOwner("worker"),1).getFirst();
        assertThat(work.revalidate(c)).isPresent();assertThat(work.complete(c)).isTrue();assertThat(work.complete(c)).isFalse();
        assertThat(work.heartbeat(c)).isFalse();assertThat(state(c.intent().id())).isEqualTo("completed");
        var d=work.enqueueIfAbsent(KnowledgeWork.Kind.NOTE,e);assertThat(d.id()).isNotEqualTo(c.intent().id());
    }
    @Test void claimedExpectationForgeryCannotFinalizeOrHeartbeat() throws Exception {
        var e=expectation(true);var b=browser(e.owner(),"ROLE_USER");policy(1);postAck(b,ackBody(getPolicy(b,200)),204);
        work.enqueueIfAbsent(KnowledgeWork.Kind.NOTE,e);var c=work.claim(new LeaseOwner("worker"),1).getFirst();
        var swapped=new PrivateAiSourceCurrentness.Expected(e.owner(),note(e.owner(),"Other","Body",true),null,1,1,null);
        var forged=new KnowledgeWork.Claim(new KnowledgeWork.Intent(c.intent().id(),KnowledgeWork.Kind.NOTE,swapped,"claimed",1,5),c.owner(),c.token(),c.until());
        assertThat(work.revalidate(forged)).isEmpty();assertThat(work.heartbeat(forged)).isFalse();assertThat(work.complete(forged)).isFalse();assertThat(state(c.intent().id())).isEqualTo("claimed");
        assertThat(work.complete(c)).isTrue();
    }
    @Test void lineageAndTerminalStateCannotBeRewrittenInDatabase() {
        var e=expectation(true);var intent=work.enqueueIfAbsent(KnowledgeWork.Kind.NOTE,e);
        assertThatThrownBy(()->jdbc.update("update knowledge.knowledge_work_intent set expected_ai_generation=2 where knowledge_work_intent_id=?",intent.id())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        var c=work.claim(new LeaseOwner("worker"),1).getFirst();assertThat(work.fail(c,KnowledgeWork.Failure.INVALID_SOURCE)).isTrue();
        assertThatThrownBy(()->jdbc.update("update knowledge.knowledge_work_intent set state='queued',next_attempt_at=clock_timestamp() where knowledge_work_intent_id=?",intent.id())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("select failure_code from knowledge.knowledge_work_intent where knowledge_work_intent_id=?",String.class,intent.id())).isEqualTo("invalid_source");
    }
    @Test void queuedExpectationsDoNotAuthorizeAiOffOrStaleGenerationOrWrongOwner() throws Exception {
        var e=expectation(false);var b=browser(e.owner(),"ROLE_USER");policy(1);postAck(b,ackBody(getPolicy(b,200)),204);
        work.enqueueIfAbsent(KnowledgeWork.Kind.NOTE,e);var c=work.claim(new LeaseOwner("worker"),1).getFirst();
        assertThat(work.revalidate(c)).isEmpty();assertThat(work.complete(c)).isFalse();
        jdbc.update("update notes.note set ai_enabled=true,ai_generation=ai_generation+1 where note_id=?",e.noteId());
        assertThat(work.revalidate(c)).isEmpty();assertThat(work.obsolete(c)).isTrue();
        var wrong=new PrivateAiSourceCurrentness.Expected(account(),e.noteId(),null,1,2,null);
        assertThatThrownBy(()->work.enqueueIfAbsent(KnowledgeWork.Kind.NOTE,wrong)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test void policyRotationAndAccountSuspensionInvalidateWorkPrerequisites() throws Exception {
        var e=expectation(true);var b=browser(e.owner(),"ROLE_USER");policy(1);postAck(b,ackBody(getPolicy(b,200)),204);
        work.enqueueIfAbsent(KnowledgeWork.Kind.NOTE,e);var c=work.claim(new LeaseOwner("worker"),1).getFirst();assertThat(work.revalidate(c)).isPresent();
        policy(2);assertThat(work.revalidate(c)).isEmpty();postAck(b,ackBody(getPolicy(b,200)),204);assertThat(work.revalidate(c)).isPresent();
        jdbc.update("update identity.account set account_state='suspended' where user_id=?",e.owner());assertThat(work.revalidate(c)).isEmpty();assertThat(work.complete(c)).isFalse();
    }
    @Test void attachmentCurrentnessRequiresAcceptedRetainedBytesAndExpectedGeneration() throws Exception {
        var n=expectation(true);var b=browser(n.owner(),"ROLE_USER");policy(1);postAck(b,ackBody(getPolicy(b,200)),204);
        UUID attachment=jdbc.queryForObject("""
                insert into notes.attachment(attachment_id,note_id,owner_user_id,media_kind,object_reference,display_filename,
                media_type,size_bytes,width,height,storage_state,validation_state,cleanup_state,revision,processing_generation,created_at,updated_at)
                values(uuidv7(),?,?,'image',?,'synthetic.png','image/png',64,1,1,'stored','accepted','retained',1,1,now(),now()) returning attachment_id
                """,UUID.class,n.noteId(),n.owner(),"private-attachment/"+"1".repeat(64));
        var e=new PrivateAiSourceCurrentness.Expected(n.owner(),n.noteId(),attachment,1,1,1L);
        work.enqueueIfAbsent(KnowledgeWork.Kind.ATTACHMENT,e);var c=work.claim(new LeaseOwner("worker"),1).getFirst();assertThat(work.revalidate(c)).isPresent();
        jdbc.update("update notes.attachment set cleanup_state='pending',removed_at=clock_timestamp(),revision=revision+1,processing_generation=processing_generation+1,updated_at=clock_timestamp() where attachment_id=?",attachment);
        assertThat(work.revalidate(c)).isEmpty();assertThat(work.complete(c)).isFalse();assertThat(work.obsolete(c)).isTrue();
    }
    @Test void schemaConstraintsAndRestrictiveForeignKeysRejectInventedAuthority() {
        var e=expectation(true);var intent=work.enqueueIfAbsent(KnowledgeWork.Kind.NOTE,e);
        for(String columnValue:List.of("state='invented'","scope_kind='public'","work_class='generic_admin'","failure_code='arbitrary secret detail'","max_attempts=99"))
            assertThatThrownBy(()->jdbc.update("update knowledge.knowledge_work_intent set "+columnValue+" where knowledge_work_intent_id=?",intent.id())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("delete from notes.note where note_id=?",e.noteId())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(jdbc.queryForList("select confdeltype::text from pg_constraint where conrelid='knowledge.knowledge_work_intent'::regclass and contype='f'",String.class)).hasSize(3).containsOnly("r");
        assertThat(jdbc.queryForObject("select count(*) from pg_tables where schemaname='knowledge'",Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForList("select extname from pg_extension",String.class)).doesNotContain("vector");
        assertThat(jdbc.queryForObject("select current_setting('server_version')",String.class)).startsWith("18.");
    }
    @Test void policyIdentityIsImmutableRetirementOneWayAndForeignKeysRestrictive() {
        UUID policy=policy(1);
        assertThatThrownBy(()->jdbc.update("update knowledge.processing_policy set policy_fingerprint=? where processing_policy_id=?","b".repeat(64),policy)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("delete from knowledge.processing_policy where processing_policy_id=?",policy)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        jdbc.update("update knowledge.processing_policy set retired_at=clock_timestamp() where processing_policy_id=?",policy);
        assertThatThrownBy(()->jdbc.update("update knowledge.processing_policy set retired_at=null where processing_policy_id=?",policy)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(jdbc.queryForList("select confdeltype::text from pg_constraint where conrelid='knowledge.processing_policy_acknowledgement'::regclass and contype='f'",String.class)).containsOnly("r").hasSize(2);
    }
    @Test void readyAndRecoveryPathsHaveSeparateUsableIndexes() {
        tx(()->{
            jdbc.execute("set local enable_seqscan=off");
            String ready=String.join("\n",jdbc.queryForList("explain select knowledge_work_intent_id from knowledge.knowledge_work_intent where state in ('queued','retry_wait') and next_attempt_at<=clock_timestamp() order by next_attempt_at,created_at,knowledge_work_intent_id limit 10 for update skip locked",String.class));
            String reclaim=String.join("\n",jdbc.queryForList("explain select knowledge_work_intent_id from knowledge.knowledge_work_intent where state='claimed' and lease_until<=clock_timestamp() order by lease_until,created_at,knowledge_work_intent_id limit 10 for update skip locked",String.class));
            assertThat(ready).contains("ix_knowledge_work_ready","LockRows");assertThat(reclaim).contains("ix_knowledge_work_reclaim","LockRows");return true;
        });
    }

    private <T> T tx(java.util.function.Supplier<T> supplier) { return new TransactionTemplate(transactions).execute(s->supplier.get()); }
    private PrivateKnowledgeSource sourceSpy() { return org.springframework.test.util.AopTestUtils.getUltimateTargetObject(sources); }
    private String state(UUID id) { return jdbc.queryForObject("select state from knowledge.knowledge_work_intent where knowledge_work_intent_id=?",String.class,id); }
    private void expire(KnowledgeWork.Claim c) { jdbc.update("update knowledge.knowledge_work_intent set lease_until=clock_timestamp()-interval '1 second' where knowledge_work_intent_id=?",c.intent().id()); }
    private PrivateAiSourceCurrentness.Expected expectation(boolean enabled) { UUID owner=account();return new PrivateAiSourceCurrentness.Expected(owner,note(owner,"Synthetic","Body",enabled),null,1,1,null); }
    private UUID policy(long version) { return jdbc.queryForObject("insert into knowledge.processing_policy(policy_code,policy_version,policy_fingerprint,disclosure_revision,effective_at) values(?,?,?,'synthetic-1',clock_timestamp()-interval '1 minute') returning processing_policy_id",UUID.class,code,version,"a".repeat(64)); }
    private tools.jackson.databind.JsonNode getPolicy(Browser b,int statusCode) throws Exception {
        var response=mvc.perform(get("/api/ai/processing-policy").cookie(b.cookie)).andExpect(status().is(statusCode)).andReturn().getResponse();
        assertThat(response.getHeader("Cache-Control")).contains("no-store");return json.readTree(response.getContentAsString());
    }
    private Map<String,Object> ackBody(tools.jackson.databind.JsonNode p) {
        return Map.of("processingPolicyId",p.get("processingPolicyId").asText(),"policyCode",p.get("policyCode").asText(),"policyVersion",p.get("policyVersion").asLong(),"policyFingerprint",p.get("policyFingerprint").asText(),"disclosureRevision",p.get("disclosureRevision").asText(),"confirmAcknowledgement",true);
    }
    private String postAck(Browser b,Map<String,Object> body,int expected) throws Exception {
        return mvc.perform(post("/api/ai/processing-policy/acknowledgements").cookie(b.cookie).header("X-CSRF-TOKEN",b.csrf).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
    }
    private UUID account() {
        UUID id=jdbc.queryForObject("select uuidv7()",UUID.class);String email="synthetic-"+id+"@example.test";
        jdbc.update("insert into identity.account(user_id,canonical_email,display_email,email_verified_at,account_state,created_at,updated_at) values(?,?,?,now(),'active',now(),now())",id,email,email);return id;
    }
    private UUID note(UUID owner,String title,String body,boolean ai) {
        return jdbc.queryForObject("insert into notes.note(note_id,owner_user_id,title,markdown,lifecycle_state,ai_enabled,revision,ai_generation,created_at,updated_at) values(uuidv7(),?,?,?,'active',?,1,1,clock_timestamp(),clock_timestamp()) returning note_id",UUID.class,owner,title,body,ai);
    }
    private Browser browser(UUID owner,String role) throws Exception {
        Session session=sessions.createSession();var context=SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(new IdentitySessionPrincipal(owner),null,List.of(new SimpleGrantedAuthority(role))));
        session.setAttribute("SPRING_SECURITY_CONTEXT",context);save(session);
        var cookie=new Cookie("SESSION",Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8)));
        var response=mvc.perform(get("/api/auth/csrf").cookie(cookie)).andExpect(status().isOk()).andReturn().getResponse();
        return new Browser(owner,session.getId(),role,response.getCookie("SESSION")==null?cookie:response.getCookie("SESSION"),json.readTree(response.getContentAsString()).get("csrfToken").asText());
    }
    private void activate(Browser b) {
        var context=SecurityContextHolder.createEmptyContext();context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(new IdentitySessionPrincipal(b.owner),null,List.of(new SimpleGrantedAuthority(b.role))));SecurityContextHolder.setContext(context);
        var request=new MockHttpServletRequest();request.setSession(new MockHttpSession(null,b.sessionId));RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
    @SuppressWarnings({"rawtypes","unchecked"}) private void save(Session s) { ((SessionRepository)sessions).save(s); }
    private record Browser(UUID owner,String sessionId,String role,Cookie cookie,String csrf) { }
}
