package org.notesknowledge.publishing;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import jakarta.servlet.http.Cookie;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.notesknowledge.discovery.PublicProjectionApi;
import org.notesknowledge.identity.IdentitySessionPrincipal;
import org.notesknowledge.identity.SyntheticRecentProof;
import org.notesknowledge.identity.spi.AccountDeletionPublishingConsequence;
import org.notesknowledge.notes.PublicationAttachmentFixtures;
import org.notesknowledge.notes.PublishableSourceApi;
import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.session.*;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.*;

@Tag("DATABASE") @Tag("API") @Tag("SECURITY")
@Testcontainers @SpringBootTest @AutoConfigureMockMvc
@Import({PublishingIntegrationTest.Storage.class,PublicationAttachmentFixtures.class})
class PublishingIntegrationTest {
    @Container static final PostgreSQLContainer postgres=new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18-trixie")
        .withDatabaseName("publishing_core").withUsername("publishing_migrator").withPassword("synthetic-publishing-migrator-password");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",postgres::getJdbcUrl);r.add("spring.datasource.username",postgres::getUsername);r.add("spring.datasource.password",postgres::getPassword);
        r.add("identity.rate.key-base64",()->"AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=");
        r.add("publishing.preview.key-base64",()->"AwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwM=");
    }
    @Autowired MockMvc mvc;@Autowired JdbcTemplate jdbc;@Autowired ObjectMapper json;
    @Autowired SessionRepository<? extends Session> sessions;@Autowired Clock clock;@Autowired MemoryStore store;
    @Autowired PublicationAttachmentFixtures.SourceObjects sourceObjects;@Autowired PlatformTransactionManager manager;
    @Autowired PublicationAttachmentFixtures.RetentionFixture retention;
    @Autowired PublicationService service;@Autowired PublishableSourceApi notes;@Autowired AccountDeletionPublishingConsequence deletion;
    @Autowired PublicMediaService mediaDelivery;
    @Autowired org.notesknowledge.PublicExposureCoordinator exposure;
    @MockitoBean org.notesknowledge.security.RateLimitPort rates;
    @MockitoSpyBean PublicProjectionApi discovery;
    @BeforeEach void reset(){org.mockito.Mockito.when(rates.evaluate(any())).thenReturn(new org.notesknowledge.security.RateLimitPort.Allowed());
        store.bytes.clear();store.created.clear();store.failWrite=false;store.failDelete=false;store.onWrite=()->{};store.onRead=()->{};sourceObjects.bytes.clear();sourceObjects.opens=0;}

    @Test void timedOutDeliveryLeaseDoesNotLeakSessionSettingsOrConsumeReaderCapacity()throws Exception {
        UUID owner=jdbc.queryForObject("select uuidv7()",UUID.class);
        String expected=jdbc.queryForObject("show lock_timeout",String.class);
        var acquired=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var worker=Executors.newSingleThreadExecutor()) {
            var denied=worker.submit(()->new TransactionTemplate(manager).executeWithoutResult(t->{
                exposure.denyBoundary(owner);acquired.countDown();await(release);
            }));
            try {
                assertThat(acquired.await(10,TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(()->exposure.readLease(owner)).isInstanceOfSatisfying(ApiFailureException.class,
                    failure->assertThat(failure.kind()).isEqualTo(ApiFailureException.Kind.SERVICE_UNAVAILABLE));
            }finally{release.countDown();}
            denied.get(15,TimeUnit.SECONDS);
        }
        var pool=jdbc.getDataSource().unwrap(com.zaxxer.hikari.HikariDataSource.class);
        var connections=new ArrayList<java.sql.Connection>();
        try {
            for(int i=0;i<pool.getMaximumPoolSize();i++)connections.add(pool.getConnection());
            for(var connection:connections)try(var statement=connection.createStatement();var row=statement.executeQuery("show lock_timeout")) {
                assertThat(row.next()).isTrue();assertThat(row.getString(1)).isEqualTo(expected);
            }
        }finally{for(var connection:connections)connection.close();}
        var leases=new ArrayList<org.notesknowledge.PublicExposureCoordinator.Lease>();
        try{for(int i=0;i<4;i++)leases.add(exposure.readLease(owner));}
        finally{for(var lease:leases)lease.close();}
    }

    @Test void snapshotIsPublicOnlyAndPrivateSaveDoesNotChangePublicContentOrOwnerEtag()throws Exception {
        var b=ready();UUID note=note(b);var p=publish(b,note,List.of());String path=ownerPath(p);String publicPath=publicPath(p);
        String etag=p.getResponse().getHeader("ETag");String ownerBefore=body(mvc.perform(get(path).cookie(b.cookie)).andExpect(status().isOk()).andReturn());
        assertThat(json.readTree(ownerBefore).get("publicUrl").asText()).isEqualTo("/p/"+id(p));
        var publicBefore=mvc.perform(get(publicPath)).andExpect(status().isOk()).andReturn();assertThat(body(publicBefore)).contains("Synthetic note","Public source")
            .doesNotContain(note.toString(),b.user.toString(),"source_note","sourceRevision","generation","checkpoint","canonical_email");
        save(b,note,"Later private text");var publicAfter=mvc.perform(get(publicPath)).andExpect(status().isOk()).andReturn();
        assertThat(body(publicAfter)).contains("Public source").doesNotContain("Later private text");
        var owner=mvc.perform(get(path).cookie(b.cookie)).andExpect(status().isOk()).andReturn();assertThat(body(owner)).isEqualTo(ownerBefore);
        assertThat(owner.getResponse().getHeader("ETag")).isEqualTo(etag);
        var status=mvc.perform(get(path+"/source-status").cookie(b.cookie)).andExpect(status().isOk()).andReturn();
        var sourceStatus=json.readTree(body(status));
        assertThat(sourceStatus.propertyNames()).containsExactlyInAnyOrder("sourceExists","sourceUsable","driftedSincePublication","updatePublicCopyReady");
        assertThat(sourceStatus.get("sourceExists").asBoolean()).isTrue();assertThat(sourceStatus.get("sourceUsable").asBoolean()).isTrue();
        assertThat(sourceStatus.get("driftedSincePublication").asBoolean()).isTrue();assertThat(sourceStatus.get("updatePublicCopyReady").asBoolean()).isTrue();
        assertThat(status.getResponse().getHeader("ETag")).isNull();
        assertThat(jdbc.queryForObject("select count(*) from notes.note_version_hold where holder_id=?",Integer.class,id(p))).isEqualTo(1);
    }
    @Test void previewIsTransientRequiresProfileAndStrongNoteValidator()throws Exception {
        var b=browser(account());UUID note=note(b);
        preview(b,note,List.of(),null).andExpect(status().isPreconditionRequired());
        preview(b,note,List.of(),noteTag(b,note)).andExpect(status().isConflict());profile(b);activate(b);
        preview(b,note,List.of(),noteTag(b,note)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from publishing.publication where owner_user_id=?",Integer.class,b.user)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from notes.note_version_hold where owner_user_id=?",Integer.class,b.user)).isZero();
    }
    @Test void staleTamperedWrongOwnerSelectionAndChangedProfileFingerprintFailClosed()throws Exception {
        var b=ready();UUID note=note(b);String tag=noteTag(b,note),fingerprint=fingerprint(preview(b,note,List.of(),tag).andReturn());
        create(b,note,tag,List.of(),fingerprint+"x").andExpect(status().isPreconditionFailed());
        var other=ready();create(other,note,tag,List.of(),fingerprint).andExpect(status().isNotFound());
        profileName(b,"Changed public author");activate(b);create(b,note,tag,List.of(),fingerprint).andExpect(status().isPreconditionFailed());
        fingerprint=fingerprint(preview(b,note,List.of(),tag).andReturn());save(b,note,"Changed source");
        create(b,note,noteTag(b,note),List.of(),fingerprint).andExpect(status().isPreconditionFailed());
    }
    @Test void updateReplacesChildrenAtomicallyAndStaleEtagCannotOverwriteNewSnapshot()throws Exception {
        var b=ready();UUID note=note(b),attachment=attachment(b,note,"image");var p=publish(b,note,List.of(attachment));
        String oldUrl=mediaUrl(p),oldTag=p.getResponse().getHeader("ETag");save(b,note,"New public source");
        String fingerprint=fingerprint(preview(b,note,List.of(),noteTag(b,note)).andReturn());
        var updated=replace(b,p,"",oldTag,List.of(),fingerprint).andExpect(status().isOk()).andReturn();
        assertThat(id(updated)).isEqualTo(id(p));assertThat(updated.getResponse().getHeader("ETag")).isNotEqualTo(oldTag);
        mvc.perform(get(oldUrl)).andExpect(status().isNotFound());assertThat(store.bytes).isEmpty();
        assertThat(body(mvc.perform(get(publicPath(p))).andExpect(status().isOk()).andReturn())).contains("New public source");
        replace(b,p,"",oldTag,List.of(),fingerprint).andExpect(status().isPreconditionFailed());
        assertThat(jdbc.queryForObject("select count(*) from notes.note_version_hold where holder_id=?",Integer.class,id(p))).isEqualTo(1);
        assertThat(jdbc.queryForObject("select snapshot_revision from publishing.publication where publication_id=?",Long.class,id(p))).isEqualTo(2);
    }
    @ParameterizedTest @ValueSource(strings={"image","audio","video","pdf"})
    void copiedMediaHasTrustedHeadersRangeAndSurvivesPrivateDeletion(String kind)throws Exception {
        var b=ready();UUID note=note(b),attachment=attachment(b,note,kind);var p=publish(b,note,List.of(attachment));String url=mediaUrl(p);
        int reads=sourceObjects.opens;var response=mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(response.getContentAsByteArray()).isEqualTo(new byte[]{1,2,3,4,5,6});assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");assertThat(response.getHeader("Accept-Ranges")).isEqualTo("bytes");
        var range=mvc.perform(get(url).header("Range","bytes=1-3")).andExpect(status().isPartialContent()).andReturn().getResponse();
        assertThat(range.getContentAsByteArray()).containsExactly(2,3,4);assertThat(range.getHeader("Content-Range")).isEqualTo("bytes 1-3/6");
        mvc.perform(get(url).header("Range","bytes=100-200")).andExpect(status().isRequestedRangeNotSatisfiable());
        mvc.perform(get(url).header("Range","bytes=0-1,3-4")).andExpect(status().isBadRequest());
        var core=mvc.perform(get("/api/notes/"+note+"/attachments/"+attachment).cookie(b.cookie)).andExpect(status().isOk()).andReturn();
        mvc.perform(delete("/api/notes/"+note+"/attachments/"+attachment).cookie(b.cookie).header("X-CSRF-TOKEN",b.csrf).header("If-Match",core.getResponse().getHeader("ETag"))).andExpect(status().isNoContent());
        assertThat(sourceObjects.bytes).isEmpty();mvc.perform(get(url)).andExpect(status().isOk());assertThat(sourceObjects.opens).isEqualTo(reads);
        assertThat(body(mvc.perform(get(publicPath(p))).andReturn())).doesNotContain(note.toString(),attachment.toString(),"private-attachment/","public-publication-media/");
    }
    @Test void wrongPairUnpublishAndRepublishDoNotResurrectOldMedia()throws Exception {
        var b=ready();UUID note=note(b),attachment=attachment(b,note,"pdf");var p=publish(b,note,List.of(attachment));String old=mediaUrl(p);
        mvc.perform(get(old.replace(id(p).toString(),UUID.randomUUID().toString()))).andExpect(status().isNotFound());
        var inactive=mvc.perform(post(ownerPath(p)+"/unpublish").cookie(b.cookie).header("X-CSRF-TOKEN",b.csrf).header("If-Match",p.getResponse().getHeader("ETag"))).andExpect(status().isOk()).andReturn();
        mvc.perform(get(publicPath(p))).andExpect(status().isNotFound());mvc.perform(get(old)).andExpect(status().isNotFound());
        var author=mvc.perform(get("/api/public/profiles/"+handle(b)+"/publications")).andExpect(status().isOk()).andReturn();assertThat(json.readTree(body(author)).get("items")).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from notes.note_version_hold where holder_id=?",Integer.class,id(p))).isZero();
        String fp=fingerprint(preview(b,note,List.of(attachment),noteTag(b,note)).andReturn());
        var republished=replace(b,p,"/republish",inactive.getResponse().getHeader("ETag"),List.of(attachment),fp).andExpect(status().isOk()).andReturn();
        assertThat(mediaUrl(republished)).isNotEqualTo(old);mvc.perform(get(old)).andExpect(status().isNotFound());mvc.perform(get(mediaUrl(republished))).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from notes.note_version_hold where holder_id=?",Integer.class,id(p))).isEqualTo(1);
    }
    @Test void sourceTrashRequiresConfirmationAndDeniesPublicStateInSameTransaction()throws Exception {
        var b=ready();UUID note=note(b);var p=publish(b,note,List.of());String tag=noteTag(b,note);
        var without=mvc.perform(post("/api/notes/"+note+"/trash").cookie(b.cookie).header("X-CSRF-TOKEN",b.csrf).header("If-Match",tag)
            .contentType("application/json").content("{}")).andExpect(status().isConflict()).andReturn();assertThat(body(without)).contains("publication_consequence_required");
        mvc.perform(post("/api/notes/"+note+"/trash").cookie(b.cookie).header("X-CSRF-TOKEN",b.csrf).header("If-Match",tag)
            .contentType("application/json").content("{\"confirmPublicationUnpublish\":true}")).andExpect(status().isOk());
        mvc.perform(get(publicPath(p))).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select active from discovery.publication_projection where publication_id=?",Boolean.class,id(p))).isFalse();
    }
    @Test void concurrentCreatesAreOneStableIdentityAndConcurrentUpdatesHaveOneWinner()throws Exception {
        var b=ready();UUID note=note(b);String tag=noteTag(b,note),fp=fingerprint(preview(b,note,List.of(),tag).andReturn());
        try(var workers=Executors.newFixedThreadPool(2)) {
            var barrier=new CyclicBarrier(2);
            var a=workers.submit(()->{barrier.await();return create(b,note,tag,List.of(),fp).andReturn().getResponse().getStatus();});
            var c=workers.submit(()->{barrier.await();return create(b,note,tag,List.of(),fp).andReturn().getResponse().getStatus();});
            assertThat(List.of(a.get(20,TimeUnit.SECONDS),c.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(201,409);
        }
        assertThat(jdbc.queryForObject("select count(*) from publishing.publication where owner_user_id=?",Integer.class,b.user)).isEqualTo(1);
        var p=mvc.perform(get("/api/me/publications").cookie(b.cookie)).andReturn();
        UUID publication=UUID.fromString(json.readTree(body(p)).get("items").get(0).get("id").asText());
        var core=mvc.perform(get("/api/me/publications/"+publication).cookie(b.cookie)).andReturn();
        String publicationTag=core.getResponse().getHeader("ETag");
        try(var workers=Executors.newFixedThreadPool(2)) {
            var barrier=new CyclicBarrier(2);
            var a=workers.submit(()->{barrier.await();return replace(b,core,"",publicationTag,List.of(),fp).andReturn().getResponse().getStatus();});
            var c=workers.submit(()->{barrier.await();return replace(b,core,"",publicationTag,List.of(),fp).andReturn().getResponse().getStatus();});
            assertThat(List.of(a.get(20,TimeUnit.SECONDS),c.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,412);
        }
        assertThat(jdbc.queryForObject("select snapshot_revision from publishing.publication where publication_id=?",Long.class,publication)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from notes.note_version_hold where holder_id=?",Integer.class,publication)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from publishing.publication_audit_fact where publication_id=?",Integer.class,publication)).isEqualTo(2);
    }

    @Test void currentHoldSurvivesRealNotesCompactionAndReusesExactSavedCheckpoint()throws Exception {
        var b=ready();UUID note=note(b);var p=publish(b,note,List.of());
        UUID held=jdbc.queryForObject("select source_note_version_id from publishing.publication where publication_id=?",UUID.class,id(p));
        // Saving different content asks Notes to retain the same old revision; its existing checkpoint must be reused.
        for(int i=0;i<4;i++) {
            save(b,note,"Changed synthetic body "+i+" "+"x".repeat(300));
        }
        mvc.perform(get("/api/notes/"+note+"/versions/"+held).cookie(b.cookie)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from notes.note_version_hold where note_version_id=?",Integer.class,held)).isEqualTo(1);
        // The protected current hold is exercised by the owning Notes retention algorithm, not a fake table.
        jdbc.update("insert into notes.note_version(note_version_id,note_id,owner_user_id,title,markdown,source_revision,checkpoint_kind,created_at) select uuidv7(),?,?,'Synthetic history','Synthetic',s,'policy',now() from generate_series(10,19) s",note,b.user);
        new TransactionTemplate(manager).executeWithoutResult(t->assertThat(retention.compact(b.user,note,held)).isEqualTo(8));
        assertThat(jdbc.queryForObject("select count(*) from notes.note_version where note_version_id=?",Integer.class,held)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from notes.note_version where note_id=?",Integer.class,note)).isEqualTo(3);
    }

    @Test void paginationIsOpaqueBoundedOwnerScopedAndAuthorPageIsOnlyActivePublicState()throws Exception {
        var b=ready();var first=publish(b,note(b),List.of());publish(b,note(b),List.of());var other=ready();publish(other,note(other),List.of());
        var page=mvc.perform(get("/api/me/publications?limit=1").cookie(b.cookie)).andExpect(status().isOk()).andReturn();
        var tree=json.readTree(body(page));assertThat(tree.get("items")).hasSize(1);String cursor=tree.get("nextCursor").asText();
        assertThat(body(page)).doesNotContain("markdown","media","ownerUserId",b.user.toString());
        mvc.perform(get("/api/me/publications").param("limit","1").param("cursor",cursor).cookie(b.cookie)).andExpect(status().isOk());
        mvc.perform(get("/api/me/publications").param("cursor",cursor).cookie(other.cookie)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/me/publications?limit=101").cookie(b.cookie)).andExpect(status().isBadRequest());
        var publicPage=mvc.perform(get("/api/public/profiles/"+handle(b)+"/publications?limit=1")).andExpect(status().isOk()).andReturn();
        var publicTree=json.readTree(body(publicPage));assertThat(publicTree.get("items")).hasSize(1);
        assertThat(body(publicPage)).doesNotContain(b.user.toString(),"sourceNote","checkpoint","markdown","ownerUserId");
        mvc.perform(get("/api/public/profiles/"+handle(other)+"/publications").param("cursor",publicTree.get("nextCursor").asText())).andExpect(status().isBadRequest());
        mvc.perform(post(ownerPath(first)+"/unpublish").cookie(b.cookie).header("X-CSRF-TOKEN",b.csrf).header("If-Match",first.getResponse().getHeader("ETag"))).andExpect(status().isOk());
        assertThat(json.readTree(body(mvc.perform(get("/api/public/profiles/"+handle(b)+"/publications")).andReturn())).get("items")).hasSize(1);
    }

    @Test void confirmedPermanentSourceDeletionDeniesPublicationBeforeLogicalDeleteSuccess()throws Exception {
        var b=ready();UUID note=note(b);var p=publish(b,note,List.of());String tag=noteTag(b,note);
        mvc.perform(delete("/api/notes/"+note).cookie(b.cookie).header("X-CSRF-TOKEN",b.csrf).header("If-Match",tag).contentType("application/json")
            .content("{\"confirmPermanentDelete\":true}")).andExpect(status().isConflict());
        mvc.perform(post("/api/notes/"+note+"/trash").cookie(b.cookie).header("X-CSRF-TOKEN",b.csrf).header("If-Match",tag).contentType("application/json")
            .content("{\"confirmPublicationUnpublish\":true}")).andExpect(status().isOk());
        mvc.perform(delete("/api/notes/"+note).cookie(b.cookie).header("X-CSRF-TOKEN",b.csrf).header("If-Match",noteTag(b,note)).contentType("application/json")
            .content("{\"confirmPermanentDelete\":true}")).andExpect(status().isNoContent());
        mvc.perform(get(publicPath(p))).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select active from discovery.publication_projection where publication_id=?",Boolean.class,id(p))).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from notes.note_version_hold where holder_id=?",Integer.class,id(p))).isZero();
    }

    @Test void denialCommittedDuringStorageReadPreventsAnyPublicByteDelivery()throws Exception {
        var b=ready();UUID note=note(b);var p=publish(b,note,List.of(attachment(b,note,"pdf")));String url=mediaUrl(p);
        var readEntered=new CountDownLatch(1);var continueRead=new CountDownLatch(1);
        store.onRead=()->{readEntered.countDown();await(continueRead);};
        try(var workers=Executors.newSingleThreadExecutor()) {
            var pending=workers.submit(()->mvc.perform(get(url)).andReturn());
            assertThat(readEntered.await(10,TimeUnit.SECONDS)).isTrue();
            mvc.perform(post(ownerPath(p)+"/unpublish").cookie(b.cookie).header("X-CSRF-TOKEN",b.csrf).header("If-Match",p.getResponse().getHeader("ETag"))).andExpect(status().isOk());
            continueRead.countDown();var denied=pending.get(15,TimeUnit.SECONDS).getResponse();
            assertThat(denied.getStatus()).isEqualTo(404);assertThat(denied.getContentAsByteArray()).isNotEqualTo(new byte[]{1,2,3,4,5,6});
            assertThat(denied.getContentType()).contains("application/problem+json");
        }finally{continueRead.countDown();}
    }

    @Test void attachmentCurrentnessAndCleanupFailureNeverChangeApprovedEligibility()throws Exception {
        var b=ready();UUID note=note(b),attachment=attachment(b,note,"pdf");var p=publish(b,note,List.of(attachment));String old=mediaUrl(p);
        String fp=fingerprint(preview(b,note,List.of(attachment),noteTag(b,note)).andReturn());
        store.onWrite=()->jdbc.update("update notes.attachment set revision=revision+1,processing_generation=processing_generation+1,updated_at=now() where attachment_id=?",attachment);
        replace(b,p,"",p.getResponse().getHeader("ETag"),List.of(attachment),fp).andExpect(status().isPreconditionFailed());
        mvc.perform(get(old)).andExpect(status().isOk());store.onWrite=()->{};
        store.failDelete=true;
        mvc.perform(post(ownerPath(p)+"/unpublish").cookie(b.cookie).header("X-CSRF-TOKEN",b.csrf).header("If-Match",p.getResponse().getHeader("ETag"))).andExpect(status().isOk());
        mvc.perform(get(old)).andExpect(status().isNotFound());store.failDelete=false;
        store.created.replaceAll((k,v)->clock.instant().minusSeconds(90000));service.reconcile(null);assertThat(store.bytes).isEmpty();
    }
    @Test void revocationAfterFirstChunkStopsRemainingBytesWithoutAppendingProblemJson()throws Exception {
        var b=ready();UUID note=note(b),attachment=attachment(b,note,"pdf",20000);var p=publish(b,note,List.of(attachment));
        var reached=new CountDownLatch(1);var proceed=new CountDownLatch(1);var reads=new java.util.concurrent.atomic.AtomicInteger();
        store.onRead=()->{if(reads.incrementAndGet()==2){reached.countDown();await(proceed);}};
        var response=new org.springframework.mock.web.MockHttpServletResponse();UUID publication=id(p);
        UUID publicMedia=UUID.fromString(json.readTree(body(p)).get("media").get(0).get("id").asText());
        try(var workers=Executors.newSingleThreadExecutor()) {
            var pending=workers.submit(()->{
                assertThatThrownBy(()->mediaDelivery.stream(publication,publicMedia,new org.springframework.mock.web.MockHttpServletRequest(),response))
                    .isInstanceOf(org.notesknowledge.websupport.ResponseStreamInterruptedException.class);
            });
            assertThat(reached.await(10,TimeUnit.SECONDS)).isTrue();assertThat(response.getContentAsByteArray()).hasSize(16*1024);
            mvc.perform(post(ownerPath(p)+"/unpublish").cookie(b.cookie).header("X-CSRF-TOKEN",b.csrf).header("If-Match",p.getResponse().getHeader("ETag"))).andExpect(status().isOk());
            proceed.countDown();pending.get(15,TimeUnit.SECONDS);assertThat(response.getContentAsByteArray()).hasSize(16*1024);
            assertThat(response.getContentAsString()).doesNotContain("problem","resource_not_found");mvc.perform(get(mediaUrl(p))).andExpect(status().isNotFound());
        }finally{proceed.countDown();}
    }
    private static void await(CountDownLatch latch){try{if(!latch.await(15,TimeUnit.SECONDS))throw new AssertionError("Synthetic coordination timed out");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
    @Test void copyOrCommitFailurePreservesOldSnapshotAndNewOrphansAreCleaned()throws Exception {
        var b=ready();UUID note=note(b);var p=publish(b,note,List.of());UUID attachment=attachment(b,note,"pdf");
        String fp=fingerprint(preview(b,note,List.of(attachment),noteTag(b,note)).andReturn());String tag=p.getResponse().getHeader("ETag");
        store.failWrite=true;replace(b,p,"",tag,List.of(attachment),fp).andExpect(status().isServiceUnavailable());store.failWrite=false;
        assertThat(store.bytes).isEmpty();
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("synthetic projection failure")).when(org.springframework.test.util.AopTestUtils.<PublicProjectionApi>getUltimateTargetObject(discovery)).advance(any());
        replace(b,p,"",tag,List.of(attachment),fp).andExpect(status().isServiceUnavailable());assertThat(store.bytes).isEmpty();
        assertThat(mvc.perform(get(ownerPath(p)).cookie(b.cookie)).andReturn().getResponse().getHeader("ETag")).isEqualTo(tag);
        assertThat(jdbc.queryForObject("select count(*) from notes.note_version_hold where holder_id=?",Integer.class,id(p))).isEqualTo(1);
    }
    @Test void staleDiscoveryGenerationCannotReactivateAndApproximateViewFailureDoesNotFailRead()throws Exception {
        var b=ready();UUID note=note(b);var p=publish(b,note,List.of());
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("synthetic views unavailable")).when(discovery).recordApproximateView(any(),org.mockito.ArgumentMatchers.anyLong());
        mvc.perform(get(publicPath(p))).andExpect(status().isOk());
        mvc.perform(post(ownerPath(p)+"/unpublish").cookie(b.cookie).header("X-CSRF-TOKEN",b.csrf).header("If-Match",p.getResponse().getHeader("ETag"))).andExpect(status().isOk());
        UUID author=jdbc.queryForObject("select public_profile_projection_id from publishing.publication where publication_id=?",UUID.class,id(p));
        UUID publication=id(p);
        assertThatThrownBy(()->new TransactionTemplate(manager).executeWithoutResult(t->discovery.advance(new PublicProjectionApi.Snapshot(publication,author,1,"Old","Old",List.of(),clock.instant(),clock.instant()))))
            .isInstanceOf(ApiFailureException.class);
        assertThat(jdbc.queryForObject("select active from discovery.publication_projection where publication_id=?",Boolean.class,id(p))).isFalse();
    }
    @Test void accountConsequenceDeniesEveryPublicationAndReleasesHolds()throws Exception {
        var b=ready();var p=publish(b,note(b),List.of());new TransactionTemplate(manager).executeWithoutResult(t->deletion.makeIneligible(b.user));
        mvc.perform(get(publicPath(p))).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select count(*) from notes.note_version_hold where owner_user_id=?",Integer.class,b.user)).isZero();
    }
    @Test void ownerIsolationCsrfMfaAndRecentProofRemainMandatory()throws Exception {
        var b=ready();UUID note=note(b);var p=publish(b,note,List.of());var other=ready();
        mvc.perform(get(ownerPath(p)).cookie(other.cookie)).andExpect(status().isNotFound());mvc.perform(get(ownerPath(p))).andExpect(status().isUnauthorized());
        mvc.perform(post(ownerPath(p)+"/unpublish").cookie(b.cookie).header("If-Match",p.getResponse().getHeader("ETag"))).andExpect(status().isForbidden());
        var old=browser(b.user,false);preview(old,note,List.of(),noteTag(old,note)).andExpect(status().isForbidden());
    }
    private Browser ready()throws Exception{var b=browser(account());profile(b);activate(b);return b;}
    private void profile(Browser b)throws Exception{profileName(b,"Public Reader");}
    private void profileName(Browser b,String name)throws Exception{mvc.perform(put("/api/me/profile").cookie(b.cookie).header("X-CSRF-TOKEN",b.csrf).contentType("application/json")
        .content(json.writeValueAsString(Map.of("displayName",name,"biography","Synthetic biography","handle",handle(b))))).andExpect(status().isOk());}
    private void activate(Browser b)throws Exception{mvc.perform(put("/api/me/public-profile").cookie(b.cookie).header("X-CSRF-TOKEN",b.csrf)).andExpect(status().isOk());}
    private String handle(Browser b){return "writer_"+b.user.toString().replace("-","").substring(0,12);}
    private UUID note(Browser b)throws Exception{return UUID.fromString(json.readTree(body(mvc.perform(post("/api/notes").cookie(b.cookie).header("X-CSRF-TOKEN",b.csrf).contentType("application/json")
        .content("{\"title\":\"Synthetic note\",\"markdown\":\"Public source\"}")).andExpect(status().isCreated()).andReturn())).get("id").asText());}
    private String noteTag(Browser b,UUID note)throws Exception{return mvc.perform(get("/api/notes/"+note).cookie(b.cookie)).andExpect(status().isOk()).andReturn().getResponse().getHeader("ETag");}
    private void save(Browser b,UUID note,String text)throws Exception{mvc.perform(put("/api/notes/"+note).cookie(b.cookie).header("X-CSRF-TOKEN",b.csrf).header("If-Match",noteTag(b,note))
        .contentType("application/json").content(json.writeValueAsString(Map.of("title","Synthetic note","markdown",text)))).andExpect(status().isOk());}
    private ResultActions preview(Browser b,UUID note,List<UUID> selection,String tag)throws Exception{var r=post("/api/notes/"+note+"/publication-preview").cookie(b.cookie).header("X-CSRF-TOKEN",b.csrf)
        .contentType("application/json").content(json.writeValueAsString(Map.of("selectedAttachmentIds",selection)));if(tag!=null)r.header("If-Match",tag);return mvc.perform(r);}
    private String fingerprint(MvcResult p)throws Exception{return json.readTree(body(p)).get("previewFingerprint").asText();}
    private ResultActions create(Browser b,UUID note,String tag,List<UUID> selection,String fp)throws Exception{return mvc.perform(post("/api/notes/"+note+"/publication").cookie(b.cookie)
        .header("X-CSRF-TOKEN",b.csrf).header("If-Match",tag).contentType("application/json").content(json.writeValueAsString(Map.of("selectedAttachmentIds",selection,"previewFingerprint",fp))));}
    private MvcResult publish(Browser b,UUID note,List<UUID> selection)throws Exception{String tag=noteTag(b,note);return create(b,note,tag,selection,fingerprint(preview(b,note,selection,tag).andExpect(status().isOk()).andReturn())).andExpect(status().isCreated()).andReturn();}
    private ResultActions replace(Browser b,MvcResult p,String suffix,String tag,List<UUID> selection,String fp)throws Exception{
        var r=suffix.isEmpty()?put(ownerPath(p)):post(ownerPath(p)+suffix);return mvc.perform(r.cookie(b.cookie).header("X-CSRF-TOKEN",b.csrf).header("If-Match",tag)
            .contentType("application/json").content(json.writeValueAsString(Map.of("selectedAttachmentIds",selection,"previewFingerprint",fp))));}
    private UUID id(MvcResult r)throws Exception{return UUID.fromString(json.readTree(body(r)).get("id").asText());}
    private String ownerPath(MvcResult r)throws Exception{return "/api/me/publications/"+id(r);}
    private String publicPath(MvcResult r)throws Exception{return "/api/public/publications/"+id(r);}
    private String mediaUrl(MvcResult r)throws Exception{return json.readTree(body(r)).get("media").get(0).get("contentUrl").asText();}
    private String body(MvcResult r)throws Exception{return r.getResponse().getContentAsString();}
    private UUID account(){var id=jdbc.queryForObject("select uuidv7()",UUID.class);String email="publishing-"+id+"@example.test";
        jdbc.update("insert into identity.account(user_id,canonical_email,display_email,email_verified_at,account_state,created_at,updated_at) values(?,?,?,now(),'active',now(),now())",id,email,email);return id;}
    private Browser browser(UUID user)throws Exception{return browser(user,true);}
    private Browser browser(UUID user,boolean recent)throws Exception{Session s=sessions.createSession();var c=SecurityContextHolder.createEmptyContext();
        c.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(new IdentitySessionPrincipal(user),null,List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        s.setAttribute("SPRING_SECURITY_CONTEXT",c);if(recent)SyntheticRecentProof.password(s,user,clock.instant());saveSession(s);
        var cookie=new Cookie("SESSION",Base64.getEncoder().encodeToString(s.getId().getBytes(StandardCharsets.UTF_8)));
        var result=mvc.perform(get("/api/auth/csrf").cookie(cookie)).andExpect(status().isOk()).andReturn();return new Browser(cookie,json.readTree(body(result)).get("csrfToken").asText(),user);}
    @SuppressWarnings({"rawtypes","unchecked"}) private void saveSession(Session s){((SessionRepository)sessions).save(s);}
    private UUID attachment(Browser b,UUID note,String kind){return attachment(b,note,kind,6);}
    private UUID attachment(Browser b,UUID note,String kind,int size){var id=jdbc.queryForObject("select uuidv7()",UUID.class);String ref="private-attachment/"+id.toString().replace("-","").repeat(2);
        String type=switch(kind){case "image"->"image/png";case "audio"->"audio/wav";case "video"->"video/mp4";default->"application/pdf";};
        jdbc.update("""
            insert into notes.attachment(attachment_id,note_id,owner_user_id,object_reference,display_filename,media_kind,media_type,size_bytes,width,height,duration_seconds,page_count,
                storage_state,validation_state,cleanup_state,revision,processing_generation,created_at,updated_at)
            values(?,?,?,?,?,?,?,?,?,?,?,?,'stored','accepted','retained',1,1,now(),now())
            """,id,note,b.user,ref,"synthetic."+kind,kind,type,size,kind.equals("image")||kind.equals("video")?2:null,
            kind.equals("image")||kind.equals("video")?2:null,kind.equals("audio")||kind.equals("video")?1.0:null,kind.equals("pdf")?1:null);
        sourceObjects.bytes.put(ref,size==6?new byte[]{1,2,3,4,5,6}:new byte[size]);return id;}
    record Browser(Cookie cookie,String csrf,UUID user){ }
    static class MemoryStore implements PublicMediaObjectStore {
        final ConcurrentHashMap<String,byte[]> bytes=new ConcurrentHashMap<>();final ConcurrentHashMap<String,Instant> created=new ConcurrentHashMap<>();
        volatile boolean failWrite,failDelete;volatile Runnable onWrite=()->{},onRead=()->{};
        private void outside(){assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();}
        public void write(String ref,InputStream source,long size){outside();try{assertThat(bytes.putIfAbsent(ref,source.readNBytes(Math.toIntExact(size)))).isNull();}catch(IOException e){throw new AssertionError(e);}
            created.put(ref,Instant.now());onWrite.run();if(failWrite)throw new IllegalStateException("synthetic copy failure");}
        public InputStream openRange(String ref,long offset,long length){outside();var b=bytes.get(ref);if(b==null)return null;
            return new ByteArrayInputStream(b,(int)offset,(int)length){@Override public synchronized int read(byte[] buffer,int start,int count){onRead.run();return super.read(buffer,start,count);}};}
        public void delete(String ref){outside();if(failDelete)throw new IllegalStateException("synthetic delete failure");bytes.remove(ref);created.remove(ref);}
        public List<StoredObject> inventoryBefore(Instant cutoff,String after,int limit){outside();return created.entrySet().stream().filter(e->e.getValue().isBefore(cutoff)&&(after==null||e.getKey().compareTo(after)>0))
            .sorted(Map.Entry.comparingByKey()).limit(limit).map(e->new StoredObject(e.getKey(),e.getValue())).toList();}
    }
    @TestConfiguration(proxyBeanMethods=false) static class Storage{@Bean @Primary MemoryStore publicMediaStore(){return new MemoryStore();}}
}
