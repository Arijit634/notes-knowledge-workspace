package org.notesknowledge.notes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.Cookie;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.notesknowledge.identity.IdentitySessionPrincipal;
import org.notesknowledge.security.RateLimitPort;
import org.notesknowledge.websupport.ApiFailureException;
import org.notesknowledge.websupport.AttachmentCoreVersion;
import org.notesknowledge.websupport.ResponseStreamInterruptedException;
import org.notesknowledge.websupport.StrongCoreEtagCodec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.ContentDisposition;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpServletRequest;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("DATABASE") @Tag("API") @Tag("SECURITY")
@Testcontainers @SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc @Import(AttachmentUploadIntegrationTest.Storage.class)
@ExtendWith(OutputCaptureExtension.class)
class AttachmentContentIntegrationTest {
    @Container static final PostgreSQLContainer postgres=new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18-trixie")
            .withDatabaseName("attachment_content").withUsername("content_migrator").withPassword("synthetic-content-password");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",postgres::getJdbcUrl);
        registry.add("spring.datasource.username",postgres::getUsername);
        registry.add("spring.datasource.password",postgres::getPassword);
        registry.add("identity.rate.key-base64",()->"AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=");
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired SessionRepository<? extends Session> sessions;
    @Autowired AttachmentUploadIntegrationTest.MemoryStore store;
    @Autowired StreamAttachmentContentService streaming;
    @Autowired NotesRepository notes;
    @Autowired StrongCoreEtagCodec etags;
    @Autowired PlatformTransactionManager transactionManager;
    @LocalServerPort int port;
    @MockitoBean RateLimitPort rates;
    @MockitoSpyBean AttachmentRepository attachments;
    @MockitoSpyBean AttachmentMediaValidator validators;

    @BeforeEach void reset() { store.clear();clearInvocations(validators);org.mockito.Mockito.when(rates.evaluate(any())).thenReturn(new RateLimitPort.Allowed()); }
    @AfterEach void noParsingOrTransactionalStoreIo() { verifyNoInteractions(validators);assertThat(store.sawTransaction).isFalse(); }

