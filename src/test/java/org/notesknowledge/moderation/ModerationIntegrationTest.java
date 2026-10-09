package org.notesknowledge.moderation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.notesknowledge.PublicDiscoveryFixtures;
import org.notesknowledge.identity.*;
import org.notesknowledge.publishing.PublicationModerationApi;
import org.notesknowledge.security.RateLimitPort;
import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.*;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.*;

@Tag("DATABASE") @Tag("API") @Tag("SECURITY")
@Testcontainers @SpringBootTest @AutoConfigureMockMvc
@org.springframework.context.annotation.Import(org.notesknowledge.publishing.ModerationMediaFixture.class)
class ModerationIntegrationTest {
    @Container static final PostgreSQLContainer postgres=new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18-trixie").withDatabaseName("moderation_synthetic").withUsername("synthetic_migrator").withPassword("synthetic-moderation-password");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",postgres::getJdbcUrl);r.add("spring.datasource.username",postgres::getUsername);r.add("spring.datasource.password",postgres::getPassword);
        r.add("identity.rate.key-base64",()->"AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=");
    }
    @Autowired MockMvc mvc;@Autowired JdbcTemplate jdbc;@Autowired ObjectMapper json;@Autowired Clock clock;
    @Autowired SessionRepository<? extends Session> sessions;@Autowired PlatformTransactionManager manager;
    @Autowired org.notesknowledge.websupport.StrongCoreEtagCodec etags;
    @MockitoBean RateLimitPort rates;
    @MockitoSpyBean AccountSuspensionApi suspension;
    @MockitoSpyBean PublicationModerationApi publishing;
    @BeforeEach void reset(){PublicDiscoveryFixtures.retireAll(jdbc);org.mockito.Mockito.when(rates.evaluate(any())).thenReturn(new RateLimitPort.Allowed());}
    @Test void authenticatedIntakeIsBoundedPublicOnlyAndDuplicateSafe()throws Exception {
        var p=publication();var b=browser(account());var result=send(post(reportPath(p.id())).contentType("application/json").content(intake()),b,201);
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        var receipt=tree(result);assertThat(receipt.toString()).doesNotContain(p.owner().toString(),"source_note","reporter","actor","object_reference");
        send(post(reportPath(p.id())).contentType("application/json").content(intake()),b,409);
        assertThat(count("moderation.report","publication_id",p.id())).isEqualTo(1);
        send(post(reportPath(p.id())).contentType("application/json").content("{\"category\":\"invented\",\"description\":\"context\"}"),b,422);
        send(post(reportPath(p.id())).contentType("application/json").content("{\"category\":\"spam\",\"description\":\""+"x".repeat(2001)+"\"}"),b,422);
        send(post(reportPath(p.id())).contentType("application/json").content("{\"category\":\"spam\",\"description\":\"context\",\"userId\":\"ignored\"}"),b,400);
        send(post(reportPath(p.id())).contentType("application/json").content(" ".repeat(8193)),b,413);
        send(post(reportPath(p.id())).contentType("application/json").content("{"),b,400);
    }
    @Test void anonymousPendingAndMissingCsrfCannotSubmitOrReview()throws Exception {
        var p=publication();var b=browser(account());
        assertStatus(mvc.perform(post(reportPath(p.id())).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()).contentType("application/json").content(intake())).andReturn(),401);
        assertStatus(mvc.perform(post(reportPath(p.id())).cookie(b.cookie).contentType("application/json").content(intake())).andReturn(),403);
        var pending=browser(account(),clock.instant(),false);send(post(reportPath(p.id())).contentType("application/json").content(intake()),pending,403);
        assertStatus(mvc.perform(get("/api/moderation/reports")).andReturn(),401);
    }
    @Test void inactiveTargetsAndUnknownTargetsAreNotReportable()throws Exception {
        var p=publication();var b=browser(account());
        jdbc.update("update publishing.publication set availability='unpublished',publication_generation=2,unpublished_at=now() where publication_id=?",p.id());
        send(post(reportPath(p.id())).contentType("application/json").content(intake()),b,404);
        send(post(reportPath(UUID.fromString("01990a55-9e12-7ac4-8f5b-31aa4a91d401"))).contentType("application/json").content(intake()),b,404);
        var suspended=publication();jdbc.update("update identity.account set account_state='suspended' where user_id=?",suspended.owner());
        send(post(reportPath(suspended.id())).contentType("application/json").content(intake()),b,404);
    }
    @Test void scopedQueueDetailAndCursorCannotEscapeCurrentGrants()throws Exception {
        var p=publication();var a=report(p);var other=report(publication());var moderator=browser(account());grant(moderator.user,"moderation.review",a);
        var page=tree(send(get("/api/moderation/reports"),moderator,200));assertThat(page.get("items").size()).isEqualTo(1);assertThat(page.get("items").get(0).get("id").asText()).isEqualTo(a.toString());
        var detail=tree(send(get(path(a)),moderator,200));assertThat(detail.get("publicEvidence").get("title").asText()).isEqualTo("Public synthetic");
        assertThat(detail.toString()).doesNotContain("PRIVATE_CANARY",p.owner().toString(),"source_note","actorUserId","reporterUserId","object_reference");
        send(get(path(other)),moderator,404);send(get(path(a)),browser(account()),403);
        grant(moderator.user,"moderation.review",other);var first=tree(send(get("/api/moderation/reports").param("limit","1"),moderator,200));String cursor=first.get("nextCursor").asText();
        send(get("/api/moderation/reports").param("limit","1").param("cursor",cursor),moderator,200);
        send(get("/api/moderation/reports").param("cursor",cursor).param("category","spam"),moderator,400);
        send(get("/api/moderation/reports").param("cursor",cursor+"x"),moderator,400);
        var second=browser(account());grant(second.user,"moderation.review",a);grant(second.user,"moderation.review",other);
        send(get("/api/moderation/reports").param("cursor",cursor),second,400);
        send(get("/api/moderation/reports").param("sort","arbitrary"),moderator,400);
        send(get("/api/moderation/reports").param("state","arbitrary"),moderator,400);
    }
    @Test void recentProofAndFullMfaAuthorityAreRequiredForAllModeratorReadsAndWrites()throws Exception {
        var r=report(publication());UUID user=account();grant(user,"moderation.review",r);grant(user,"moderation.enforce",r);
        var noProof=browser(user,null,true);send(get(path(r)),noProof,403);send(post(path(r)+"/begin-review"),noProof,403);
        var old=browser(user,clock.instant().minusSeconds(86400),true);send(get("/api/moderation/reports"),old,403);
        var pending=browser(user,clock.instant(),false);send(get(path(r)),pending,403);decision(pending,r,"none",403);
    }
    @Test void reviewAndEnforceAreSeparateAndRevocationIsImmediate()throws Exception {
        var r=report(publication());var review=browser(account());grant(review.user,"moderation.review",r);
        send(post(path(r)+"/begin-review"),review,200);decision(review,r,"none",403);
        assertThat(jdbc.queryForObject("select count(*) from moderation.moderation_audit_fact where report_id=? and actor_user_id=? and action_code='denied'",Integer.class,r,review.user)).isEqualTo(1);
        var enforce=browser(account());grant(enforce.user,"moderation.enforce",r);send(get(path(r)),enforce,403);
        jdbc.update("update identity.privilege_assignment set revoked_at=clock_timestamp() where user_id=?",review.user);
        send(get(path(r)),review,403);send(post(path(r)+"/begin-review"),review,403);
        jdbc.update("update identity.privilege_assignment set revoked_at=clock_timestamp() where user_id=?",enforce.user);decision(enforce,r,"none",403);
    }
    @Test void beginIsIdempotentAuditedAndTerminalCannotReopen()throws Exception {
        var r=report(publication());var b=moderator(r);send(post(path(r)+"/begin-review"),b,200);send(post(path(r)+"/begin-review"),b,200);
        assertThat(jdbc.queryForObject("select count(*) from moderation.moderation_audit_fact where report_id=? and action_code='begin_review'",Integer.class,r)).isEqualTo(1);
        decision(b,r,"none",201);send(post(path(r)+"/begin-review"),b,409);decision(b,r,"none",409);
        assertThat(count("moderation.moderation_decision","report_id",r)).isEqualTo(1);
    }
    @Test void suspendedAndDeletedModeratorsCannotReuseExistingSessionOrPersistedGrants()throws Exception {
        var r=report(publication());
        for(String state:List.of("suspended","logically_deleted")) {
            var b=moderator(r);jdbc.update("update identity.account set account_state=? where user_id=?",state,b.user);
            send(get("/api/moderation/reports"),b,401);send(get(path(r)),b,401);
            // The read invalidates the old session; unsafe replay then has no current CSRF authority.
            send(post(path(r)+"/begin-review"),b,403);decision(b,r,"none",403);
        }
        assertThat(state(r)).isEqualTo("open");assertThat(count("moderation.moderation_decision","report_id",r)).isZero();
    }
    @Test void unavailableOrChangedOpenTargetClosesWithoutPrivateFallback()throws Exception {
        var p=publication();var r=report(p);var b=moderator(r);
        jdbc.update("update publishing.publication set availability='unpublished',publication_generation=2,unpublished_at=now() where publication_id=?",p.id());
        var result=tree(send(post(path(r)+"/begin-review"),b,200));assertThat(result.get("report").get("status").asText()).isEqualTo("closed");assertThat(result.get("targetAvailable").asBoolean()).isFalse();
        send(post(path(r)+"/begin-review"),b,409);assertThat(count("moderation.moderation_decision","report_id",r)).isZero();
    }
    @Test void staleGenerationCannotBeRemovedByOldReport()throws Exception {
        var p=publication();var r=report(p);var b=moderator(r);send(post(path(r)+"/begin-review"),b,200);
        jdbc.update("update publishing.publication set publication_generation=2,snapshot_revision=2 where publication_id=?",p.id());decision(b,r,"removePublication",409);
        assertThat(state(r)).isEqualTo("under_review");assertThat(publicState(p.id())).isEqualTo("active");
    }
    @Test void dismissalDoesNotChangePublicationOrAccount()throws Exception {
        var p=publication();var r=report(p);var b=moderator(r);send(post(path(r)+"/begin-review"),b,200);decision(b,r,"none",201);
        assertThat(state(r)).isEqualTo("dismissed");assertThat(publicState(p.id())).isEqualTo("active");assertThat(accountState(p.owner())).isEqualTo("active");
    }
    @Test void removalDeniesPublicReadMediaLikesSearchAndOldDerivedGeneration()throws Exception {
        var p=publication();var r=report(p);var b=moderator(r);send(post(path(r)+"/begin-review"),b,200);
        UUID media=jdbc.queryForObject("""
            insert into publishing.publication_public_media(public_media_id,publication_id,snapshot_revision,publication_generation,public_object_reference,media_kind,media_type,display_name,byte_size,width,height,media_order,state)
            values(uuidv7(),?,1,1,?,'image','image/png','synthetic.png',6,1,1,0,'current') returning public_media_id
            """,UUID.class,p.id(),"public-publication-media/"+"a".repeat(64));
        String mediaPath="/api/public/publications/"+p.id()+"/media/"+media+"/content";
        assertStatus(mvc.perform(get(mediaPath)).andReturn(),200);assertStatus(mvc.perform(get(mediaPath).header("Range","bytes=1-3")).andReturn(),206);
        PublicDiscoveryFixtures.publication(jdbc,"Different","Different content","other",clock.instant().minusSeconds(3600));
        var oldCursor=tree(mvc.perform(get("/api/public/explore").param("limit","1")).andReturn()).get("nextCursor").asText();
        UUID root=jdbc.queryForObject("insert into knowledge.public_derived_representation(publication_id,snapshot_revision,publication_generation,derivation_class,lineage_id,state) values(?,1,1,'text_surrogate',?,'current') returning derived_representation_id",UUID.class,p.id(),"a".repeat(64));
        decision(b,r,"removePublication",201);assertThat(publicState(p.id())).isEqualTo("removed");assertThat(state(r)).isEqualTo("actioned");
        assertStatus(mvc.perform(get("/api/public/publications/"+p.id())).andReturn(),404);
        assertStatus(mvc.perform(get(mediaPath)).andReturn(),404);
        assertStatus(mvc.perform(get("/api/public/explore").param("cursor",oldCursor)).andReturn(),400);
        send(put("/api/public/publications/"+p.id()+"/like"),b,404);
        assertThat(tree(mvc.perform(get("/api/public/search").param("q","synthetic")).andReturn()).get("items").size()).isZero();
        assertThat(jdbc.queryForObject("select active from discovery.publication_projection where publication_id=?",Boolean.class,p.id())).isFalse();
        assertThat(jdbc.queryForObject("select state from knowledge.public_derived_representation where derived_representation_id=?",String.class,root)).isEqualTo("obsolete");
        assertThatThrownBy(()->jdbc.update("update knowledge.public_derived_representation set state='current',updated_at=clock_timestamp() where derived_representation_id=?",root)).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void suspensionRevokesSessionsButDoesNotDeletePrivateDataOrRemoveUnrelatedAggregates()throws Exception {
        var p=publication();var r=report(p);var b=moderator(r);var owner=browser(p.owner());
        UUID unrelated=jdbc.queryForObject("insert into publishing.publication(publication_id,owner_user_id,source_note_id,source_note_version_id,source_revision,public_profile_projection_id,title,markdown,snapshot_revision,publication_generation,availability,reason_code,published_at,updated_at) values(uuidv7(),?,uuidv7(),uuidv7(),1,?,'Other','Other public',1,1,'active','owner_publish',now(),now()) returning publication_id",UUID.class,p.owner(),p.author());
        UUID note=jdbc.queryForObject("insert into notes.note(note_id,owner_user_id,title,markdown,lifecycle_state,revision,ai_enabled,ai_generation,created_at,updated_at) values(uuidv7(),?,'PRIVATE_CANARY','Private body','active',1,false,1,now(),now()) returning note_id",UUID.class,p.owner());
        send(post(path(r)+"/begin-review"),b,200);decision(b,r,"removePublicationAndSuspendResponsibleAccount",201);
        assertThat(accountState(p.owner())).isEqualTo("suspended");send(get("/api/notes"),owner,401);
        assertThat(count("notes.note","note_id",note)).isEqualTo(1);assertThat(publicState(unrelated)).isEqualTo("active");
        assertStatus(mvc.perform(get("/api/public/publications/"+unrelated)).andReturn(),404);
        assertThat(jdbc.queryForObject("select count(*) from identity.spring_session where principal_name=?",Integer.class,p.owner().toString())).isZero();
    }
    @Test void mandatorySuspensionFailureRollsBackEveryLogicalEffect()throws Exception {
        var p=publication();var r=report(p);var b=moderator(r);send(post(path(r)+"/begin-review"),b,200);
        AccountSuspensionApi suspensionTarget=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(suspension);
        org.mockito.Mockito.doAnswer(inv->{inv.callRealMethod();throw ApiFailureException.of(ApiFailureException.Kind.SERVICE_UNAVAILABLE);}).when(suspensionTarget).suspendResponsible(any(),any(),any(),any());
        decision(b,r,"removePublicationAndSuspendResponsibleAccount",503);
        assertThat(state(r)).isEqualTo("under_review");assertThat(publicState(p.id())).isEqualTo("active");assertThat(accountState(p.owner())).isEqualTo("active");
        assertThat(count("moderation.moderation_decision","report_id",r)).isZero();assertThat(jdbc.queryForObject("select active from discovery.publication_projection where publication_id=?",Boolean.class,p.id())).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from publishing.publication_audit_fact where publication_id=? and action_code='remove'",Integer.class,p.id())).isZero();
    }
    @Test void publishingFailureDoesNotCommitDecision()throws Exception {
        var p=publication();var r=report(p);var b=moderator(r);send(post(path(r)+"/begin-review"),b,200);
        PublicationModerationApi publishingTarget=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(publishing);
        org.mockito.Mockito.doThrow(ApiFailureException.of(ApiFailureException.Kind.SERVICE_UNAVAILABLE)).when(publishingTarget).remove(eq(p.id()),anyLong(),any());
        decision(b,r,"removePublication",503);assertThat(state(r)).isEqualTo("under_review");assertThat(count("moderation.moderation_decision","report_id",r)).isZero();
    }
    @Test void concurrentDecisionsHaveExactlyOneSuccessfulMaterialOutcome()throws Exception {
        var p=publication();var r=report(p);var a=moderator(r);var b=moderator(r);send(post(path(r)+"/begin-review"),a,200);
        var ready=new CountDownLatch(2);var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var one=pool.submit(()->racingDecision(a,r,"removePublication",ready,release));var two=pool.submit(()->racingDecision(b,r,"none",ready,release));
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();release.countDown();assertThat(List.of(one.get(20,TimeUnit.SECONDS),two.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(201,409);
        }finally{release.countDown();}
        assertThat(count("moderation.moderation_decision","report_id",r)).isEqualTo(1);
    }
    @Test void concurrentBeginReviewRecordsOneTransition()throws Exception {
        var r=report(publication());var a=moderator(r);var b=moderator(r);var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var one=pool.submit(()->{await(start);return send(post(path(r)+"/begin-review"),a,200);});var two=pool.submit(()->{await(start);return send(post(path(r)+"/begin-review"),b,200);});start.countDown();one.get(20,TimeUnit.SECONDS);two.get(20,TimeUnit.SECONDS);
        }finally{start.countDown();}
        assertThat(count("moderation.moderation_audit_fact","report_id",r)).isEqualTo(1);
    }
    @Test void unpublishWinningAuthorLockPreventsRacingReport()throws Exception {
        var p=publication();var b=browser(account());var held=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var deny=pool.submit(()->new TransactionTemplate(manager).executeWithoutResult(t->{jdbc.queryForObject("select user_id from identity.account where user_id=? for update",UUID.class,p.owner());held.countDown();await(release);jdbc.update("update publishing.publication set availability='unpublished',publication_generation=2,unpublished_at=now() where publication_id=?",p.id());}));
            assertThat(held.await(10,TimeUnit.SECONDS)).isTrue();var intake=pool.submit(()->send(post(reportPath(p.id())).contentType("application/json").content(intake()),b,404));release.countDown();deny.get(20,TimeUnit.SECONDS);intake.get(20,TimeUnit.SECONDS);
        }finally{release.countDown();}
        assertThat(count("moderation.report","publication_id",p.id())).isZero();
    }
    @Test void deletionWinningAccountLockPreventsSuspensionDecision()throws Exception {
        var p=publication();var r=report(p);var b=moderator(r);send(post(path(r)+"/begin-review"),b,200);var held=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var deletion=pool.submit(()->new TransactionTemplate(manager).executeWithoutResult(t->{jdbc.queryForObject("select user_id from identity.account where user_id=? for update",UUID.class,p.owner());held.countDown();await(release);jdbc.update("update identity.account set account_state='logically_deleted' where user_id=?",p.owner());jdbc.update("update publishing.publication set availability='unpublished',publication_generation=2,unpublished_at=now() where publication_id=?",p.id());}));
            assertThat(held.await(10,TimeUnit.SECONDS)).isTrue();var action=pool.submit(()->decision(b,r,"removePublicationAndSuspendResponsibleAccount",409));release.countDown();deletion.get(20,TimeUnit.SECONDS);action.get(20,TimeUnit.SECONDS);
        }finally{release.countDown();}
        assertThat(accountState(p.owner())).isEqualTo("logically_deleted");assertThat(state(r)).isEqualTo("under_review");assertThat(count("moderation.moderation_decision","report_id",r)).isZero();
    }
    @Test void committedRevocationWinningActorLockDeniesBeginReview()throws Exception {
        var r=report(publication());var b=moderator(r);var held=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var revoke=pool.submit(()->new TransactionTemplate(manager).executeWithoutResult(t->{jdbc.queryForObject("select user_id from identity.account where user_id=? for update",UUID.class,b.user);jdbc.update("update identity.privilege_assignment set revoked_at=clock_timestamp() where user_id=?",b.user);held.countDown();await(release);}));
            assertThat(held.await(10,TimeUnit.SECONDS)).isTrue();var begin=pool.submit(()->send(post(path(r)+"/begin-review"),b,403));release.countDown();revoke.get(20,TimeUnit.SECONDS);begin.get(20,TimeUnit.SECONDS);
        }finally{release.countDown();}
        assertThat(state(r)).isEqualTo("open");
    }
    @Test void ownerGenerationUpdateWinningAccountLockRejectsStaleEnforcement()throws Exception {
        var p=publication();var r=report(p);var b=moderator(r);send(post(path(r)+"/begin-review"),b,200);
        var held=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var update=pool.submit(()->new TransactionTemplate(manager).executeWithoutResult(t->{
                jdbc.queryForObject("select user_id from identity.account where user_id=? for update",UUID.class,p.owner());
                jdbc.update("update publishing.publication set publication_generation=2,snapshot_revision=2 where publication_id=?",p.id());held.countDown();await(release);
            }));
            assertThat(held.await(10,TimeUnit.SECONDS)).isTrue();var action=pool.submit(()->decision(b,r,"removePublication",409));
            release.countDown();update.get(20,TimeUnit.SECONDS);action.get(20,TimeUnit.SECONDS);
        }finally{release.countDown();}
        assertThat(publicState(p.id())).isEqualTo("active");assertThat(state(r)).isEqualTo("under_review");
        assertThat(count("moderation.moderation_decision","report_id",r)).isZero();
    }
    @Test void actualOwnerUnpublishAndModerationSerializeWithoutRestoringPublicState()throws Exception {
        var p=publication();var r=report(p);var mod=moderator(r);var owner=browser(p.owner());send(post(path(r)+"/begin-review"),mod,200);
        String validator=etags.encode(new org.notesknowledge.websupport.PublicationCoreVersion(p.id(),1,1));var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var one=pool.submit(()->{await(start);return mvc.perform(post("/api/me/publications/"+p.id()+"/unpublish").cookie(owner.cookie).header("X-CSRF-TOKEN",owner.csrf).header("If-Match",validator)).andReturn().getResponse().getStatus();});
            var two=pool.submit(()->{await(start);return mvc.perform(post(path(r)+"/decisions").cookie(mod.cookie).header("X-CSRF-TOKEN",mod.csrf).contentType("application/json").content(decisionBody("removePublication"))).andReturn().getResponse().getStatus();});
            start.countDown();int unpublish=one.get(20,TimeUnit.SECONDS),remove=two.get(20,TimeUnit.SECONDS);
            assertThat(unpublish==200&&remove==409||unpublish==412&&remove==201).isTrue();
        }finally{start.countDown();}
        assertThat(publicState(p.id())).isIn("unpublished","removed");assertStatus(mvc.perform(get("/api/public/publications/"+p.id())).andReturn(),404);
    }
    @Test void noSelfProvisioningOrPrivateSourceAuthorityAndRateFailuresAreSafe()throws Exception {
        var p=publication();var r=report(p);var b=moderator(r);UUID note=jdbc.queryForObject("insert into notes.note(note_id,owner_user_id,title,markdown,lifecycle_state,revision,ai_enabled,ai_generation,created_at,updated_at) values(uuidv7(),?,'PRIVATE_CANARY','private','active',1,false,1,now(),now()) returning note_id",UUID.class,p.owner());
        send(get("/api/notes/"+note),b,404);send(post("/api/moderation/privileges").contentType("application/json").content("{}"),b,403);
        org.mockito.Mockito.when(rates.evaluate(any())).thenReturn(new RateLimitPort.ControlUnavailable());send(get(path(r)),b,503);send(post(reportPath(p.id())).contentType("application/json").content(intake()),b,503);
        org.mockito.Mockito.when(rates.evaluate(any())).thenReturn(new RateLimitPort.Throttled(7));var limited=send(get(path(r)),b,429);assertThat(limited.getResponse().getHeader("Retry-After")).isEqualTo("7");
    }
    @Test void terminalRequiresReviewAndAllowlistedConsequenceReason()throws Exception {
        var r=report(publication());var b=moderator(r);decision(b,r,"none",409);
        send(post(path(r)+"/decisions").contentType("application/json").content("{\"consequence\":\"grantAdmin\",\"reasonCode\":\"confirmedPolicyViolation\"}"),b,400);
        send(post(path(r)+"/decisions").contentType("application/json").content("{\"consequence\":\"none\",\"reasonCode\":\"confirmedPolicyViolation\"}"),b,422);
        assertStatus(mvc.perform(post(path(r)+"/begin-review").cookie(b.cookie)).andReturn(),403);
    }
    private PublicDiscoveryFixtures.PublicRow publication(){return PublicDiscoveryFixtures.publication(jdbc,"Public synthetic","Copied public content","synthetic",clock.instant().minusSeconds(3600));}
    private UUID account(){return PublicDiscoveryFixtures.account(jdbc);}
    private UUID report(PublicDiscoveryFixtures.PublicRow p)throws Exception {return UUID.fromString(tree(send(post(reportPath(p.id())).contentType("application/json").content(intake()),browser(account()),201)).get("id").asText());}
    private Browser moderator(UUID report)throws Exception {var b=browser(account());grant(b.user,"moderation.review",report);grant(b.user,"moderation.enforce",report);return b;}
    private void grant(UUID user,String capability,UUID report){UUID operator=account();jdbc.update("insert into identity.privilege_assignment(privilege_assignment_id,user_id,capability_code,scope_kind,scope_id,assigned_by_user_id,granted_at) values(uuidv7(),?,?,'public_report',?,?,clock_timestamp())",user,capability,report,operator);}
    private Browser browser(UUID user)throws Exception{return browser(user,clock.instant().minusSeconds(1),true);}
    private Browser browser(UUID user,Instant recent,boolean full)throws Exception {
        Session s=sessions.createSession();ModerationBrowserFixture.authenticate(s,user,recent,full);save(s);
        var cookie=new Cookie("SESSION",Base64.getEncoder().encodeToString(s.getId().getBytes(StandardCharsets.UTF_8)));var r=mvc.perform(get("/api/auth/csrf").cookie(cookie)).andReturn();assertStatus(r,200);return new Browser(user,cookie,tree(r).get("csrfToken").asText());
    }
    @SuppressWarnings({"rawtypes","unchecked"}) private void save(Session s){((SessionRepository)sessions).save(s);}
    private MvcResult send(MockHttpServletRequestBuilder request,Browser b,int expected)throws Exception {var result=mvc.perform(request.cookie(b.cookie).header("X-CSRF-TOKEN",b.csrf)).andReturn();assertStatus(result,expected);return result;}
    private void assertStatus(MvcResult result,int expected){assertThat(result.getResponse().getStatus()).as("HTTP status").isEqualTo(expected);if(expected>=400)assertThat(result.getResponse().getContentType()).startsWith("application/problem+json");}
    private JsonNode tree(MvcResult r)throws Exception{return json.readTree(r.getResponse().getContentAsString());}
    private MvcResult decision(Browser b,UUID r,String c,int expected)throws Exception{return send(post(path(r)+"/decisions").contentType("application/json").content(decisionBody(c)),b,expected);}
    private int racingDecision(Browser b,UUID r,String c,CountDownLatch ready,CountDownLatch start)throws Exception {ready.countDown();await(start);return mvc.perform(post(path(r)+"/decisions").cookie(b.cookie).header("X-CSRF-TOKEN",b.csrf).contentType("application/json").content(decisionBody(c))).andReturn().getResponse().getStatus();}
    private static String decisionBody(String c){return "{\"consequence\":\""+c+"\",\"reasonCode\":\""+(c.equals("none")?"noPolicyViolation":"confirmedPolicyViolation")+"\"}";}
    private static String intake(){return "{\"category\":\"harmfulContent\",\"description\":\"Synthetic bounded context\"}";}
    private static String reportPath(UUID id){return "/api/public/publications/"+id+"/reports";}
    private static String path(UUID id){return "/api/moderation/reports/"+id;}
    private String state(UUID r){return jdbc.queryForObject("select state from moderation.report where report_id=?",String.class,r);}
    private String publicState(UUID p){return jdbc.queryForObject("select availability from publishing.publication where publication_id=?",String.class,p);}
    private String accountState(UUID id){return jdbc.queryForObject("select account_state from identity.account where user_id=?",String.class,id);}
    private int count(String table,String column,UUID id){return jdbc.queryForObject("select count(*) from "+table+" where "+column+"=?",Integer.class,id);}
    private static void await(CountDownLatch latch){try{if(!latch.await(15,TimeUnit.SECONDS))throw new AssertionError("Synthetic barrier timed out");}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError(interrupted);}}
    record Browser(UUID user,Cookie cookie,String csrf){@Override public String toString(){return "SyntheticBrowser[REDACTED]";}}
}
