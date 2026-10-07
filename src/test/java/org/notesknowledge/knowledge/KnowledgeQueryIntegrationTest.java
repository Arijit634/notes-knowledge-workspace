package org.notesknowledge.knowledge;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.notesknowledge.identity.IdentitySessionPrincipal;
import org.notesknowledge.knowledge.spi.PrivateAiSourceCurrentness;
import org.notesknowledge.knowledge.spi.PrivateQuerySource;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@Tag("DATABASE") @Tag("API") @Tag("SECURITY") @Tag("RETRIEVAL") @Tag("EVALUATION")
@Testcontainers @SpringBootTest(properties="knowledge.operations.scheduling-enabled=false") @AutoConfigureMockMvc
@org.springframework.context.annotation.Import(org.notesknowledge.notes.DerivationMediaFixtures.class)
class KnowledgeQueryIntegrationTest {
    @Container static final PostgreSQLContainer postgres=new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18-trixie")
        .withDatabaseName("query_contracts").withUsername("synthetic_migrator").withPassword("synthetic-query-password");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",postgres::getJdbcUrl);r.add("spring.datasource.username",postgres::getUsername);r.add("spring.datasource.password",postgres::getPassword);
        r.add("identity.rate.key-base64",()->"AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=");
        r.add("knowledge.operations.envelope.key-base64",()->Base64.getEncoder().encodeToString(new byte[32]));
        r.add("knowledge.operations.handle.key-base64",()->Base64.getEncoder().encodeToString(new byte[32]));
    }
    @Autowired JdbcTemplate jdbc; @Autowired MockMvc mvc; @Autowired ObjectMapper json;
    @Autowired SessionRepository<? extends Session> sessions; @Autowired PlatformTransactionManager transactions;
    @Autowired KnowledgeOperationService operations; @MockitoSpyBean KnowledgeOperationRepository operationRows;
    @Autowired PrivateRepresentationRepository representations; @MockitoSpyBean PrivateQuerySource sources;
    @Autowired KnowledgeOperationMaterialCipher cipher; @Autowired org.notesknowledge.DispatchCoordinator coordination;
    @MockitoBean AiDerivationProperties configuration; @MockitoBean ProviderDispatchProperties dispatch;
    @MockitoBean KnowledgePolicyProperties policyConfiguration; @MockitoBean StructuredKnowledgePort provider;
    @MockitoBean RateLimitPort rates;
    UUID owner,note,policy; Browser browser;
    @BeforeEach void prepare() throws Exception {
        reset(configuration,dispatch,policyConfiguration,provider,rates,operationRows,sources);
        when(configuration.configured()).thenReturn(true);when(configuration.provider()).thenReturn("synthetic");
        when(configuration.embeddingModel()).thenReturn("synthetic-embedding");when(configuration.mediaModel()).thenReturn("synthetic-media");
        when(configuration.modelRevision()).thenReturn("v1");when(configuration.configurationId()).thenReturn("synthetic-v1");
        when(configuration.adapterVersion()).thenReturn("synthetic-v1");when(configuration.region()).thenReturn("synthetic");when(configuration.tier()).thenReturn("synthetic");
        when(configuration.dimension()).thenReturn(8);when(configuration.operator()).thenReturn("cosine");when(configuration.normalization()).thenReturn("none");
        when(configuration.approvedPolicyFingerprint()).thenReturn("a".repeat(64));
        when(provider.available()).thenReturn(true);when(rates.evaluate(any())).thenReturn(new RateLimitPort.Allowed());
        jdbc.update("update knowledge.knowledge_work_intent set state='cancelled',input_ciphertext=null,input_nonce=null,input_key_version=null,result_ciphertext=null,result_nonce=null,result_key_version=null,lease_owner=null,lease_token=null,lease_until=null,next_attempt_at=null,updated_at=clock_timestamp() where work_class='private_knowledge_operation' and state in ('queued','claimed','retry_wait')");
        String code="synthetic-"+UUID.randomUUID();when(policyConfiguration.code()).thenReturn(code);
        policy=jdbc.queryForObject("insert into knowledge.processing_policy(policy_code,policy_version,policy_fingerprint,disclosure_revision,effective_at) values(?,1,?,'synthetic-1',clock_timestamp()-interval '1 minute') returning processing_policy_id",UUID.class,code,"a".repeat(64));
        owner=account();ack(owner);note=note(owner,true,"Synthetic ordinary note","Synthetic ordinary body");browser=browser(owner);
        when(provider.embedQuery(any(),any())).thenAnswer(call->{KnowledgeQueryGate.QueryPermit permit=call.getArgument(0);permit.requireDispatch();assertOutsideTransaction();return vector();});
        when(provider.generate(any(),any(),any(),any())).thenAnswer(call->{KnowledgeQueryGate.EvidencePermit permit=call.getArgument(0);permit.requireDispatch();assertOutsideTransaction();
            List<StructuredKnowledgePort.Evidence> evidence=call.getArgument(3);
            return new StructuredKnowledgePort.Output(List.of(new StructuredKnowledgePort.Claim("Supported synthetic result",List.of(evidence.getFirst().id()))),false);});
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();}

    @ParameterizedTest @ValueSource(strings={"neither","source-only","query-only","both"})
    void unpaidGoogleQueryAndSourceApprovalAreIndependentAtTheProviderBoundary(String approval) throws Exception {
        String query="find synthetic plans";
        when(configuration.provider()).thenReturn("gemini");when(configuration.tier()).thenReturn("unpaid");when(configuration.region()).thenReturn("global");
        when(dispatch.dispatchPolicy()).thenReturn("unpaid-synthetic-demo");
        when(dispatch.approvedQueryFingerprints()).thenReturn(approval.equals("query-only")||approval.equals("both")?List.of(ProviderDispatchPolicy.queryFingerprint(query)):List.of());
        String sourceFingerprint=ProviderDispatchPolicy.fingerprint(sources.note(owner,note).expected());
        when(dispatch.approvedSourceFingerprints()).thenReturn(approval.equals("source-only")||approval.equals("both")?List.of(sourceFingerprint):List.of());
        derive(note,"Synthetic safe plans");
        doAnswer(call->{KnowledgeQueryGate.QueryPermit permit=call.getArgument(0);permit.requireGoogle(call.getArgument(1));assertOutsideTransaction();return vector();}).when(provider).embedQuery(any(),any());
        doAnswer(call->{KnowledgeQueryGate.EvidencePermit permit=call.getArgument(0);permit.requireGoogle(call.getArgument(1));assertOutsideTransaction();return new StructuredKnowledgePort.Output(List.of(new StructuredKnowledgePort.Claim("Synthetic supported",List.of("e0"))),false);}).when(provider).generate(any(),any(),any(),any());
        query(query).andExpect(status().isOk());
        verify(provider,times(approval.equals("query-only")||approval.equals("both")?1:0)).embedQuery(any(),any());
        verify(provider,times(approval.equals("both")?1:0)).generate(any(),any(),any(),any());
    }

    @Test void laterSemanticChunkSurvivesFusionAndOnlyItsActualProvenanceIsCited() throws Exception {
        var chunks=new ArrayList<DerivedSegment>();var embeddings=new ArrayList<float[]>();
        for(int i=0;i<15;i++){String text=i==14?"Kyoto relevant late answer":"Unrelated early marker "+i;chunks.add(DerivedSegment.note(text,"",i*100,i*100+text.length()));embeddings.add(i==14?vector():orthogonal());}
        deriveChunks(chunks,embeddings);
        var t=json.readTree(query("what about Kyoto").andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        var captured=capturedEvidence();assertThat(captured).hasSize(1);assertThat(captured.getFirst().text()).isEqualTo("Kyoto relevant late answer");
        assertThat(t.get("citations").get(0).get("location").get("start").asInt()).isEqualTo(1400);
        assertThat(t.get("aiAnswer").isNull()).isFalse();
    }
    @ParameterizedTest @ValueSource(strings={"note","media"})
    void independentlyRelevantEvidenceNeverDumpsOtherMaterialFromItsParent(String relevant) throws Exception {
        derive(note,relevant.equals("note")?"Kyoto supported text":"UNRELATED_NOTE_SYNTHETIC_SECRET_MARKER",relevant.equals("note")?vector():orthogonal());
        UUID attachment=upload("image");var source=sources.source(owner,note,attachment);
        var lineage=EmbeddingLineage.create(configuration,new ProcessingPolicyService.AcknowledgedProcessingPolicy(policy,1,"a".repeat(64)),"image");
        var segment=new DerivedSegment(relevant.equals("media")?"Kyoto supported image":"UNRELATED_MEDIA_SYNTHETIC_SECRET_MARKER","whole_image","",null,null,null,null,null,null,null,null,null);
        tx(()->{representations.activate(source.expected(),lineage,List.of(segment),List.of(relevant.equals("media")?vector():orthogonal()));return null;});
        var t=json.readTree(query("what about Kyoto").andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        var captured=capturedEvidence();assertThat(captured).hasSize(1);assertThat(captured.getFirst().text()).startsWith("Kyoto supported").doesNotContain("SYNTHETIC_SECRET_MARKER");
        assertThat(captured.getFirst().modality()).isEqualTo(relevant.equals("media")?"image":"note");
        var citation=t.get("citations").get(0);if(relevant.equals("media"))assertThat(citation.get("attachmentId").asText()).isEqualTo(attachment.toString());else assertThat(citation.get("attachmentId").isNull()).isTrue();
    }
    @Test void overlappingSemanticAndLexicalCandidatesDoNotExpandContext() throws Exception {
        jdbc.update("update notes.note set title='Kyoto plans' where note_id=?",note);
        deriveChunks(List.of(DerivedSegment.note("Kyoto supported overlapping evidence one","",0,100),DerivedSegment.note("Kyoto supported overlapping evidence two","",5,105)),List.of(vector(),vector()));
        query("find Kyoto").andExpect(status().isOk());assertThat(capturedEvidence()).hasSize(1);
    }
    @Test void noPositiveSemanticAffinityKeepsDeterministicResultsWithoutDumpingEvidence() throws Exception {
        jdbc.update("update notes.note set title='Kyoto plans' where note_id=?",note);derive(note,"Unrelated synthetic marker",orthogonal());
        var result=json.readTree(query("what about Kyoto").andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(result.get("deterministicResults").toString()).contains(note.toString());assertThat(result.get("aiAnswer").isNull()).isTrue();assertThat(result.get("insufficientEvidence").asBoolean()).isTrue();verify(provider,never()).generate(any(),any(),any(),any());
    }
    @SuppressWarnings("unchecked") private List<StructuredKnowledgePort.Evidence> capturedEvidence(){
        var capture=org.mockito.ArgumentCaptor.forClass(List.class);verify(provider).generate(any(),any(),eq("answer"),capture.capture());return (List<StructuredKnowledgePort.Evidence>)capture.getValue();
    }
    private static float[] orthogonal(){return new float[]{0,1,0,0,0,0,0,0};}
    private void deriveChunks(List<DerivedSegment> segments,List<float[]> embeddings){var source=sources.note(owner,note);var lineage=EmbeddingLineage.create(configuration,new ProcessingPolicyService.AcknowledgedProcessingPolicy(policy,1,"a".repeat(64)),"note");
        tx(()->{representations.activate(source.expected(),lineage,segments,embeddings);return null;});}

    @Test void suggestionMutationWinningBeforeFinalLockCannotInsertOrReturnStaleProposal() throws Exception {
        derive(note,"Synthetic suggestion source");String etag=etag(note);var arrived=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(call->{arrived.countDown();assertThat(release.await(2,TimeUnit.SECONDS)).isTrue();return call.callRealMethod();}).when(sources).validate(anyList(),eq(true));
        var pool=Executors.newSingleThreadExecutor();try{var suggestion=pool.submit(()->suggest(etag));assertThat(arrived.await(5,TimeUnit.SECONDS)).isTrue();
            saveNote(etag).andExpect(status().isOk());release.countDown();assertThat(suggestion.get(5,TimeUnit.SECONDS)).isEqualTo(412);
            assertThat(jdbc.queryForObject("select count(*) from knowledge.organization_suggestion where source_note_id=?",Integer.class,note)).isZero();
        }finally{release.countDown();pool.shutdownNow();}
    }
    @Test void suggestionLockWinningSerializesInsertThenSaveInvalidatesTheProposal() throws Exception {
        derive(note,"Synthetic suggestion source");String etag=etag(note);var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(call->{boolean valid=(Boolean)call.callRealMethod();assertThat(valid).isTrue();locked.countDown();assertThat(release.await(2,TimeUnit.SECONDS)).isTrue();return valid;}).when(sources).validate(anyList(),eq(true));
        var pool=Executors.newFixedThreadPool(2);try{var suggestion=pool.submit(()->suggest(etag));assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();
            var save=pool.submit(()->saveNote(etag).andReturn().getResponse().getStatus());
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);boolean waiting=false;
            while(System.nanoTime()<deadline&&!waiting)waiting=jdbc.queryForObject("select exists(select 1 from pg_stat_activity where datname=current_database() and wait_event_type='Lock' and query ilike '%notes.note%')",Boolean.class);
            assertThat(waiting).as("Note Save waits on the finalization row lock").isTrue();assertThat(save.isDone()).isFalse();
            assertThat(jdbc.queryForObject("select count(*) from knowledge.organization_suggestion where source_note_id=?",Integer.class,note)).isZero();
            release.countDown();assertThat(suggestion.get(5,TimeUnit.SECONDS)).isEqualTo(200);assertThat(save.get(5,TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(jdbc.queryForObject("select state from knowledge.organization_suggestion where source_note_id=?",String.class,note)).isEqualTo("obsolete");
        }finally{release.countDown();pool.shutdownNow();}
    }
    private int suggest(String etag)throws Exception{return mvc.perform(post("/api/notes/"+note+"/organization-suggestions").cookie(browser.cookie).header("X-CSRF-TOKEN",browser.csrf).header("If-Match",etag)).andReturn().getResponse().getStatus();}
    private org.springframework.test.web.servlet.ResultActions saveNote(String etag)throws Exception{return mvc.perform(put("/api/notes/"+note).cookie(browser.cookie).header("X-CSRF-TOKEN",browser.csrf).header("If-Match",etag).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Changed\",\"markdown\":\"New synthetic revision\"}"));}

    @Test void backdatedNewNoteAboveCapturedTupleDoesNotChangeFingerprintOrCoverage() throws Exception {
        jdbc.update("update notes.note set created_at=clock_timestamp()-interval '1 minute',markdown='https://example.test/accepted' where note_id=?",note);
        String handle=accept("all links");var row=claim();var metadata=json.readValue(row.metadata(),KnowledgeOperationService.Metadata.class);var boundary=metadata.boundary();
        UUID later=note(owner,false,"Later","https://example.test/later");Instant backdated=boundary.startedAt().minusMillis(1);
        jdbc.update("update notes.note set created_at=? where note_id=?",java.sql.Timestamp.from(backdated),later);
        assertThat(backdated).isBefore(boundary.startedAt()).isAfter(boundary.notes().createdAt());
        assertThat(sources.fingerprint(owner,boundary,false)).isEqualTo(metadata.fingerprint());
        assertThat(traverse(boundary,false).stream().map(s->s.expected().noteId())).doesNotContain(later);
        operations.execute(row);var result=poll(handle).get("result");assertThat(result.get("coverage").get("completed").asBoolean()).isTrue();assertThat(result.get("coverage").get("corpusChanged").asBoolean()).isFalse();assertThat(result.toString()).doesNotContain(later.toString());
    }
    @Test void backdatedAttachmentCannotEnterAnExplicitlyEmptyCapturedFamily() throws Exception {
        jdbc.update("update notes.note set markdown='https://example.test/accepted' where note_id=?",note);String handle=accept("all links");var row=claim();var metadata=json.readValue(row.metadata(),KnowledgeOperationService.Metadata.class);
        assertThat(metadata.boundary().attachments().empty()).isTrue();UUID later=syntheticAttachment(Instant.EPOCH,"pdf");
        assertThat(sources.source(owner,note,later).createdAt()).isBefore(metadata.boundary().startedAt());
        assertThat(sources.fingerprint(owner,metadata.boundary(),false)).isEqualTo(metadata.fingerprint());
        assertThat(traverse(metadata.boundary(),false).stream().map(s->s.expected().attachmentId())).doesNotContain(later);
        operations.execute(row);assertThat(poll(handle).get("result").get("coverage").get("completed").asBoolean()).isTrue();
    }
    @Test void tupleContinuationVisitsEveryFamilyOnceWithStableRestartAndExcludesAboveMaxima() {
        jdbc.update("update notes.note set created_at='2020-01-01T00:00:00Z' where note_id=?",note);
        var expectedNotes=new HashSet<UUID>();expectedNotes.add(note);var expectedMedia=new HashSet<UUID>();
        for(int i=0;i<14;i++){UUID n=note(owner,true,"Batch "+i,"Body");expectedNotes.add(n);jdbc.update("update notes.note set created_at=? where note_id=?",java.sql.Timestamp.from(Instant.parse("2020-01-01T00:00:00Z").plusSeconds(i/2)),n);expectedMedia.add(syntheticAttachment(Instant.parse("2020-01-01T00:00:00Z").plusSeconds(i/2),"image"));}
        var boundary=sources.capture(owner,List.of("active","archived"));String fingerprint=sources.fingerprint(owner,boundary,true);
        UUID outsideNote=note(owner,true,"Outside","Body"),outsideMedia=syntheticAttachment(boundary.attachments().createdAt().plusSeconds(1),"image");
        var actual=traverse(boundary,true);assertThat(actual.stream().filter(s->s.expected().attachmentId()==null).map(s->s.expected().noteId())).containsExactlyInAnyOrderElementsOf(expectedNotes);
        assertThat(actual.stream().filter(s->s.expected().attachmentId()!=null).map(s->s.expected().attachmentId())).containsExactlyInAnyOrderElementsOf(expectedMedia);
        assertThat(actual).doesNotHaveDuplicates();assertThat(traverse(boundary,true)).isEqualTo(actual);assertThat(sources.fingerprint(owner,boundary,true)).isEqualTo(fingerprint);
        assertThat(actual.stream().map(s->s.expected().noteId())).doesNotContain(outsideNote);assertThat(actual.stream().map(s->s.expected().attachmentId())).doesNotContain(outsideMedia);
        var positions=actual.stream().map(s->new PrivateQuerySource.Position(s.expected().attachmentId()==null?0:1,s.createdAt(),s.expected().attachmentId()==null?s.expected().noteId():s.expected().attachmentId())).toList();
        assertThat(positions).isSortedAccordingTo(Comparator.comparingInt(PrivateQuerySource.Position::family).thenComparing(PrivateQuerySource.Position::createdAt).thenComparing(p->p.sourceId().toString()));
    }
    @Test void backdatedNewAttachmentAboveNonemptyHighWaterIsExcluded() {
        syntheticAttachment(Instant.parse("2020-01-01T00:00:00Z"),"pdf");var b=sources.capture(owner,List.of("active"));String fingerprint=sources.fingerprint(owner,b,false);
        Instant backdated=b.startedAt().minusMillis(1);UUID added=syntheticAttachment(backdated,"pdf");assertThat(backdated).isBefore(b.startedAt()).isAfter(b.attachments().createdAt());
        assertThat(traverse(b,false).stream().map(s->s.expected().attachmentId())).doesNotContain(added);assertThat(sources.fingerprint(owner,b,false)).isEqualTo(fingerprint);
    }
    @Test void emptyNoteFamilyRemainsEmptyAfterBackdatedInsertion() {
        UUID emptyOwner=account();var boundary=sources.capture(emptyOwner,List.of("active"));assertThat(boundary.notes().empty()).isTrue();assertThat(boundary.attachments().empty()).isTrue();String before=sources.fingerprint(emptyOwner,boundary,false);
        UUID added=note(emptyOwner,false,"Later","Body");jdbc.update("update notes.note set created_at='2020-01-01T00:00:00Z' where note_id=?",added);
        assertThat(sources.page(emptyOwner,boundary,null,12,false).sources()).isEmpty();assertThat(sources.fingerprint(emptyOwner,boundary,false)).isEqualTo(before);
    }
    private List<PrivateQuerySource.Source> traverse(PrivateQuerySource.Boundary b,boolean ai){var all=new ArrayList<PrivateQuerySource.Source>();PrivateQuerySource.Position after=null;int pages=0;
        do{var page=sources.page(owner,b,after,3,ai);all.addAll(page.sources());after=page.continuation();assertThat(++pages).isLessThan(50);}while(after!=null);return all;}
    /** Isolated accepted-metadata fixture only; no byte delivery/parse claim and never used as a production store. */
    private UUID syntheticAttachment(Instant created,String kind){return jdbc.queryForObject("insert into notes.attachment(attachment_id,note_id,owner_user_id,media_kind,object_reference,display_filename,media_type,size_bytes,width,height,page_count,storage_state,validation_state,cleanup_state,revision,processing_generation,created_at,updated_at) values(uuidv7(),?,?,?,'private-attachment/'||encode(sha256(uuidv7()::text::bytea),'hex'),'synthetic',?,100,?,?,?,'stored','accepted','retained',1,1,?,clock_timestamp()) returning attachment_id",UUID.class,note,owner,kind,kind.equals("pdf")?"application/pdf":"image/png",kind.equals("image")?1:null,kind.equals("image")?1:null,kind.equals("pdf")?1:null,java.sql.Timestamp.from(created));}

    @Test void rankedSearchKeepsAiOffDeterministicResultsOutOfProviderEvidence() throws Exception {
        UUID off=note(owner,false,"Kyoto excluded","Kyoto private excluded marker");
        jdbc.update("update notes.note set title='Kyoto travel' where note_id=?",note);derive(note,"Kyoto supported evidence",new float[]{0.8f,0.6f,0,0,0,0,0,0});
        UUID foreign=account();ack(foreign);UUID other=note(foreign,true,"Kyoto foreign","Foreign secret marker");derive(other,"Foreign secret marker");
        assertThat(jdbc.queryForObject("select embedding<=>'[1,0,0,0,0,0,0,0]'::vector from knowledge.private_derived_segment where owner_user_id=?",Double.class,foreign))
            .isLessThan(jdbc.queryForObject("select embedding<=>'[1,0,0,0,0,0,0,0]'::vector from knowledge.private_derived_segment where owner_user_id=?",Double.class,owner));
        var response=query("find Kyoto").andExpect(status().isOk()).andReturn().getResponse();var tree=json.readTree(response.getContentAsString());
        var ids=new HashSet<String>();tree.get("deterministicResults").forEach(i->i.get("occurrences").forEach(c->ids.add(c.get("noteId").asText())));
        assertThat(ids).contains(note.toString(),off.toString()).doesNotContain(other.toString());
        var ranked=new ArrayList<String>();tree.get("deterministicResults").forEach(i->ranked.add(i.get("occurrences").get(0).get("noteId").asText()));
        assertFrozenSetQuality(new HashSet<>(ranked.subList(0,Math.min(2,ranked.size()))),Set.of(note.toString(),off.toString()));
        double reciprocalRank=ranked.indexOf(note.toString())<0?0:1.0/(ranked.indexOf(note.toString())+1);
        assertThat(reciprocalRank).isEqualTo(1.0);
        var captured=org.mockito.ArgumentCaptor.forClass(List.class);verify(provider).generate(any(),any(),eq("answer"),captured.capture());
        @SuppressWarnings("unchecked") List<StructuredKnowledgePort.Evidence> payload=(List<StructuredKnowledgePort.Evidence>)captured.getValue();
        assertThat(payload).allSatisfy(e->assertThat(e.text()).doesNotContain("excluded marker","Foreign secret marker"));
        assertThat(tree.get("citations").toString()).contains(note.toString()).doesNotContain(off.toString(),other.toString());
        verify(provider).embedQuery(any(),eq("find Kyoto"));assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    }
    @ParameterizedTest @ValueSource(strings={"unavailable","policy","lineage","invalid-output","empty","conflict"})
    void focusedResultsSeparateDegradationInsufficiencyAndConflicts(String outcome) throws Exception {
        jdbc.update("update notes.note set title='PlayStation login' where note_id=?",note);derive(note,"PlayStation synthetic login");
        switch(outcome){
            case "unavailable"->when(provider.available()).thenReturn(false);
            case "policy"->jdbc.update("insert into knowledge.processing_policy(policy_code,policy_version,policy_fingerprint,disclosure_revision,effective_at) select policy_code,2,policy_fingerprint,'synthetic-2',clock_timestamp() from knowledge.processing_policy where processing_policy_id=?",policy);
            case "lineage"->when(configuration.configurationId()).thenReturn("synthetic-v2");
            case "invalid-output"->doReturn(new StructuredKnowledgePort.Output(List.of(new StructuredKnowledgePort.Claim("Invented",List.of("foreign"))),false)).when(provider).generate(any(),any(),any(),any());
            case "empty"->doReturn(new StructuredKnowledgePort.Output(List.of(),false)).when(provider).generate(any(),any(),any(),any());
            case "conflict"->doReturn(new StructuredKnowledgePort.Output(List.of(new StructuredKnowledgePort.Claim("Conflicting evidence",List.of("e0"))),true)).when(provider).generate(any(),any(),any(),any());
        }
        var t=json.readTree(query("what was my PlayStation login").andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(t.get("deterministicResults").size()).isPositive();
        if(outcome.equals("conflict")){assertThat(t.get("aiAnswer").get("conflicting").asBoolean()).isTrue();assertThat(t.get("citations").size()).isPositive();}
        else {assertThat(t.get("aiAnswer").isNull()).isTrue();assertThat(t.get("citations").size()).isZero();
            assertThat(t.get("insufficientEvidence").asBoolean()).isEqualTo(outcome.equals("empty")||outcome.equals("lineage"));}
    }
    @Test void deterministicUrlsTraverseTheWholeBoundaryIncludingAiOffAndRetainOccurrences() throws Exception {
        jdbc.update("update notes.note set markdown='https://example.test/a https://example.test/a' where note_id=?",note);
        UUID off=note(owner,false,"Excluded links","https://example.test/b");
        note(account(),false,"Foreign links","https://example.test/foreign");
        String handle=accept("show every URL I saved");var row=claim();
        assertThat(row.metadata()).doesNotContain("show every","example.test");
        assertThat(new String(row.input().ciphertext(),StandardCharsets.ISO_8859_1)).doesNotContain("show every");
        operations.execute(row);var t=poll(handle).get("result");
        assertThat(t.get("coverage").get("completed").asBoolean()).isTrue();assertThat(t.get("coverage").get("inspectedSources").asLong()).isEqualTo(2);
        assertThat(t.get("deterministicResults").size()).isEqualTo(2);assertThat(t.get("aiAnswer").isNull()).isTrue();assertThat(t.get("citations").size()).isZero();
        assertThat(t.toString()).contains(note.toString(),off.toString()).doesNotContain("foreign");
        assertThat(t.get("deterministicResults").get(0).get("occurrences").size()).isEqualTo(2);
        long occurrenceCount=0;for(var item:t.get("deterministicResults"))occurrenceCount+=item.get("occurrences").size();
        assertThat(occurrenceCount).isEqualTo(3); // Exact occurrence coverage, not semantic relevance.
        var completed=tx(()->operationRows.owner(owner,row.id()).orElseThrow());assertThat(completed.input()).isNull();assertThat(completed.result()).isNotNull();
        verify(provider,never()).embedQuery(any(),any());verify(provider,never()).generate(any(),any(),any(),any());
    }
    @Test void durableSemanticCorpusCoversArbitraryCategoryBeyondTopKAndRecordsUncertainty() throws Exception {
        jdbc.update("update notes.note set ai_enabled=false where note_id=?",note);
        List<UUID> expected=new ArrayList<>();for(int i=0;i<13;i++){UUID n=note(owner,true,"City "+i,"Synthetic city "+i);expected.add(n);derive(n,"Synthetic city "+i);}
        var known=new HashMap<String,String>();for(int i=0;i<13;i++)known.put("Synthetic city "+i,"City "+i);
        doAnswer(call->{assertOutsideTransaction();KnowledgeQueryGate.EvidencePermit permit=call.getArgument(0);permit.requireDispatch();List<StructuredKnowledgePort.Evidence> e=call.getArgument(3);
            return new StructuredKnowledgePort.Output(List.of(new StructuredKnowledgePort.Claim(known.get(e.getFirst().text()),List.of("e0"))),false);}).when(provider).generate(any(),any(),eq("extract"),any());
        String handle=accept("list every city I mentioned");operations.execute(claim());var t=poll(handle).get("result");
        assertThat(t.get("aiAnswer").get("claims").size()).isEqualTo(13);assertThat(t.get("coverage").get("completed").asBoolean()).isTrue();
        assertThat(t.get("coverage").get("classificationUncertain").asBoolean()).isTrue();assertThat(t.get("citations").size()).isEqualTo(13);
        Set<String> found=new HashSet<>();t.get("citations").forEach(c->found.add(c.get("noteId").asText()));assertThat(found).containsExactlyInAnyOrderElementsOf(expected.stream().map(UUID::toString).toList());
        // Frozen synthetic extraction scores are independent from traversal coverage and real-world classification recall.
        Set<String> claims=new HashSet<>();t.get("aiAnswer").get("claims").forEach(c->claims.add(c.asText()));
        assertFrozenSetQuality(claims,new HashSet<>(known.values()));
        assertFrozenSetQuality(found,new HashSet<>(expected.stream().map(UUID::toString).toList()));
        verify(provider,times(13)).generate(any(),any(),eq("extract"),any());verify(provider,never()).embedQuery(any(),any());
    }
    @Test void checkpointSurvivesWorkerLossAndReclaimDoesNotLosePriorAggregate() throws Exception {
        jdbc.update("update notes.note set markdown='https://example.test/first' where note_id=?",note);note(owner,false,"Second","https://example.test/second");
        String handle=accept("all links");var first=claim();
        // Simulate process loss after a checkpoint has committed, not a rolled-back checkpoint transaction.
        doAnswer(call->{var value=call.callRealMethod();if(operationVersion(first.id())>0)throw new SyntheticWorkerLoss();return value;}).when(operationRows).owner(eq(owner),eq(first.id()));
        assertThatThrownBy(()->operations.execute(first)).isInstanceOf(SyntheticWorkerLoss.class);reset(operationRows);
        assertThat(operationVersion(first.id())).isEqualTo(1);jdbc.update("update knowledge.knowledge_work_intent set lease_until=clock_timestamp()-interval '1 second' where knowledge_work_intent_id=?",first.id());
        var reclaimed=claim();assertThat(reclaimed.lease().value()).isNotEqualTo(first.lease().value());assertThat(reclaimed.version()).isEqualTo(1);
        assertThat(tx(()->operationRows.current(first))).isFalse();operations.execute(reclaimed);
        assertThat(poll(handle).get("result").get("deterministicResults").size()).isEqualTo(2);
    }
    @Test void authoritativeCancelFencesLateCheckpointAndCompletionAndIsIdempotent() throws Exception {
        String handle=accept("every URL");var current=claim();var frame=resultFrame(current);
        mvc.perform(delete("/api/knowledge/operations/"+handle).cookie(browser.cookie).header("X-CSRF-TOKEN",browser.csrf)).andExpect(status().isNoContent());
        assertThat(tx(()->operationRows.checkpoint(current,"{}",frame))).isFalse();assertThat(tx(()->operationRows.complete(current,"{}",frame))).isFalse();
        mvc.perform(delete("/api/knowledge/operations/"+handle).cookie(browser.cookie).header("X-CSRF-TOKEN",browser.csrf)).andExpect(status().isNoContent());
        assertThat(poll(handle).get("status").asText()).isEqualTo("cancelled");var cancelled=tx(()->operationRows.owner(owner,current.id()).orElseThrow());assertThat(cancelled.input()).isNull();assertThat(cancelled.result()).isNull();assertThat(cancelled.lease()).isNull();
    }
    @Test void completedPollRevalidatesAllSourceAuthorityAndNeverRepairsClaimsByDroppingCitations() throws Exception {
        derive(note,"Synthetic current evidence");String handle=accept("all books");var row=claim();operations.execute(row);
        assertThat(poll(handle).get("status").asText()).isEqualTo("completed");
        jdbc.update("update notes.note set ai_enabled=false,ai_generation=ai_generation+1,revision=revision+1 where note_id=?",note);
        var t=poll(handle);assertThat(t.get("status").asText()).isEqualTo("obsolete");assertThat(t.get("result").isNull()).isTrue();assertThat(tx(()->operationRows.owner(owner,row.id()).orElseThrow()).result()).isNull();
    }
    @Test void newSourcesAfterAcceptanceAreOutsideItsHistoricalBoundary() throws Exception {
        jdbc.update("update notes.note set markdown='https://example.test/accepted' where note_id=?",note);String handle=accept("all links");
        UUID later=note(owner,false,"Later","https://example.test/later");operations.execute(claim());var t=poll(handle).get("result");
        assertThat(t.get("coverage").get("completed").asBoolean()).isTrue();assertThat(t.get("deterministicResults").size()).isEqualTo(1);assertThat(t.toString()).doesNotContain(later.toString(),"example.test/later");
    }
    @Test void privateOperationsRequireOwnerCsrfAndOpaqueUntamperedHandle() throws Exception {
        String handle=accept("all links");var other=browser(account());
        mvc.perform(get("/api/knowledge/operations/"+handle).cookie(other.cookie)).andExpect(status().isNotFound());
        mvc.perform(get("/api/knowledge/operations/not-a-handle").cookie(browser.cookie)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/knowledge/operations/"+handle).cookie(browser.cookie)).andExpect(status().isForbidden());
        mvc.perform(post("/api/knowledge/query").cookie(browser.cookie).contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"all links\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/knowledge/query").contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"all links\"}")).andExpect(status().is4xxClientError());
    }
    @Test void relatedAndSuggestionsUseCurrentOwnerSourceEtagAndExplicitTagAttribution() throws Exception {
        derive(note,"Synthetic source");UUID related=note(owner,true,"Related synthetic","Related");derive(related,"Related");
        UUID excluded=note(owner,false,"Excluded","Excluded");UUID foreign=note(account(),true,"Foreign","Foreign");
        String etag=etag(note);var r=mvc.perform(post("/api/notes/"+note+"/related").cookie(browser.cookie).header("X-CSRF-TOKEN",browser.csrf).header("If-Match",etag)).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(r.getContentAsString()).contains(related.toString()).doesNotContain(excluded.toString(),foreign.toString(),"distance","embedding");
        mvc.perform(post("/api/notes/"+note+"/related").cookie(browser.cookie).header("X-CSRF-TOKEN",browser.csrf)).andExpect(status().isPreconditionRequired());
        mvc.perform(post("/api/notes/"+note+"/related").cookie(browser.cookie).header("X-CSRF-TOKEN",browser.csrf).header("If-Match","\"stale\"")).andExpect(status().isPreconditionFailed());
        doReturn(new StructuredKnowledgePort.Output(List.of(new StructuredKnowledgePort.Claim("Travel",List.of("e0"))),false)).when(provider).generate(any(),any(),eq("tags"),any());
        var s=mvc.perform(post("/api/notes/"+note+"/organization-suggestions").cookie(browser.cookie).header("X-CSRF-TOKEN",browser.csrf).header("If-Match",etag)).andExpect(status().isOk()).andReturn().getResponse();String id=json.readTree(s.getContentAsString()).get("suggestionId").asText();
        assertThat(jdbc.queryForObject("select count(*) from notes.note_tag where note_id=?",Integer.class,note)).isZero();assertThat(etag(note)).isEqualTo(etag);
        mvc.perform(put("/api/notes/"+note+"/tags").cookie(browser.cookie).header("X-CSRF-TOKEN",browser.csrf).header("If-Match",etag).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("tags",List.of("Travel"),"acceptedSuggestionId",id)))).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select state from knowledge.organization_suggestion where suggestion_id=?",String.class,UUID.fromString(id))).isEqualTo("accepted");
    }
    @Test void oppositeOrderMultiNoteDispatchUsesOneDeterministicOrder() throws Exception {
        UUID second=note(owner,true,"Second","Body");var start=new CyclicBarrier(2);var pool=Executors.newFixedThreadPool(2);
        try{var a=pool.submit(()->{start.await();try(var h=coordination.dispatchMany(owner,List.of(note,second))){h.requireDispatchScopes(owner,List.of(note,second));}return true;});
            var b=pool.submit(()->{start.await();try(var h=coordination.dispatchMany(owner,List.of(second,note))){h.requireDispatchScopes(owner,List.of(second,note));}return true;});assertThat(a.get(8,TimeUnit.SECONDS)).isTrue();assertThat(b.get(8,TimeUnit.SECONDS)).isTrue();}
        finally{pool.shutdownNow();}
    }
    @ParameterizedTest @ValueSource(strings={"source_note_id","operation_purpose","input_nonce","input_key_version","operation_expires_at","work_class","input_ciphertext","state"})
    void operationShapeCannotBecomeLooseMetadata(String field) throws Exception {
        accept("all links");var row=claim();String assignment=switch(field){case "source_note_id"->"source_note_id='"+note+"'";case "operation_purpose"->"operation_purpose='arbitrary'";case "input_nonce"->"input_nonce=null";case "input_key_version"->"input_key_version=null";case "operation_expires_at"->"operation_expires_at=null";case "work_class"->"work_class='generic_job'";case "input_ciphertext"->"input_ciphertext=decode(repeat('00',16401),'hex')";default->"state='completed'";};
        assertThatThrownBy(()->jdbc.update("update knowledge.knowledge_work_intent set "+assignment+" where knowledge_work_intent_id=?",row.id())).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void staleCheckpointVersionCannotOverwriteAndTerminalMaterialCannotBeResurrected() throws Exception {
        accept("all links");var row=claim();var envelope=resultFrame(row);
        assertThat(tx(()->operationRows.checkpoint(row,row.metadata(),envelope))).isTrue();assertThat(tx(()->operationRows.checkpoint(row,row.metadata(),envelope))).isFalse();
        var newer=tx(()->operationRows.owner(owner,row.id()).orElseThrow());assertThat(newer.version()).isEqualTo(1);
        assertThat(tx(()->operationRows.complete(newer,newer.metadata(),resultFrame(newer)))).isTrue();
        assertThatThrownBy(()->jdbc.update("update knowledge.knowledge_work_intent set state='queued',input_ciphertext=?,input_nonce=?,input_key_version=? where knowledge_work_intent_id=?",row.input().ciphertext(),row.input().nonce(),row.input().keyVersion(),row.id())).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void correctedSpellingAndShorthandUseDistinctFuzzyAndSemanticSignals() throws Exception {
        jdbc.update("update notes.note set title='goohle login',markdown='synthetic provider login' where note_id=?",note);
        UUID ps=note(owner,true,"ps login","password: synthetic-console-value");derive(ps,"ps login\npassword: synthetic-console-value");
        when(provider.available()).thenReturn(false);
        var fuzzy=json.readTree(query("google").andExpect(status().isOk()).andReturn().getResponse().getContentAsString());assertThat(fuzzy.get("deterministicResults").toString()).contains(note.toString());
        when(provider.available()).thenReturn(true);
        doAnswer(call->{KnowledgeQueryGate.EvidencePermit permit=call.getArgument(0);permit.requireDispatch();assertOutsideTransaction();List<StructuredKnowledgePort.Evidence> evidence=call.getArgument(3);
            assertThat(evidence.getFirst().text()).contains("password: synthetic-console-value");return new StructuredKnowledgePort.Output(List.of(new StructuredKnowledgePort.Claim("synthetic-console-value",List.of("e0"))),false);}).when(provider).generate(any(),any(),eq("answer"),any());
        var semantic=json.readTree(query("what was my PlayStation login").andExpect(status().isOk()).andReturn().getResponse().getContentAsString());assertThat(semantic.get("citations").toString()).contains(ps.toString(),"note_text");
        assertThat(semantic.get("aiAnswer").get("claims").get(0).asText()).isEqualTo("synthetic-console-value");
        Set<String> cited=new HashSet<>();semantic.get("citations").forEach(c->cited.add(c.get("noteId").asText()));
        assertFrozenSetQuality(cited,Set.of(ps.toString())); // Focused citation precision/recall, separate from answer exactness.
    }
    @ParameterizedTest @ValueSource(strings={"image","audio","video","pdf"})
    void currentMediaEvidenceUsesTypedProvenanceWithoutOpeningBytesForAi(String kind) throws Exception {
        UUID attachment=upload(kind);var source=sources.source(owner,note,attachment);
        var lineage=EmbeddingLineage.create(configuration,new ProcessingPolicyService.AcknowledgedProcessingPolicy(policy,1,"a".repeat(64)),kind);
        DerivedSegment segment=switch(kind){case "image"->new DerivedSegment("Synthetic image temple","whole_image","",null,null,null,null,null,null,null,null,null);
            case "audio","video"->new DerivedSegment("Synthetic recorded temple","transcript","",null,null,null,0.0,0.5,null,null,null,null);
            default->new DerivedSegment("Synthetic PDF temple","pdf_text","",null,null,1,null,null,null,null,null,null);};
        tx(()->{representations.activate(source.expected(),lineage,List.of(segment),List.of(vector()));return null;});
        var t=json.readTree(query("find temple").andExpect(status().isOk()).andReturn().getResponse().getContentAsString());assertThat(t.get("citations").toString()).contains(attachment.toString(),segment.kind());
        var capture=org.mockito.ArgumentCaptor.forClass(List.class);verify(provider).generate(any(),eq("find temple"),eq("answer"),capture.capture());
        assertThat(capture.getValue()).hasSize(1);
        jdbc.update("update notes.note set ai_enabled=false,ai_generation=ai_generation+1,revision=revision+1 where note_id=?",note);clearInvocations(provider);
        var excluded=json.readTree(query("find temple").andExpect(status().isOk()).andReturn().getResponse().getContentAsString());assertThat(excluded.get("aiAnswer").isNull()).isTrue();verify(provider,never()).generate(any(),any(),any(),any());
        mvc.perform(get("/api/notes/"+note+"/attachments/"+attachment).cookie(browser.cookie)).andExpect(status().isOk());
        mvc.perform(get("/api/notes/"+note+"/attachments/"+attachment+"/content").cookie(browser.cookie)).andExpect(status().isOk());
    }
    @Test void multiNotePoisonIndependentlyVetoesOtherwiseCurrentEvidence() throws Exception {
        derive(note,"Synthetic first evidence");UUID second=note(owner,true,"Second source","Synthetic second");derive(second,"Synthetic second evidence");
        doAnswer(call->{assertOutsideTransaction();KnowledgeQueryGate.EvidencePermit permit=call.getArgument(0);permit.requireDispatch();List<StructuredKnowledgePort.Evidence> e=call.getArgument(3);assertThat(e).hasSize(2);
            Integer pid=jdbc.queryForObject("select distinct pid from pg_locks where locktype='advisory' and classid=17403 and granted and mode='ExclusiveLock'",Integer.class);
            assertThat(jdbc.queryForObject("select pg_terminate_backend(?)",Boolean.class,pid)).isTrue();
            assertThat(sources.matches(sources.note(owner,note),true)).isTrue();assertThat(sources.matches(sources.note(owner,second),true)).isTrue();
            return new StructuredKnowledgePort.Output(List.of(new StructuredKnowledgePort.Claim("Must not be exposed",List.of("e0","e1"))),false);}).when(provider).generate(any(),any(),any(),any());
        var t=json.readTree(query("find synthetic sources").andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(t.get("aiAnswer").isNull()).isTrue();assertThat(t.get("degraded").asText()).isEqualTo("coordinationUnavailable");assertThat(t.get("citations").size()).isZero();assertThat(t.get("deterministicResults").size()).isEqualTo(2);
    }
    @Test void repeatedCorpusMutationReportsRetryRatherThanFalseComplete() throws Exception {
        derive(note,"Synthetic source evidence");String handle=accept("all books");var count=new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call->{KnowledgeQueryGate.EvidencePermit permit=call.getArgument(0);permit.requireDispatch();int revision=count.incrementAndGet()+1;
            // Synthetic fixture mutation simulates a committed source change at the result-currentness boundary.
            jdbc.update("update notes.note set revision=? where note_id=?",revision,note);
            var source=sources.note(owner,note);var l=EmbeddingLineage.create(configuration,new ProcessingPolicyService.AcknowledgedProcessingPolicy(policy,1,"a".repeat(64)),"note");
            tx(()->{representations.activate(source.expected(),l,List.of(new DerivedSegment("Synthetic new evidence","note_text","",0,22,null,null,null,null,null,null,null)),List.of(vector()));return null;});
            return new StructuredKnowledgePort.Output(List.of(new StructuredKnowledgePort.Claim("Changed synthetic result",List.of("e0"))),false);}).when(provider).generate(any(),any(),eq("extract"),any());
        operations.execute(claim());var t=poll(handle);assertThat(t.get("status").asText()).isEqualTo("completed");var result=t.get("result");
        assertThat(result.get("aiAnswer").isNull()).isTrue();assertThat(result.get("coverage").get("completed").asBoolean()).isFalse();assertThat(result.get("coverage").get("corpusChanged").asBoolean()).isTrue();assertThat(result.get("coverage").get("retryRequired").asBoolean()).isTrue();
    }
    @Test void staleOrForeignSuggestionCannotAttributeAnExplicitTagMutation() throws Exception {
        derive(note,"Synthetic source");doReturn(new StructuredKnowledgePort.Output(List.of(new StructuredKnowledgePort.Claim("Travel",List.of("e0"))),false)).when(provider).generate(any(),any(),eq("tags"),any());
        var s=mvc.perform(post("/api/notes/"+note+"/organization-suggestions").cookie(browser.cookie).header("X-CSRF-TOKEN",browser.csrf).header("If-Match",etag(note))).andExpect(status().isOk()).andReturn().getResponse();String id=json.readTree(s.getContentAsString()).get("suggestionId").asText();
        mvc.perform(put("/api/notes/"+note+"/pin").cookie(browser.cookie).header("X-CSRF-TOKEN",browser.csrf).header("If-Match",etag(note))).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select state from knowledge.organization_suggestion where suggestion_id=?",String.class,UUID.fromString(id))).isEqualTo("obsolete");
        mvc.perform(put("/api/notes/"+note+"/tags").cookie(browser.cookie).header("X-CSRF-TOKEN",browser.csrf).header("If-Match",etag(note)).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("tags",List.of("Travel"),"acceptedSuggestionId",id)))).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("select count(*) from notes.note_tag where note_id=?",Integer.class,note)).isZero();
        var foreign=browser(account());mvc.perform(post("/api/notes/"+note+"/organization-suggestions").cookie(foreign.cookie).header("X-CSRF-TOKEN",foreign.csrf).header("If-Match",etag(note))).andExpect(status().isNotFound());
    }
    @Test @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void queryAndEnvelopeMaterialNeverAppearInLogsOrMetricDimensions(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        String marker="SyntheticOpaqueQueryPrivacyMarker";query("all links "+marker).andExpect(status().isAccepted());var row=claim();operations.execute(row);
        assertThat(output.getAll()).doesNotContain(marker,owner.toString(),note.toString(),row.id().toString(),row.lease().value().toString());
        assertThat(row.toString()+row.input()+row.metadata()).doesNotContain(marker);
    }
    @Test void expiredMaterialIsUnavailableAndClearedWithoutResurrectingTerminalRows() throws Exception {
        Instant created=Instant.now().minusSeconds(7200),expiry=Instant.now().minusSeconds(3600);UUID id=jdbc.queryForObject("select uuidv7()",UUID.class);
        var frame=cipher.seal(new KnowledgeOperationMaterialCipher.Context(id,owner,"deterministic_corpus",KnowledgeOperationMaterialCipher.Kind.INPUT,0),new byte[]{1});
        tx(()->{operationRows.insert(id,owner,"deterministic_corpus",created,expiry,"{}",frame);return null;});
        assertThat(tx(()->operationRows.owner(owner,id))).isEmpty();tx(()->{operationRows.expire();return null;});
        assertThat(jdbc.queryForObject("select state from knowledge.knowledge_work_intent where knowledge_work_intent_id=?",String.class,id)).isEqualTo("obsolete");
        assertThat(jdbc.queryForObject("select input_ciphertext is null and result_ciphertext is null from knowledge.knowledge_work_intent where knowledge_work_intent_id=?",Boolean.class,id)).isTrue();
    }
    @Test void derivationRowsCannotBorrowOperationEnvelopesOrLoseTheirSourceShape() {
        String lineage="d".repeat(64);UUID work=jdbc.queryForObject("insert into knowledge.knowledge_work_intent(owner_user_id,work_class,source_kind,source_note_id,expected_revision,expected_ai_generation,max_attempts,next_attempt_at,dedupe_key,target_lineage_id) values(?,'private_note_derivation','note',?,1,1,5,now(),?,?) returning knowledge_work_intent_id",UUID.class,owner,note,lineage,lineage);
        assertThatThrownBy(()->jdbc.update("update knowledge.knowledge_work_intent set source_note_id=null where knowledge_work_intent_id=?",work)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("update knowledge.knowledge_work_intent set input_ciphertext=decode(repeat('00',17),'hex'),input_nonce=decode(repeat('00',12),'hex'),input_key_version='v1' where knowledge_work_intent_id=?",work)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("delete from notes.note where note_id=?",note)).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @ParameterizedTest @ValueSource(strings={"[]","[null]","[42]","[{}]","[\"\"]","[\"<script>\"]","{}"})
    void proposalValuesCannotHideUnboundedOrUntypedPayloads(String values){
        assertThatThrownBy(()->jdbc.update("insert into knowledge.organization_suggestion(owner_user_id,source_note_id,source_revision,processing_generation,proposal_kind,proposal_values) values(?,?,1,1,'tags',?::jsonb)",owner,note,values)).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void oversizedAggregateEndsTruthfullyWithinTheDurableEnvelopeBound() throws Exception {
        String a=java.util.stream.IntStream.range(0,300).mapToObj(i->"https://example.test/"+i+"/"+"a".repeat(1950)).collect(java.util.stream.Collectors.joining("\n"));
        String b=java.util.stream.IntStream.range(300,600).mapToObj(i->"https://example.test/"+i+"/"+"b".repeat(1950)).collect(java.util.stream.Collectors.joining("\n"));
        jdbc.update("update notes.note set markdown=? where note_id=?",a,note);note(owner,false,"Second bounded source",b);String handle=accept("all links");var row=claim();operations.execute(row);
        var result=poll(handle).get("result");assertThat(result.get("coverage").get("completed").asBoolean()).isFalse();assertThat(result.get("coverage").get("truncated").asBoolean()).isTrue();assertThat(result.get("degraded").asText()).isEqualTo("budgetExceeded");
        assertThat(jdbc.queryForObject("select octet_length(result_ciphertext) from knowledge.knowledge_work_intent where knowledge_work_intent_id=?",Integer.class,row.id())).isLessThanOrEqualTo(1048592);
    }
    @Test void deterministicPdfTextProvidesPageOccurrencesWithoutAiOrUrlFetch() throws Exception {
        byte[] bytes;try(var pdf=new org.apache.pdfbox.pdmodel.PDDocument();var output=new java.io.ByteArrayOutputStream()){
            var page=new org.apache.pdfbox.pdmodel.PDPage();pdf.addPage(page);try(var content=new org.apache.pdfbox.pdmodel.PDPageContentStream(pdf,page)){
                content.beginText();content.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA),12);content.newLineAtOffset(40,700);content.showText("https://example.test/pdf-link");content.endText();}pdf.save(output);bytes=output.toByteArray();}
        var upload=mvc.perform(multipart("/api/notes/"+note+"/attachments").file(new org.springframework.mock.web.MockMultipartFile("file","synthetic.pdf","application/pdf",bytes)).cookie(browser.cookie).header("X-CSRF-TOKEN",browser.csrf)).andExpect(status().isCreated()).andReturn().getResponse();String attachment=json.readTree(upload.getContentAsString()).get("id").asText();
        upload("image");jdbc.update("update notes.note set ai_enabled=false where note_id=?",note);String handle=accept("all URLs");operations.execute(claim());var result=poll(handle).get("result");
        assertThat(result.get("coverage").get("boundary").asText()).isEqualTo("acceptedNoteAndPdfText");assertThat(result.get("coverage").get("inspectedSources").asLong()).isEqualTo(2);assertThat(result.get("coverage").get("completed").asBoolean()).isTrue();
        var items=result.get("deterministicResults");assertThat(items.size()).isEqualTo(1);assertThat(items.get(0).get("value").asText()).isEqualTo("https://example.test/pdf-link");assertThat(items.get(0).get("occurrences").get(0).get("attachmentId").asText()).isEqualTo(attachment);assertThat(items.get(0).get("occurrences").get(0).get("location").get("page").asInt()).isEqualTo(1);
        verify(provider,never()).embedQuery(any(),any());verify(provider,never()).generate(any(),any(),any(),any());
    }
    @Test void cancellationDuringProviderCallPreventsLateResultActivation() throws Exception {
        derive(note,"Synthetic cancellable evidence");String handle=accept("all books");var row=claim();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(call->{KnowledgeQueryGate.EvidencePermit permit=call.getArgument(0);permit.requireDispatch();assertOutsideTransaction();entered.countDown();assertThat(release.await(5,TimeUnit.SECONDS)).isTrue();
            return new StructuredKnowledgePort.Output(List.of(new StructuredKnowledgePort.Claim("Cancelled late result",List.of("e0"))),false);}).when(provider).generate(any(),any(),any(),any());
        var pool=Executors.newSingleThreadExecutor();try{var work=pool.submit(()->operations.execute(row));assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            mvc.perform(delete("/api/knowledge/operations/"+handle).cookie(browser.cookie).header("X-CSRF-TOKEN",browser.csrf)).andExpect(status().isNoContent());release.countDown();work.get(5,TimeUnit.SECONDS);
            assertThat(poll(handle).get("status").asText()).isEqualTo("cancelled");var after=tx(()->operationRows.owner(owner,row.id()).orElseThrow());assertThat(after.input()).isNull();assertThat(after.result()).isNull();}
        finally{release.countDown();pool.shutdownNow();}
    }
    @Test void savedUrlNeverContactsEvenALocalHttpTrap() throws Exception {
        var contacts=new java.util.concurrent.atomic.AtomicInteger();var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{contacts.incrementAndGet();exchange.sendResponseHeaders(200,-1);exchange.close();});server.start();
        try{String url="http://127.0.0.1:"+server.getAddress().getPort()+"/inert";jdbc.update("update notes.note set markdown=? where note_id=?",url,note);String handle=accept("all links");operations.execute(claim());
            assertThat(poll(handle).get("result").get("deterministicResults").get(0).get("value").asText()).isEqualTo(url);assertThat(contacts).hasValue(0);verify(provider,never()).generate(any(),any(),any(),any());}
        finally{server.stop(0);}
    }
    private UUID upload(String kind)throws Exception{var response=mvc.perform(multipart("/api/notes/"+note+"/attachments").file(org.notesknowledge.notes.DerivationMediaFixtures.media(kind)).cookie(browser.cookie).header("X-CSRF-TOKEN",browser.csrf)).andExpect(status().isCreated()).andReturn().getResponse();return UUID.fromString(json.readTree(response.getContentAsString()).get("id").asText());}
    private static class SyntheticWorkerLoss extends RuntimeException { }
    private void derive(UUID id,String text){derive(id,text,vector());}
    private void derive(UUID id,String text,float[] embedding){UUID user=jdbc.queryForObject("select owner_user_id from notes.note where note_id=?",UUID.class,id);var e=new PrivateAiSourceCurrentness.Expected(user,id,null,1,1,null);
        var l=EmbeddingLineage.create(configuration,new ProcessingPolicyService.AcknowledgedProcessingPolicy(policy,1,"a".repeat(64)),"note");tx(()->{representations.activate(e,l,List.of(new DerivedSegment(text,"note_text","",0,text.length(),null,null,null,null,null,null,null)),List.of(embedding));return null;});}
    private <T>T tx(java.util.function.Supplier<T> fn){return new TransactionTemplate(transactions).execute(s->fn.get());}
    private org.springframework.test.web.servlet.ResultActions query(String q)throws Exception{return mvc.perform(post("/api/knowledge/query").cookie(browser.cookie).header("X-CSRF-TOKEN",browser.csrf).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("query",q))));}
    private String accept(String q)throws Exception{var response=query(q).andExpect(status().isAccepted()).andReturn().getResponse();String handle=json.readTree(response.getContentAsString()).get("operationId").asText();assertThat(response.getHeader("Location")).isEqualTo("/api/knowledge/operations/"+handle);return handle;}
    private tools.jackson.databind.JsonNode poll(String handle)throws Exception{return json.readTree(mvc.perform(get("/api/knowledge/operations/"+handle).cookie(browser.cookie)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());}
    private KnowledgeOperationRepository.Row claim(){return operations.claim(2).stream().filter(r->r.owner().equals(owner)).findFirst().orElseThrow();}
    private long operationVersion(UUID id){return jdbc.queryForObject("select checkpoint_version from knowledge.knowledge_work_intent where knowledge_work_intent_id=?",Long.class,id);}
    private KnowledgeOperationMaterialCipher.Envelope resultFrame(KnowledgeOperationRepository.Row r){return cipher.seal(new KnowledgeOperationMaterialCipher.Context(r.id(),r.owner(),r.purpose(),KnowledgeOperationMaterialCipher.Kind.RESULT,r.version()+1),new byte[]{1});}
    private UUID account(){UUID id=jdbc.queryForObject("select uuidv7()",UUID.class);String email="synthetic-"+id+"@example.test";jdbc.update("insert into identity.account(user_id,canonical_email,display_email,email_verified_at,account_state,created_at,updated_at) values(?,?,?,now(),'active',now(),now())",id,email,email);return id;}
    private UUID note(UUID user,boolean enabled,String title,String body){return jdbc.queryForObject("insert into notes.note(note_id,owner_user_id,title,markdown,lifecycle_state,ai_enabled,revision,ai_generation,created_at,updated_at) values(uuidv7(),?,?,?,'active',?,1,1,clock_timestamp(),clock_timestamp()) returning note_id",UUID.class,user,title,body,enabled);}
    private void ack(UUID user){jdbc.update("insert into knowledge.processing_policy_acknowledgement(user_id,processing_policy_id,disclosure_revision) values(?,?,'synthetic-1')",user,policy);}
    private String etag(UUID id)throws Exception{return mvc.perform(get("/api/notes/"+id).cookie(browser.cookie)).andExpect(status().isOk()).andReturn().getResponse().getHeader("ETag");}
    private Browser browser(UUID user)throws Exception{Session s=sessions.createSession();var c=SecurityContextHolder.createEmptyContext();c.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(new IdentitySessionPrincipal(user),null,List.of(new SimpleGrantedAuthority("ROLE_USER"))));s.setAttribute("SPRING_SECURITY_CONTEXT",c);save(s);var cookie=new Cookie("SESSION",Base64.getEncoder().encodeToString(s.getId().getBytes(StandardCharsets.UTF_8)));var response=mvc.perform(get("/api/auth/csrf").cookie(cookie)).andExpect(status().isOk()).andReturn().getResponse();return new Browser(response.getCookie("SESSION")==null?cookie:response.getCookie("SESSION"),json.readTree(response.getContentAsString()).get("csrfToken").asText());}
    @SuppressWarnings({"rawtypes","unchecked"})private void save(Session s){((SessionRepository)sessions).save(s);}
    private static float[] vector(){return new float[]{1,0,0,0,0,0,0,0};}
    private static void assertOutsideTransaction(){assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();}
    private static void assertFrozenSetQuality(Set<String> actual,Set<String> expected){
        long truePositives=actual.stream().filter(expected::contains).count();
        double precision=actual.isEmpty()?0:(double)truePositives/actual.size();
        double recall=expected.isEmpty()?0:(double)truePositives/expected.size();
        double f1=precision+recall==0?0:2*precision*recall/(precision+recall);
        assertThat(precision).as("frozen synthetic precision").isEqualTo(1.0);
        assertThat(recall).as("frozen synthetic recall").isEqualTo(1.0);
        assertThat(f1).as("frozen synthetic F1").isEqualTo(1.0);
    }
    private record Browser(Cookie cookie,String csrf){ }
}