    @ParameterizedTest @CsvSource({"image,image/png","image,image/jpeg","audio,audio/wav","video,video/mp4","pdf,application/pdf"})
    void fullRepresentationIsExactTrustedNoStoreAndCannotExposeCustody(String kind,String type) throws Exception {
        Fixture f=fixture(kind,type,media(type),"synthetic file."+kind);
        var original=row(f.id());long noteRevision=noteRevision(f.note());
        var response=getContent(f,null).getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsByteArray()).isEqualTo(f.bytes());
        headers(response,f,f.bytes().length);
        assertThat(response.getHeader("Content-Range")).isNull();
        assertThat(ContentDisposition.parse(response.getHeader("Content-Disposition")).getFilename()).isEqualTo(f.filename());
        assertThat(response.getHeaderNames().stream().map(response::getHeader)).allSatisfy(v->assertThat(v).doesNotContain(f.reference()));
        assertThat(store.opens).isEqualTo(1);assertThat(store.closes).isEqualTo(1);
        assertThat(store.lastOffset).isZero();assertThat(store.lastLength).isEqualTo(f.bytes().length);
        assertThat(store.maxReadRequest).isLessThanOrEqualTo(16*1024);
        assertThat(row(f.id())).isEqualTo(original);assertThat(noteRevision(f.note())).isEqualTo(noteRevision);
    }

    @ParameterizedTest @CsvSource({"bytes=0-0,0,0","bytes=0-9,0,9","bytes=40-59,40,59","bytes=99-99,99,99",
            "bytes=90-,90,99","bytes=-1,99,99","bytes=-10,90,99","bytes=-500,0,99","bytes=90-1000,90,99","bytes=0-99,0,99"})
    void rangeFormsReturnOnlySelectedBytesAndExactHeaders(String range,int start,int end) throws Exception {
        Fixture f=fixture("pdf","application/pdf",payload(100),"synthetic.pdf");
        var response=getContent(f,range).getResponse();
        assertThat(response.getStatus()).isEqualTo(206);
        assertThat(response.getContentAsByteArray()).isEqualTo(Arrays.copyOfRange(f.bytes(),start,end+1));
        headers(response,f,end-start+1);
        assertThat(response.getHeader("Content-Range")).isEqualTo("bytes "+start+"-"+end+"/100");
        assertThat(store.lastOffset).isEqualTo(start);assertThat(store.lastLength).isEqualTo(end-start+1);
        assertThat(store.closes).isEqualTo(1);
    }

    @ParameterizedTest @CsvSource({"image,image/png","audio,audio/wav","video,video/mp4","pdf,application/pdf"})
    void rangeAccessHasNoModalityOrAiGate(String kind,String type) throws Exception {
        Fixture f=fixture(kind,type,payload(100),"synthetic");
        jdbc.update("update notes.note set ai_enabled=true,ai_generation=ai_generation+1,revision=revision+1 where note_id=?",f.note());
        var response=getContent(f,"bytes=1-3").getResponse();
        assertThat(response.getStatus()).isEqualTo(206);assertThat(response.getContentAsByteArray()).isEqualTo(Arrays.copyOfRange(f.bytes(),1,4));
        headers(response,f,3);
    }

    @ParameterizedTest @ValueSource(strings={"","items=0-5","bytes=0-1,4-5","bytes=","bytes=-","bytes=-1-2","bytes=abc-def",
            "bytes=+1-2","bytes=0-+1","bytes=9223372036854775808-","bytes=0-9223372036854775808","bytes=0--1",
            "bytes==0-1","bytes=0-1-2"," bytes=0-1","bytes= 0-1","bytes=0 -1","bytes=0-1 ","bytes=0-1\r\n","bytes=０-１"})
    void malformedRangesFailBeforeStorage(String header) throws Exception {
        Fixture f=fixture("pdf","application/pdf",payload(100),"synthetic.pdf");
        var response=getContent(f,header).getResponse();assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("malformed_request").doesNotContain(f.reference());
        assertThat(store.opens).isZero();
    }

    @Test void duplicateAndOversizedHeadersNeverSelectFirstRange() throws Exception {
        Fixture f=fixture("pdf","application/pdf",payload(100),"synthetic.pdf");Cookie cookie=browser(f.owner(),"ROLE_USER");
        mvc.perform(get(path(f)).cookie(cookie).header("Range","bytes=0-1","bytes=4-5")).andExpect(status().isBadRequest());
        mvc.perform(get(path(f)).cookie(cookie).header("Range","bytes=0-"+"0".repeat(256))).andExpect(status().isBadRequest());
        assertThat(store.opens).isZero();
    }

    @ParameterizedTest @ValueSource(strings={"bytes=100-","bytes=101-","bytes=-0","bytes=9-8"})
    void unsatisfiableSelectionsReturnSafe416WithoutOpeningBytes(String header) throws Exception {
        Fixture f=fixture("pdf","application/pdf",payload(100),"synthetic.pdf");
        var response=getContent(f,header).getResponse();assertThat(response.getStatus()).isEqualTo(416);
        assertThat(response.getHeader("Content-Range")).isEqualTo("bytes */100");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(response.getContentAsString()).contains("range_not_satisfiable").doesNotContain(f.reference());
        assertThat(store.opens).isZero();
    }

    @Test void anonymousRestrictedSuspendedAndAllIdorFormsDenyBeforeObjectAccess() throws Exception {
        Fixture f=fixture("pdf","application/pdf",payload(100),"synthetic.pdf");
        mvc.perform(get(path(f))).andExpect(status().isUnauthorized());
        mvc.perform(get(path(f)).cookie(browser(f.owner(),"ROLE_MFA_PENDING"))).andExpect(status().isForbidden());
        mvc.perform(get(path(f)).cookie(browser(f.owner(),"moderation.review"))).andExpect(status().isForbidden());
        Cookie foreign=browser(account(),"ROLE_USER"),owner=browser(f.owner(),"ROLE_USER");
        String missingNote="/api/notes/"+uuid()+"/attachments/"+f.id()+"/content";
        String wrongNote="/api/notes/"+note(f.owner())+"/attachments/"+f.id()+"/content";
        String missingAttachment="/api/notes/"+f.note()+"/attachments/"+uuid()+"/content";
        for(String path:List.of(path(f),missingNote,wrongNote,missingAttachment))
            mvc.perform(get(path).cookie(foreign).header("Range","malformed")).andExpect(status().isNotFound());
        for(String path:List.of(missingNote,wrongNote,missingAttachment))
            mvc.perform(get(path).cookie(owner)).andExpect(status().isNotFound());
        jdbc.update("update identity.account set account_state='suspended' where user_id=?",f.owner());
        assertThat(mvc.perform(get(path(f)).cookie(owner)).andReturn().getResponse().getStatus()).isIn(401,403);
        assertThat(store.opens).isZero();
    }

    @ParameterizedTest @ValueSource(strings={"active","archived","trashed"})
    void privatelyReadableParentLifecyclesKeepAcceptedBytesAvailable(String state) throws Exception {
        Fixture f=fixture("pdf","application/pdf",payload(100),"synthetic.pdf");lifecycle(f.note(),state);
        assertThat(getContent(f,null).getResponse().getStatus()).isEqualTo(200);
    }

    @Test void logicallyDeletedParentDeniesBeforeStorage() throws Exception {
        Fixture f=fixture("pdf","application/pdf",payload(100),"synthetic.pdf");lifecycle(f.note(),"logically_deleted");
        assertThat(getContent(f,null).getResponse().getStatus()).isEqualTo(404);assertThat(store.opens).isZero();
    }

    @ParameterizedTest @ValueSource(strings={"cleanupPending","cleanupDeleted","validationPending","validationRejected","validationQuarantined","storagePending","storageFailed"})
    void byteUnavailableStateIsIndistinguishableFromAbsence(String state) throws Exception {
        Fixture f=fixture("pdf","application/pdf",payload(100),"synthetic.pdf");
        switch(state) {
            case "cleanupPending" -> jdbc.update("update notes.attachment set cleanup_state='pending',removed_at=now(),revision=2 where attachment_id=?",f.id());
            case "cleanupDeleted" -> jdbc.update("update notes.attachment set cleanup_state='deleted',removed_at=now(),cleaned_at=now(),revision=2 where attachment_id=?",f.id());
            case "storagePending","storageFailed" -> jdbc.update("update notes.attachment set storage_state=?,validation_state='pending',revision=2 where attachment_id=?",state.equals("storagePending")?"pending":"failed",f.id());
            default -> jdbc.update("update notes.attachment set validation_state=?,revision=2 where attachment_id=?",state.equals("validationPending")?"pending":state.equals("validationRejected")?"rejected":"quarantined",f.id());
        }
        assertThat(getContent(f,null).getResponse().getStatus()).isEqualTo(404);assertThat(store.opens).isZero();
    }

    @ParameterizedTest @ValueSource(strings={"synthetic.pdf","a file with spaces.pdf","旅行メモ.pdf","quoted\"name.pdf","semi;colon.pdf","100% done.pdf"})
    void frameworkDispositionSafelyRoundTripsStoredDisplayNames(String filename) throws Exception {
        Fixture f=fixture("pdf","application/pdf",payload(100),filename);
        String header=getContent(f,null).getResponse().getHeader("Content-Disposition");
        assertThat(header).doesNotContain("\r","\n");
        var parsed=ContentDisposition.parse(header);assertThat(parsed.getType()).isEqualTo("inline");assertThat(parsed.getFilename()).isEqualTo(filename);
    }

    @Test void persistedConstraintsAndDescriptorChecksDoNotPermitHeaderOrLocatorInjection() throws Exception {
        assertThatThrownBy(()->fixture("pdf","application/pdf",payload(100),"bad\r\nInjected: value"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        Fixture f=fixture("pdf","application/pdf",payload(100),"synthetic.pdf");
        org.mockito.Mockito.doReturn(java.util.Optional.of(new AttachmentContentDescriptor(f.id(),"not-a-generated-reference","application/pdf","safe",100,1)))
                .when(attachments).content(f.owner(),f.note(),f.id());
        assertThat(getContent(f,null).getResponse().getStatus()).isEqualTo(404);assertThat(store.opens).isZero();
        org.mockito.Mockito.doReturn(java.util.Optional.of(new AttachmentContentDescriptor(f.id(),f.reference(),"application/pdf","safe",0,1)))
                .when(attachments).content(f.owner(),f.note(),f.id());
        assertThat(getContent(f,null).getResponse().getStatus()).isEqualTo(404);assertThat(store.opens).isZero();
    }

    @Test void databaseOpenMissingCustodyAndPrecommitReadFailuresAreSanitized503(CapturedOutput output) throws Exception {
        Fixture f=fixture("pdf","application/pdf",payload(100),"private-display-canary.pdf");
        doThrow(new DataAccessResourceFailureException("SYNTHETIC_PRIVATE_DIAGNOSTIC")).when(attachments).content(f.owner(),f.note(),f.id());
        var response=getContent(f,null).getResponse();assertThat(response.getStatus()).isEqualTo(503);assertThat(store.opens).isZero();
        org.mockito.Mockito.doCallRealMethod().when(attachments).content(f.owner(),f.note(),f.id());
        store.failOpen=true;assertThat(getContent(f,null).getResponse().getStatus()).isEqualTo(503);assertThat(store.closes).isZero();
        store.failOpen=false;store.failReadAfter=0;response=getContent(f,null).getResponse();
        assertThat(response.getStatus()).isEqualTo(503);assertThat(response.getHeader("Content-Length")).isNull();
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getContentAsString()).contains("service_unavailable").doesNotContain(f.filename(),f.reference(),"SYNTHETIC_PRIVATE_DIAGNOSTIC");
        assertThat(store.closes).isEqualTo(1);
        store.failReadAfter=-1;store.bytes.remove(f.reference());assertThat(getContent(f,null).getResponse().getStatus()).isEqualTo(503);
        assertThat(output.getAll()).doesNotContain(f.filename(),f.reference(),"SYNTHETIC_PRIVATE_DIAGNOSTIC");
    }

    @Test void unconfiguredRuntimePortFailsClosedAndNeverReturnsEmptyContent() {
        Fixture f=fixture("pdf","application/pdf",payload(100),"synthetic.pdf");
        var unavailable=new StreamAttachmentContentService(notes,attachments,new AttachmentConfiguration().unavailableAttachmentObjectStore(),etags);
        assertThatThrownBy(()->unavailable.stream(f.owner(),f.note(),f.id(),new MockHttpServletRequest(),new MockHttpServletResponse()))
                .isInstanceOfSatisfying(ApiFailureException.class,e->assertThat(e.kind()).isEqualTo(ApiFailureException.Kind.SERVICE_UNAVAILABLE));
    }

    @Test void prematureEofBeforeCommitResetsPartialBufferRatherThanPaddingOrReportingSuccess() throws Exception {
        Fixture f=fixture("pdf","application/pdf",payload(100),"synthetic.pdf");store.shortAfter=10;
        var response=getContent(f,null).getResponse();assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(response.getHeader("Content-Length")).isNull();assertThat(store.closes).isEqualTo(1);
    }

    @Test void serviceNeverReadsPastSelectionEvenIfAdapterOffersAdditionalBytes() throws Exception {
        Fixture f=fixture("pdf","application/pdf",payload(100),"synthetic.pdf");store.ignoreRangeBound=true;
        var response=getContent(f,"bytes=3-4").getResponse();assertThat(response.getContentAsByteArray()).isEqualTo(Arrays.copyOfRange(f.bytes(),3,5));
        assertThat(store.maxReadRequest).isEqualTo(2);assertThat(store.closes).isEqualTo(1);
    }

    @Test void transactionCallerIsRejectedBeforeStorageAndOutputFailuresCloseSource() {
        Fixture f=fixture("pdf","application/pdf",payload(40000),"synthetic.pdf");var before=row(f.id());
        assertThatThrownBy(()->new TransactionTemplate(transactionManager).execute(s->{streaming.stream(f.owner(),f.note(),f.id(),new MockHttpServletRequest(),new MockHttpServletResponse());return null;}))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertThat(store.opens).isZero();
        assertThatThrownBy(()->streaming.stream(f.owner(),f.note(),f.id(),new MockHttpServletRequest(),new FailingOutput(false)))
                .isInstanceOfSatisfying(ApiFailureException.class,e->assertThat(e.kind()).isEqualTo(ApiFailureException.Kind.SERVICE_UNAVAILABLE));
        assertThat(store.closes).isEqualTo(1);
        var response=new FailingOutput(true);
        assertThatThrownBy(()->streaming.stream(f.owner(),f.note(),f.id(),new MockHttpServletRequest(),response)).isInstanceOf(ResponseStreamInterruptedException.class);
        assertThat(response.isCommitted()).isTrue();assertThat(response.getContentAsByteArray()).hasSize(16*1024);
        assertThat(store.closes).isEqualTo(2);assertThat(row(f.id())).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings={"short","readFailure"})
    void committedMediaTruncationIsObservableOnRealConnectionWithoutJsonAppend(String mode,CapturedOutput output) throws Exception {
        Fixture f=fixture("pdf","application/pdf",payload(40000),"private-display-canary.pdf");var before=row(f.id());
        if(mode.equals("short"))store.shortAfter=20000;else store.failReadAfter=20000;
        Cookie cookie=browser(f.owner(),"ROLE_USER");
        try(var client=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build()) {
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path(f))).timeout(Duration.ofSeconds(10))
                    .header("Cookie","SESSION="+cookie.getValue()).header("Range","bytes=0-39999").GET().build();
            var response=client.send(request,HttpResponse.BodyHandlers.ofInputStream());
            assertThat(response.statusCode()).isEqualTo(206);
            assertThat(response.headers().firstValue("Content-Length")).contains("40000");
            var received=new ByteArrayOutputStream();
            try(var input=response.body()) {
                try(var reader=java.util.concurrent.Executors.newSingleThreadExecutor()) {
                    var read=reader.submit(()->{byte[] buffer=new byte[1024];for(int n;(n=input.read(buffer))>=0;)received.write(buffer,0,n);return null;});
                    try {
                        assertThatThrownBy(()->read.get(10,java.util.concurrent.TimeUnit.SECONDS))
                                .isInstanceOf(java.util.concurrent.ExecutionException.class).hasCauseInstanceOf(IOException.class);
                    } finally { input.close();read.cancel(true); }
                }
            }
            assertThat(received.size()).isGreaterThan(0).isLessThan(40000);
            assertThat(received.toByteArray()).isEqualTo(Arrays.copyOf(f.bytes(),received.size()));
        }
        assertThat(store.closes).isEqualTo(1);assertThat(row(f.id())).isEqualTo(before);
        assertThat(output.getAll()).contains("attachment_stream_interrupted").doesNotContain(f.filename(),f.reference(),"SYNTHETIC_PRIVATE_DIAGNOSTIC");
        assertThat(output.getAll()).contains("\"outcome\":\"failure\"");
    }

    @Test void conditionalHeadersAreNotSelectedAndHeadDeleteAiAndWildcardsRemainDenied() throws Exception {
        Fixture f=fixture("pdf","application/pdf",payload(100),"synthetic.pdf");Cookie cookie=browser(f.owner(),"ROLE_USER");
        var response=mvc.perform(get(path(f)).cookie(cookie).header("If-None-Match",etags.encode(new AttachmentCoreVersion(f.id(),1)))
                .header("If-Modified-Since","Wed, 01 Jan 2030 00:00:00 GMT")).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(response.getHeader("Last-Modified")).isNull();assertThat(response.getContentAsByteArray()).isEqualTo(f.bytes());
        mvc.perform(get(path(f)).cookie(cookie).header("Range","bytes=0-0").header("If-Range","stale"))
                .andExpect(status().isPartialContent());
        int opens=store.opens;
        mvc.perform(head(path(f)).cookie(cookie)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/notes/"+f.note()+"/attachments/"+f.id()).cookie(cookie)).andExpect(status().isForbidden());
        mvc.perform(get("/api/notes/"+f.note()+"/ai-processing").cookie(cookie)).andExpect(status().isForbidden());
        mvc.perform(get(path(f)+"/other").cookie(cookie)).andExpect(status().isForbidden());
        assertThat(store.opens).isEqualTo(opens);
    }

    @Test void contentUsesSameCurrentRevisionValidatorAsMetadataWithoutChangingEitherAggregate() throws Exception {
        Fixture f=fixture("pdf","application/pdf",payload(100),"synthetic.pdf");
        jdbc.update("update notes.attachment set revision=2 where attachment_id=?",f.id());var before=row(f.id());
        Cookie cookie=browser(f.owner(),"ROLE_USER");
        String validator=mvc.perform(get("/api/notes/"+f.note()+"/attachments/"+f.id()).cookie(cookie))
                .andExpect(status().isOk()).andReturn().getResponse().getHeader("ETag");
        var response=getContent(f,"bytes=0-0").getResponse();assertThat(response.getStatus()).isEqualTo(206);
        assertThat(response.getHeader("ETag")).isEqualTo(validator).isEqualTo(etags.encode(new AttachmentCoreVersion(f.id(),2)));
        assertThat(row(f.id())).isEqualTo(before);
    }

    private void headers(MockHttpServletResponse response,Fixture f,long length) {
        assertThat(response.getContentType()).isEqualTo(f.type());assertThat(response.getHeader("Content-Length")).isEqualTo(Long.toString(length));
        assertThat(response.getHeader("Accept-Ranges")).isEqualTo("bytes");assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeader("ETag")).isEqualTo(etags.encode(new AttachmentCoreVersion(f.id(),1)));
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    }
    private MvcResult getContent(Fixture f,String range) throws Exception {
        var request=get(path(f)).cookie(browser(f.owner(),"ROLE_USER"));if(range!=null)request.header("Range",range);
        return mvc.perform(request).andReturn();
    }
    private String path(Fixture f) {return "/api/notes/"+f.note()+"/attachments/"+f.id()+"/content";}
    private byte[] payload(int size) {byte[] result=new byte[size];for(int i=0;i<size;i++)result[i]=(byte)(i%251);return result;}
    private byte[] media(String type) throws Exception {
        var bytes=new ByteArrayOutputStream();
        switch(type) {
            case "image/png","image/jpeg" -> javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(16,12,java.awt.image.BufferedImage.TYPE_INT_RGB),type.equals("image/png")?"png":"jpeg",bytes);
            case "audio/wav" -> bytes.write(AttachmentMediaValidatorTest.wav(16000,1,16000));
            case "video/mp4" -> bytes.write(AttachmentParserPreflightTest.supportedMp4());
            default -> {try(var pdf=new org.apache.pdfbox.pdmodel.PDDocument()){pdf.addPage(new org.apache.pdfbox.pdmodel.PDPage());pdf.save(bytes);}}
        }
        return bytes.toByteArray();
    }
    // Technical range/state fixtures establish accepted row+custody directly. Real
    // upload-to-content acceptance remains separately covered for every modality.
    private Fixture fixture(String kind,String type,byte[] bytes,String filename) {
        UUID owner=account(),note=note(owner),id=uuid();String reference="private-attachment/"+id.toString().replace("-","").repeat(2);
        jdbc.update("""
                insert into notes.attachment(attachment_id,note_id,owner_user_id,object_reference,display_filename,media_kind,media_type,size_bytes,
                    width,height,duration_seconds,page_count,storage_state,validation_state,cleanup_state,revision,processing_generation,created_at,updated_at)
                values(?,?,?,?,?,?,?,?,?,?,?,?,'stored','accepted','retained',1,1,now(),now())
                """,id,note,owner,reference,filename,kind,type,bytes.length,kind.equals("image")||kind.equals("video")?16:null,
                kind.equals("image")||kind.equals("video")?12:null,kind.equals("audio")||kind.equals("video")?1.0:null,kind.equals("pdf")?1:null);
        store.bytes.put(reference,bytes);return new Fixture(owner,note,id,reference,bytes,type,filename);
    }
    private UUID uuid(){return jdbc.queryForObject("select uuidv7()",UUID.class);}
    private UUID account(){UUID id=uuid();String email="content-"+id+"@example.test";jdbc.update("insert into identity.account(user_id,canonical_email,display_email,email_verified_at,account_state,created_at,updated_at) values(?,?,?,now(),'active',now(),now())",id,email,email);return id;}
    private UUID note(UUID owner){UUID id=uuid();jdbc.update("insert into notes.note(note_id,owner_user_id,title,markdown,lifecycle_state,pinned,revision,ai_enabled,ai_generation,created_at,updated_at) values(?,?,'Synthetic','Synthetic','active',false,1,false,1,now(),now())",id,owner);return id;}
    private void lifecycle(UUID note,String state){jdbc.update("""
            update notes.note set lifecycle_state=?,revision=revision+1,
            pre_trash_state=case when ?='trashed' then 'active' else null end,trashed_at=case when ?='trashed' then now() else null end,
            deleted_at=case when ?='logically_deleted' then now() else null end where note_id=?
            """,state,state,state,state,note);}
    private long noteRevision(UUID note){return jdbc.queryForObject("select revision from notes.note where note_id=?",Long.class,note);}
    private Map<String,Object> row(UUID id){return jdbc.queryForMap("select * from notes.attachment where attachment_id=?",id);}
    private Cookie browser(UUID user,String role){Session session=sessions.createSession();var context=SecurityContextHolder.createEmptyContext();context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(new IdentitySessionPrincipal(user),null,List.of(new SimpleGrantedAuthority(role))));session.setAttribute("SPRING_SECURITY_CONTEXT",context);save(session);return new Cookie("SESSION",Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8)));}
    @SuppressWarnings({"rawtypes","unchecked"}) private void save(Session session){((SessionRepository)sessions).save(session);}
    private record Fixture(UUID owner,UUID note,UUID id,String reference,byte[] bytes,String type,String filename) { }
    private static class FailingOutput extends MockHttpServletResponse {
        private final boolean afterCommit;
        FailingOutput(boolean afterCommit){this.afterCommit=afterCommit;}
        @Override public ServletOutputStream getOutputStream(){
            var delegate=super.getOutputStream();
            return new ServletOutputStream(){int writes;
                public boolean isReady(){return true;}public void setWriteListener(WriteListener listener){ }
                public void write(int value)throws IOException{write(new byte[]{(byte)value},0,1);}
                public void write(byte[] bytes,int offset,int count)throws IOException{
                    if(!afterCommit||writes++>0)throw new IOException("SYNTHETIC_PRIVATE_DIAGNOSTIC");
                    delegate.write(bytes,offset,count);flushBuffer();
                }
            };
        }
    }
}
