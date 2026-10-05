package org.notesknowledge.notes;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import jakarta.servlet.http.Cookie;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.notesknowledge.identity.AccountEligibilityApi;
import org.notesknowledge.identity.IdentitySessionPrincipal;
import org.notesknowledge.security.RateLimitPort;
import org.notesknowledge.websupport.AttachmentCoreVersion;
import org.notesknowledge.websupport.StrongCoreEtagCodec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.dao.DataAccessResourceFailureException;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@Tag("DATABASE") @Tag("API") @Tag("SECURITY")
@Testcontainers @SpringBootTest @AutoConfigureMockMvc @Import(AttachmentDeleteIntegrationTest.Storage.class)
@ExtendWith(OutputCaptureExtension.class)
class AttachmentDeleteIntegrationTest {
    @Container static final PostgreSQLContainer postgres=new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18-trixie")
            .withDatabaseName("attachment_delete").withUsername("delete_migrator").withPassword("synthetic-delete-password");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",postgres::getJdbcUrl);r.add("spring.datasource.username",postgres::getUsername);r.add("spring.datasource.password",postgres::getPassword);
        r.add("identity.rate.key-base64",()->"AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=");
    }
    @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc; @Autowired ObjectMapper json;
    @Autowired SessionRepository<? extends Session> sessions; @Autowired StrongCoreEtagCodec etags;
    @Autowired TestStore store; @Autowired AttachmentDeleteService deletes; @Autowired AttachmentUploadService uploads;
    @Autowired AttachmentDeleteTransactions deleteTransactions;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean RateLimitPort rates;
    @MockitoSpyBean AttachmentRepository attachments; @MockitoSpyBean AttachmentMediaValidator validators;
    @MockitoSpyBean AccountEligibilityApi eligibility;

    @BeforeEach void reset() {
        jdbc.update("delete from notes.attachment"); store.clear();store.attempts.clear();store.failures.clear();store.deleteHook=ref->{};store.readHook=()->{};
        clearInvocations(validators);when(rates.evaluate(any())).thenReturn(new RateLimitPort.Allowed());
    }
    @AfterEach void noParsingOrTransactionalObjectIo(){verifyNoInteractions(validators);assertThat(store.sawTransaction).isFalse();}

    @ParameterizedTest @ValueSource(strings={"active","archived","trashed"})
    void successfulDeletionCommitsBeforeStorageAndFinalizesWithoutChangingNote(String lifecycle) throws Exception {
        Fixture f=fixture();lifecycle(f.note(),lifecycle);long noteRevision=noteRevision(f.note());var before=row(f.id());
        store.deleteHook=ref->{var pending=row(f.id());assertThat(pending.get("cleanup_state")).isEqualTo("pending");assertThat(pending.get("revision")).isEqualTo(2L);assertThat(pending.get("processing_generation")).isEqualTo(2L);};
        var response=remove(f,browser(f.owner(),"ROLE_USER"),etag(f)).getResponse();
        assertThat(response.getStatus()).isEqualTo(204);assertThat(response.getContentAsByteArray()).isEmpty();assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");assertThat(response.getHeader("ETag")).isNull();
        var after=row(f.id());assertThat(after.get("cleanup_state")).isEqualTo("deleted");assertThat(after.get("revision")).isEqualTo(3L);assertThat(after.get("processing_generation")).isEqualTo(2L);
        assertThat((Timestamp)after.get("cleaned_at")).isAfterOrEqualTo((Timestamp)after.get("removed_at"));
        unchangedMetadata(before,after);assertThat(noteRevision(f.note())).isEqualTo(noteRevision);assertThat(store.bytes).doesNotContainKey(f.reference());assertThat(store.attempts).containsExactly(f.reference());assertThat(store.opens).isZero();
    }

    @Test void providerFailureStill204ImmediatelyDeniesAllReadsAndKeepsCustody(CapturedOutput output) throws Exception {
        Fixture f=fixture();var before=row(f.id());Browser b=browser(f.owner(),"ROLE_USER");store.failures.add(f.reference());
        assertThat(remove(f,b,etag(f)).getResponse().getStatus()).isEqualTo(204);
        var after=row(f.id());assertThat(after.get("cleanup_state")).isEqualTo("pending");assertThat(after.get("removed_at")).isNotNull();assertThat(after.get("cleaned_at")).isNull();
        assertThat(after.get("revision")).isEqualTo(2L);assertThat(after.get("processing_generation")).isEqualTo(2L);unchangedMetadata(before,after);
        assertThat(attachments.referenced(f.reference())).isTrue();assertThat(store.bytes).containsKey(f.reference());
        assertThat(json.readTree(mvc.perform(get(collection(f)).cookie(b.cookie())).andReturn().getResponse().getContentAsString()).get("items")).isEmpty();
        assertThat(mvc.perform(get(path(f)).cookie(b.cookie())).andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mvc.perform(get(path(f)+"/content").cookie(b.cookie())).andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(remove(f,b,etag(f)).getResponse().getStatus()).isEqualTo(404);assertThat(store.attempts).hasSize(1);assertThat(store.opens).isZero();
        assertThat(output.getAll()).contains("attachment_cleanup_deferred").doesNotContain(f.reference(),f.id().toString(),f.owner().toString(),"SYNTHETIC_PRIVATE_DIAGNOSTIC","synthetic-private.pdf");
    }

    @Test void metadataFailureAfterCommitIsDeferredAndReconciliationRepairsIt(CapturedOutput output) throws Exception {
        Fixture f=fixture();doThrow(new DataAccessResourceFailureException("SYNTHETIC_PRIVATE_DIAGNOSTIC")).doCallRealMethod().when(attachments).finalizeCleanup(any(),any());
        assertThat(remove(f,browser(f.owner(),"ROLE_USER"),etag(f)).getResponse().getStatus()).isEqualTo(204);
        assertThat(row(f.id()).get("cleanup_state")).isEqualTo("pending");assertThat(store.bytes).doesNotContainKey(f.reference());
        deletes.reconcilePending();assertThat(row(f.id()).get("cleanup_state")).isEqualTo("deleted");assertThat(row(f.id()).get("revision")).isEqualTo(3L);assertThat(row(f.id()).get("processing_generation")).isEqualTo(2L);
        deletes.reconcilePending();assertThat(store.attempts).hasSize(2);
        assertThat(output.getAll()).contains("attachment_cleanup_metadata_deferred").doesNotContain("SYNTHETIC_PRIVATE_DIAGNOSTIC",f.reference());
    }

    @Test void unconfiguredRuntimeDeleteDefersButLogicalRemovalStillSucceeds() throws Exception {
        Fixture f=fixture();store.deleteHook=ref->new AttachmentConfiguration().unavailableAttachmentObjectStore().delete(ref);
        assertThat(remove(f,browser(f.owner(),"ROLE_USER"),etag(f)).getResponse().getStatus()).isEqualTo(204);
        assertThat(row(f.id()).get("cleanup_state")).isEqualTo("pending");assertThat(store.bytes).containsKey(f.reference());
    }

    @ParameterizedTest @CsvSource({"missing,428","stale,412","malformed,400","weak,400","wildcard,400","list,400","other,412"})
    void preconditionsRejectWithoutMutationOrStorage(String kind,int status) throws Exception {
        Fixture f=fixture();var original=row(f.id());String tag=switch(kind){case "missing"->null;case "stale"->"\"stale\"";case "malformed"->"bad";case "weak"->"W/"+etag(f);case "wildcard"->"*";case "list"->etag(f)+","+etag(f);default->etag(fixture());};
        var response=remove(f,browser(f.owner(),"ROLE_USER"),tag).getResponse();assertThat(response.getStatus()).isEqualTo(status);assertThat(row(f.id())).isEqualTo(original);assertThat(store.attempts).isEmpty();
        assertThat(response.getContentAsString()).doesNotContain(f.reference(),"revision");
    }

    @ParameterizedTest @ValueSource(strings={"metadata","content"})
    void validatorFromEitherReadSurfaceAuthorizesDelete(String surface) throws Exception {
        Fixture f=fixture();Browser b=browser(f.owner(),"ROLE_USER");String url=path(f)+(surface.equals("content")?"/content":"");
        String tag=mvc.perform(get(url).cookie(b.cookie())).andReturn().getResponse().getHeader("ETag");assertThat(tag).isEqualTo(etag(f));
        assertThat(remove(f,b,tag).getResponse().getStatus()).isEqualTo(204);
    }

    @ParameterizedTest @ValueSource(strings={"pending","failed","validationPending","rejected","quarantined"})
    void everyRetainedStateIsDeletable(String state) throws Exception {
        Fixture f=fixture();String storage=Set.of("pending","failed").contains(state)?state:"stored";String validation=Set.of("pending","failed","validationPending").contains(state)?"pending":state;
        jdbc.update("update notes.attachment set storage_state=?,validation_state=?,revision=2 where attachment_id=?",storage,validation,f.id());
        assertThat(remove(f,browser(f.owner(),"ROLE_USER"),etags.encode(new AttachmentCoreVersion(f.id(),2))).getResponse().getStatus()).isEqualTo(204);
        assertThat(row(f.id()).get("storage_state")).isEqualTo(storage);assertThat(row(f.id()).get("validation_state")).isEqualTo(validation);
    }

    @ParameterizedTest @ValueSource(strings={"wrongOwner","missingNote","deletedNote","wrongNote","missingAttachment","pending","deleted"})
    void unavailableResourcesDenyBeforeMissingOrMalformedPrecondition(String denial) throws Exception {
        Fixture f=fixture();Browser b=browser(denial.equals("wrongOwner")?account():f.owner(),"ROLE_USER");UUID note=f.note(),id=f.id();
        switch(denial){case "missingNote"->note=uuid();case "deletedNote"->lifecycle(note,"logically_deleted");case "wrongNote"->note=note(f.owner());case "missingAttachment"->id=uuid();case "pending","deleted"->pending(f,denial.equals("deleted"));default->{} }
        Fixture denied=new Fixture(f.owner(),note,id,f.reference());
        for(String header:Arrays.asList(null,"malformed")){assertThat(remove(denied,b,header).getResponse().getStatus()).isEqualTo(404);}
        assertThat(store.attempts).isEmpty();
    }

    @ParameterizedTest @ValueSource(strings={"anonymous","ROLE_MFA_PENDING","moderation.review","suspended","noCsrf","revokedSession"})
    void securityBoundariesDenyWithoutStorage(String denial) throws Exception {
        Fixture f=fixture();Browser b=browser(f.owner(),denial.startsWith("ROLE_")||denial.equals("moderation.review")?denial:"ROLE_USER");
        if(denial.equals("suspended"))jdbc.update("update identity.account set account_state='suspended' where user_id=?",f.owner());
        if(denial.equals("revokedSession")){String sid=new String(Base64.getDecoder().decode(b.cookie().getValue()),StandardCharsets.UTF_8);sessions.deleteById(sid);}
        var request=delete(path(f)).header("If-Match",etag(f));if(!denial.equals("anonymous"))request.cookie(b.cookie());
        // A valid anonymous CSRF proof separates authentication failure from CSRF rejection.
        if(denial.equals("anonymous")){Browser anon=csrf(null);request.cookie(anon.cookie()).header("X-CSRF-TOKEN",anon.csrf());}
        else request.header("X-CSRF-TOKEN",b.csrf());
        if(denial.equals("noCsrf"))request=delete(path(f)).cookie(b.cookie()).header("If-Match",etag(f));
        int status=mvc.perform(request).andReturn().getResponse().getStatus();
        if(denial.equals("anonymous"))assertThat(status).isEqualTo(401);
        else if(Set.of("noCsrf","ROLE_MFA_PENDING","moderation.review").contains(denial))assertThat(status).isEqualTo(403);
        else assertThat(status).isIn(401,403);
        assertThat(store.attempts).isEmpty();assertThat(row(f.id()).get("cleanup_state")).isEqualTo("retained");
    }

    @ParameterizedTest @ValueSource(strings={"authority","lookup","mutation"})
    void precommitDatabaseFailureRollsBackAndNeverDeletes(String stage) throws Exception {
        Fixture f=fixture();var original=row(f.id());var fail=new DataAccessResourceFailureException("SYNTHETIC_PRIVATE_DIAGNOSTIC");
        if(stage.equals("authority"))doThrow(fail).when(org.springframework.test.util.AopTestUtils.<AccountEligibilityApi>getUltimateTargetObject(eligibility)).requireCurrentOwner(any(),any());
        else if(stage.equals("lookup"))doThrow(fail).when(attachments).lockRetained(any(),any(),any());
        else doAnswer(invocation->{invocation.callRealMethod();throw fail;}).when(attachments).remove(any(),any(),any(),anyLong(),any());
        var response=remove(f,browser(f.owner(),"ROLE_USER"),etag(f)).getResponse();assertThat(response.getStatus()).isEqualTo(503);assertThat(response.getContentAsString()).doesNotContain("SYNTHETIC_PRIVATE_DIAGNOSTIC",f.reference());
        assertThat(row(f.id())).isEqualTo(original);assertThat(store.attempts).isEmpty();
    }

    @Test void boundedReconciliationOrdersOnlyPendingAndContinuesAfterFailures() {
        Fixture retained=fixture(),deleted=fixture();pending(deleted,true);
        List<Fixture> candidates=new ArrayList<>();for(int i=0;i<102;i++){Fixture f=fixture();pending(f,false);candidates.add(f);}
        var expected=attachments.pendingCleanup();assertThat(expected).hasSize(100);
        store.failures.add(expected.getFirst().reference());store.bytes.remove(expected.get(1).reference());
        doThrow(new DataAccessResourceFailureException("SYNTHETIC_PRIVATE_DIAGNOSTIC")).doCallRealMethod().when(attachments).finalizeCleanup(any(),any());
        deletes.reconcilePending();assertThat(store.attempts).containsExactlyElementsOf(expected.stream().map(AttachmentCleanupTarget::reference).toList());
        assertThat(row(expected.getFirst().id()).get("cleanup_state")).isEqualTo("pending");assertThat(row(expected.get(1).id()).get("cleanup_state")).isEqualTo("pending");
        assertThat(row(expected.get(2).id()).get("cleanup_state")).isEqualTo("deleted");assertThat(row(retained.id()).get("cleanup_state")).isEqualTo("retained");assertThat(store.attempts).doesNotContain(retained.reference(),deleted.reference());
        assertThat(jdbc.queryForObject("select count(*) from notes.attachment where cleanup_state='pending'",Integer.class)).isEqualTo(4);
        store.failures.clear();deletes.reconcilePending();assertThat(jdbc.queryForObject("select count(*) from notes.attachment where cleanup_state='pending'",Integer.class)).isZero();assertThat(store.opens).isZero();
        assertThat(expected.stream().map(AttachmentCleanupTarget::id)).isSorted(); // Identical removed_at values use UUID tie order.
        candidates.forEach(f->assertThat(noteRevision(f.note())).isEqualTo(1L));
    }

    @Test void invalidInternalTargetIsRedactedAndSkippedWithoutBlockingLaterCandidate() {
        Fixture first=fixture(),second=fixture();pending(second,false);
        doReturn(List.of(new AttachmentCleanupTarget(first.id(),"invalid-private-locator",2),new AttachmentCleanupTarget(second.id(),second.reference(),2))).when(attachments).pendingCleanup();
        deletes.reconcilePending();assertThat(store.attempts).containsExactly(second.reference());assertThat(row(first.id()).get("cleanup_state")).isEqualTo("retained");assertThat(row(second.id()).get("cleanup_state")).isEqualTo("deleted");
        assertThat(new AttachmentCleanupTarget(first.id(),first.reference(),1).toString()).doesNotContain(first.reference(),first.id().toString());
    }

    @Test void reconciliationUsesRemovedTimeBeforeUuidAndAlreadyFinalizedIsBenign() {
        Fixture olderId=fixture(),newerId=fixture();pending(newerId,false);
        jdbc.update("update notes.attachment set cleanup_state='pending',removed_at=?,revision=2,processing_generation=2 where attachment_id=?",Timestamp.from(Instant.parse("2026-01-03T00:00:00Z")),olderId.id());
        assertThat(attachments.pendingCleanup().stream().map(AttachmentCleanupTarget::id)).containsExactly(newerId.id(),olderId.id());
        store.deleteHook=ref->{if(ref.equals(newerId.reference()))deleteTransactions.finalizeCleanup(new AttachmentCleanupTarget(newerId.id(),ref,2));};
        deletes.reconcilePending();
        assertThat(store.attempts).containsExactly(newerId.reference(),olderId.reference());
        assertThat(row(newerId.id()).get("revision")).isEqualTo(3L);assertThat(row(olderId.id()).get("revision")).isEqualTo(3L);
        assertThat(row(newerId.id()).get("processing_generation")).isEqualTo(2L);
    }

    @Test void twoConcurrentCommandsProduceOne204AndOne404() throws Exception {
        Fixture f=fixture();Browser first=browser(f.owner(),"ROLE_USER"),second=browser(f.owner(),"ROLE_USER");var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            var a=pool.submit(()->{assertThat(start.await(10,TimeUnit.SECONDS)).isTrue();return remove(f,first,etag(f)).getResponse().getStatus();});
            var b=pool.submit(()->{assertThat(start.await(10,TimeUnit.SECONDS)).isTrue();return remove(f,second,etag(f)).getResponse().getStatus();});start.countDown();
            assertThat(List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(204,404);
        }
        assertThat(row(f.id()).get("revision")).isEqualTo(3L);assertThat(row(f.id()).get("processing_generation")).isEqualTo(2L);assertThat(store.attempts).hasSize(1);
    }

    @Test void alreadyOpenedStreamDoesNotHoldLocksOrPreventFutureDenial() throws Exception {
        Fixture f=fixture();Browser b=browser(f.owner(),"ROLE_USER");CountDownLatch opened=new CountDownLatch(1),release=new CountDownLatch(1);
        store.readHook=()->{opened.countDown();try{if(!release.await(15,TimeUnit.SECONDS))throw new AssertionError("Synthetic read not released");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}};
        try(var pool=Executors.newFixedThreadPool(2)){
            var read=pool.submit(()->mvc.perform(get(path(f)+"/content").cookie(b.cookie())).andReturn());assertThat(opened.await(10,TimeUnit.SECONDS)).isTrue();
            try{var deletion=pool.submit(()->remove(f,browser(f.owner(),"ROLE_USER"),etag(f)));assertThat(deletion.get(10,TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(204);
                assertThat(mvc.perform(get(path(f)+"/content").cookie(b.cookie())).andReturn().getResponse().getStatus()).isEqualTo(404);
            }finally{release.countDown();}
            assertThat(read.get(10,TimeUnit.SECONDS).getResponse().getContentAsByteArray()).isEqualTo(new byte[]{1,2,3});
        }
        assertThat(store.closes).isEqualTo(1);assertThat(row(f.id()).get("cleanup_state")).isEqualTo("deleted");
    }

    @Test void cleanupNeverMovesTimesBackwardsOrAdvancesProcessingTwice() throws Exception {
        Fixture f=fixture();jdbc.update("update notes.attachment set updated_at=?,revision=2 where attachment_id=?",Timestamp.from(Instant.parse("2030-01-01T00:00:00Z")),f.id());
        assertThat(remove(f,browser(f.owner(),"ROLE_USER"),etags.encode(new AttachmentCoreVersion(f.id(),2))).getResponse().getStatus()).isEqualTo(204);
        assertThat(row(f.id()).get("revision")).isEqualTo(4L);assertThat(row(f.id()).get("processing_generation")).isEqualTo(2L);
        assertThat((Timestamp)row(f.id()).get("updated_at")).isEqualTo(Timestamp.from(Instant.parse("2030-01-01T00:00:00Z")));
    }

    @Test void outerCoordinatorRejectsActiveTransactionBeforeAnyCleanup() {
        Fixture f=fixture();pending(f,false);
        assertThatThrownBy(()->new TransactionTemplate(transactionManager).execute(status->{deletes.reconcilePending();return null;})).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertThat(store.attempts).isEmpty();
    }

    @Test void pendingRowsStillBelongToCustodyAndAreNotUploadOrphans() {
        Fixture f=fixture();pending(f,false);store.created.put(f.reference(),Instant.parse("2000-01-01T00:00:00Z"));
        uploads.reconcile(null);assertThat(store.bytes).containsKey(f.reference());assertThat(store.attempts).isEmpty();
        deletes.reconcilePending();assertThat(store.bytes).doesNotContainKey(f.reference());
    }

    @Test void v012RejectsStateWithoutRevisionAndReopeningAndTimestampRewrites() {
        Fixture f=fixture();assertThatThrownBy(()->jdbc.update("update notes.attachment set cleanup_state='pending',removed_at=now() where attachment_id=?",f.id())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        pending(f,true);
        for(String sql:List.of("cleanup_state='pending',cleaned_at=null,revision=revision+1","removed_at=removed_at+interval '1 second',revision=revision+1","cleaned_at=cleaned_at+interval '1 second',revision=revision+1","processing_generation=processing_generation-1","updated_at=updated_at-interval '1 second',revision=revision+1")){
            assertThatThrownBy(()->jdbc.update("update notes.attachment set "+sql+" where attachment_id=?",f.id())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        }
    }

    @Test void noExpandedAttachmentSurfaceIsReachable() throws Exception {
        Fixture f=fixture();Browser b=browser(f.owner(),"ROLE_USER");
        for(String url:List.of(path(f)+"/content",path(f)+"/arbitrary"))assertThat(mvc.perform(delete(url).cookie(b.cookie()).header("X-CSRF-TOKEN",b.csrf())).andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(mvc.perform(head(path(f)+"/content").cookie(b.cookie())).andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(mvc.perform(get("/api/notes/"+f.note()+"/ai-processing").cookie(b.cookie())).andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(store.attempts).isEmpty();
    }

    private void unchangedMetadata(Map<String,Object> before,Map<String,Object> after){for(String name:List.of("attachment_id","note_id","owner_user_id","object_reference","media_kind","display_filename","media_type","size_bytes","width","height","duration_seconds","page_count","created_at","storage_state","validation_state"))assertThat(after.get(name)).as(name).isEqualTo(before.get(name));}
    private MvcResult remove(Fixture f,Browser b,String tag)throws Exception{var r=delete(path(f)).cookie(b.cookie()).header("X-CSRF-TOKEN",b.csrf());if(tag!=null)r.header("If-Match",tag);return mvc.perform(r).andReturn();}
    private String collection(Fixture f){return "/api/notes/"+f.note()+"/attachments";}private String path(Fixture f){return collection(f)+"/"+f.id();}
    private String etag(Fixture f){return etags.encode(new AttachmentCoreVersion(f.id(),1));}
    private Map<String,Object> row(UUID id){return jdbc.queryForMap("select * from notes.attachment where attachment_id=?",id);}
    private long noteRevision(UUID id){return jdbc.queryForObject("select revision from notes.note where note_id=?",Long.class,id);}
    private UUID uuid(){return jdbc.queryForObject("select uuidv7()",UUID.class);}
    private UUID account(){UUID id=uuid();String email="delete-"+id+"@example.test";jdbc.update("insert into identity.account(user_id,canonical_email,display_email,email_verified_at,account_state,created_at,updated_at) values(?,?,?,now(),'active',now(),now())",id,email,email);return id;}
    private UUID note(UUID owner){UUID id=uuid();jdbc.update("insert into notes.note(note_id,owner_user_id,title,markdown,lifecycle_state,pinned,revision,ai_enabled,ai_generation,created_at,updated_at) values(?,?,'Synthetic','Synthetic','active',false,1,false,1,now(),now())",id,owner);return id;}
    private Fixture fixture(){UUID owner=account(),note=note(owner),id=uuid();String ref="private-attachment/"+id.toString().replace("-","").repeat(2);
        jdbc.update("insert into notes.attachment(attachment_id,note_id,owner_user_id,object_reference,display_filename,media_kind,media_type,size_bytes,page_count,storage_state,validation_state,cleanup_state,revision,processing_generation,created_at,updated_at) values(?,?,?,?,'synthetic-private.pdf','pdf','application/pdf',3,1,'stored','accepted','retained',1,1,?,?)",id,note,owner,ref,Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")),Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")));store.bytes.put(ref,new byte[]{1,2,3});return new Fixture(owner,note,id,ref);}
    private void pending(Fixture f,boolean deleted){jdbc.update("update notes.attachment set cleanup_state=?,removed_at=?,cleaned_at=?,revision=2,processing_generation=2 where attachment_id=?",deleted?"deleted":"pending",Timestamp.from(Instant.parse("2026-01-02T00:00:00Z")),deleted?Timestamp.from(Instant.parse("2026-01-02T00:00:00Z")):null,f.id());}
    private void lifecycle(UUID note,String state){jdbc.update("update notes.note set lifecycle_state=?,revision=revision+1,pre_trash_state=case when ?='trashed' then 'active' else null end,trashed_at=case when ?='trashed' then now() else null end,deleted_at=case when ?='logically_deleted' then now() else null end where note_id=?",state,state,state,state,note);}
    private Browser browser(UUID user,String role)throws Exception{Session s=sessions.createSession();var context=SecurityContextHolder.createEmptyContext();context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(new IdentitySessionPrincipal(user),null,List.of(new SimpleGrantedAuthority(role))));s.setAttribute("SPRING_SECURITY_CONTEXT",context);save(s);return csrf(new Cookie("SESSION",Base64.getEncoder().encodeToString(s.getId().getBytes(StandardCharsets.UTF_8))));}
    private Browser csrf(Cookie cookie)throws Exception{var r=get("/api/auth/csrf");if(cookie!=null)r.cookie(cookie);var response=mvc.perform(r).andReturn().getResponse();Cookie next=response.getCookie("SESSION");return new Browser(next==null?cookie:next,json.readTree(response.getContentAsString()).get("csrfToken").asText());}
    @SuppressWarnings({"rawtypes","unchecked"})private void save(Session s){((SessionRepository)sessions).save(s);}
    private record Fixture(UUID owner,UUID note,UUID id,String reference){} private record Browser(Cookie cookie,String csrf){}
    static class TestStore extends AttachmentUploadIntegrationTest.MemoryStore {
        final List<String> attempts=new CopyOnWriteArrayList<>();final Set<String> failures=ConcurrentHashMap.newKeySet();
        volatile Consumer<String> deleteHook=ref->{};volatile Runnable readHook=()->{};
        @Override public void delete(String reference){assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();attempts.add(reference);deleteHook.accept(reference);if(failures.contains(reference))throw new IllegalStateException("SYNTHETIC_PRIVATE_DIAGNOSTIC");super.delete(reference);}
        @Override public InputStream openRange(String reference,long offset,long length){var stream=super.openRange(reference,offset,length);readHook.run();return stream;}
    }
    @TestConfiguration(proxyBeanMethods=false)static class Storage{@Bean @Primary TestStore deletionTestStore(){return new TestStore();}}
}
