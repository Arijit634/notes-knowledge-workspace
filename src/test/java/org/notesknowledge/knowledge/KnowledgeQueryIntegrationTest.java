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
@Testcontainers @SpringBootTest(properties={"knowledge.operations.scheduling-enabled=false","knowledge.operations.extraction-batch-size=32"}) @AutoConfigureMockMvc
@org.springframework.context.annotation.Import(org.notesknowledge.notes.DerivationMediaFixtures.class)
class KnowledgeQueryIntegrationTest {
    private ProcessingDisclosureFixtures disclosureFixtures;
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
    @Autowired ExactPrivateVectorSearch vectors;
    @MockitoSpyBean PrivateAiSourceCurrentness currentSources;
    @MockitoBean AiDerivationProperties configuration; @MockitoBean ProviderDispatchProperties dispatch;
    @MockitoBean KnowledgePolicyProperties policyConfiguration; @MockitoBean StructuredKnowledgePort provider;
    @MockitoBean AudioSignalPort audioSignals;
    @MockitoSpyBean QueryEvidenceRepository evidenceRows;
    @MockitoBean RateLimitPort rates;
    @MockitoBean ProcessingDisclosureResolver disclosures;
    UUID owner,note,policy; Browser browser;
    @BeforeEach void prepare() throws Exception {
        reset(configuration,dispatch,policyConfiguration,provider,audioSignals,evidenceRows,rates,operationRows,sources,currentSources);
        when(configuration.configured()).thenReturn(true);when(configuration.provider()).thenReturn("synthetic");
        when(configuration.embeddingModel()).thenReturn("synthetic-embedding");when(configuration.mediaModel()).thenReturn("synthetic-media");
        when(configuration.modelRevision()).thenReturn("v1");when(configuration.configurationId()).thenReturn("synthetic-v1");
        when(configuration.adapterVersion()).thenReturn("synthetic-v1");when(configuration.region()).thenReturn("synthetic");when(configuration.tier()).thenReturn("synthetic");
        when(configuration.dimension()).thenReturn(8);when(configuration.operator()).thenReturn("cosine");when(configuration.normalization()).thenReturn("none");
        when(configuration.approvedPolicyFingerprint()).thenReturn("a".repeat(64));
        when(provider.available()).thenReturn(true);when(rates.evaluate(any())).thenReturn(new RateLimitPort.Allowed());
        String code="synthetic-"+UUID.randomUUID();when(policyConfiguration.code()).thenReturn(code);
        disclosureFixtures=new ProcessingDisclosureFixtures(disclosures,configuration);
        policy=disclosureFixtures.insert(jdbc,code,1,true);
        owner=account();ack(owner);note=note(owner,true,"Synthetic ordinary note","Synthetic ordinary body");browser=browser(owner);
        when(provider.embedQuery(any(),any())).thenAnswer(call->{KnowledgeQueryGate.QueryPermit permit=call.getArgument(0);permit.requireDispatch();assertOutsideTransaction();return vector();});
        when(provider.generate(any(),any(),any(),any())).thenAnswer(call->{KnowledgeQueryGate.EvidencePermit permit=call.getArgument(0);permit.requireDispatch();assertOutsideTransaction();
            List<StructuredKnowledgePort.Evidence> evidence=call.getArgument(3);
            return new StructuredKnowledgePort.Output(List.of(new StructuredKnowledgePort.Claim("Supported synthetic result",List.of(evidence.getFirst().id()))),false);});
    }
    @AfterEach void clear(){
        try {if(owner!=null)cancelFixtureOperations(owner);}
        finally {SecurityContextHolder.clearContext();}
    }
    private void cancelFixtureOperations(UUID fixtureOwner){
        jdbc.update("update knowledge.knowledge_work_intent set state='cancelled',input_ciphertext=null,input_nonce=null,input_key_version=null,result_ciphertext=null,result_nonce=null,result_key_version=null,lease_owner=null,lease_token=null,lease_until=null,next_attempt_at=null,updated_at=greatest(updated_at,clock_timestamp()) where owner_user_id=? and work_class='private_knowledge_operation' and state in ('queued','claimed','retry_wait')",fixtureOwner);
    }

    @Test void fixtureCleanupNeverCancelsAnotherOwnersOperation() throws Exception {
        UUID other=account(),id=jdbc.queryForObject("select uuidv7()",UUID.class);
        Instant created=jdbc.queryForObject("select clock_timestamp()",java.sql.Timestamp.class).toInstant();
        var input=cipher.seal(new KnowledgeOperationMaterialCipher.Context(id,other,"deterministic_corpus",KnowledgeOperationMaterialCipher.Kind.INPUT,0),new byte[]{1});
        tx(()->{operationRows.insert(id,other,"deterministic_corpus",created,created.plusSeconds(3600),"{}",input);return null;});
        try {
            String handle=accept("all links");cancelFixtureOperations(owner);
            assertThat(poll(handle).get("status").asText()).isEqualTo("cancelled");
            assertThat(jdbc.queryForObject("select state from knowledge.knowledge_work_intent where knowledge_work_intent_id=?",String.class,id)).isEqualTo("queued");
        } finally {cancelFixtureOperations(other);}
    }

    @ParameterizedTest @ValueSource(strings={"neither","source-only","query-only","both"})
    void unpaidGoogleQueryAndSourceApprovalAreIndependentAtTheProviderBoundary(String approval) throws Exception {
        String query="find synthetic plans";
        when(configuration.provider()).thenReturn("gemini");when(configuration.tier()).thenReturn("unpaid");when(configuration.region()).thenReturn("global");
        String code="synthetic-"+UUID.randomUUID();when(policyConfiguration.code()).thenReturn(code);
        policy=disclosureFixtures.insert(jdbc,code,1,true);ack(owner);
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
        var lineage=EmbeddingLineage.create(configuration,new ProcessingPolicyService.AcknowledgedProcessingPolicy(policy,1,configuration.approvedPolicyFingerprint()),"image");
        var segment=new DerivedSegment(relevant.equals("media")?"Kyoto supported image":"UNRELATED_MEDIA_SYNTHETIC_SECRET_MARKER","whole_image","",null,null,null,null,null,null,null,null,null);
        tx(()->{representations.activate(source.expected(),lineage,List.of(segment),List.of(relevant.equals("media")?vector():orthogonal()));return null;});
        var t=json.readTree(query("what about Kyoto").andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        var captured=capturedEvidence();assertThat(captured).hasSize(1);assertThat(captured.getFirst().text()).startsWith("Kyoto supported").doesNotContain("SYNTHETIC_SECRET_MARKER");
        assertThat(captured.getFirst().modality()).isEqualTo(relevant.equals("media")?"image":"note");
        var citation=t.get("citations").get(0);if(relevant.equals("media"))assertThat(citation.get("attachmentId").asText()).isEqualTo(attachment.toString());else assertThat(citation.get("attachmentId").isNull()).isTrue();
    }
    @Test void soundtrackEvidenceRetrievesBothSavedOccurrencesWithOriginalVideoCitations()throws Exception {
        UUID first=note,second=note(owner,true,"Another saved clip","No soundtrack hint in the private note text");
        var expectedAttachments=new java.util.HashSet<String>();
        var lineage=EmbeddingLineage.create(configuration,new ProcessingPolicyService.AcknowledgedProcessingPolicy(policy,1,configuration.approvedPolicyFingerprint()),"video");
        for(UUID parent:List.of(first,second)){
            note=parent;UUID attachment=upload("video");expectedAttachments.add(attachment.toString());
            var source=sources.source(owner,parent,attachment);
            var scene=new DerivedSegment("Audio description (model inference, not spoken words): Possibly chattering calls over a bass rhythm","video_scene","Acoustic description",null,null,null,0.,.5,null,null,null,null);
            tx(()->{representations.activate(source.expected(),lineage,List.of(scene),List.of(vector()));return null;});
        }
        note=first;
        doAnswer(call->{
            KnowledgeQueryGate.EvidencePermit permit=call.getArgument(0);permit.requireDispatch();assertOutsideTransaction();
            List<StructuredKnowledgePort.Evidence> supplied=call.getArgument(3);
            return new StructuredKnowledgePort.Output(supplied.stream().map(e->new StructuredKnowledgePort.Claim(e.text(),List.of(e.id()))).toList(),false);
        }).when(provider).generate(any(),any(),any(),any());
        var result=json.readTree(query("find the clip with chattering sounds in the background").andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(capturedEvidence()).hasSize(2).allSatisfy(e->assertThat(e.modality()).isEqualTo("video"));
        var actualAttachments=new java.util.HashSet<String>();var actualNotes=new java.util.HashSet<String>();
        for(var citation:result.get("citations")){actualAttachments.add(citation.get("attachmentId").asText());actualNotes.add(citation.get("noteId").asText());}
        assertThat(actualAttachments).isEqualTo(expectedAttachments);assertThat(actualNotes).containsExactlyInAnyOrder(first.toString(),second.toString());
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.CsvSource({"16,video","60,video","16,audio","60,audio"})
    void repeatedTextMatchesCannotCrowdRelevantVideoAudioOutOfBoundedContext(int count,String modality)throws Exception {
        UUID original=note;
        for(int i=0;i<count;i++) {
            UUID text=note(owner,true,"Electronic background music bass reference "+i,"Video electronic background music repeated bass reference notes");
            derive(text,"Electronic background music repeated bass reference",vector());
        }
        note=original;UUID attachment=upload(modality);var source=sources.source(owner,note,attachment);
        var lineage=EmbeddingLineage.create(configuration,new ProcessingPolicyService.AcknowledgedProcessingPolicy(policy,1,configuration.approvedPolicyFingerprint()),modality);
        String kind=modality.equals("video")?"video_scene":"transcript";
        var sound=new DerivedSegment("Audio description (model inference, not spoken words): Repeated electronic bass music",kind,"Acoustic description",null,null,null,0.,.5,null,null,null,null);
        var foreground=new DerivedSegment("A fictional foreground observation",kind,modality.equals("video")?"Visual description":"Transcript",null,null,null,0.,.5,null,null,null,null);
        tx(()->{representations.activate(source.expected(),lineage,List.of(foreground,sound),List.of(vector(),new float[]{.9f,.1f,0,0,0,0,0,0}));return null;});
        query("Find a "+modality+" with electronic background music and repeated bass").andExpect(status().isOk());
        assertThat(capturedEvidence()).hasSizeLessThanOrEqualTo(12)
            .anySatisfy(e->{assertThat(e.modality()).isEqualTo(modality);assertThat(e.text()).startsWith("Audio description");});
    }
    @Test void overlappingSemanticAndLexicalCandidatesDoNotExpandContext() throws Exception {
        jdbc.update("update notes.note set title='Kyoto plans' where note_id=?",note);
        deriveChunks(List.of(DerivedSegment.note("Kyoto supported overlapping evidence one","",0,100),DerivedSegment.note("Kyoto supported overlapping evidence two","",5,105)),List.of(vector(),vector()));
        query("find Kyoto").andExpect(status().isOk());assertThat(capturedEvidence()).hasSize(1);
    }
    @ParameterizedTest @ValueSource(strings={"pdf","audio","video","image"})
    void repeatedMediaFactsKeepTheirDistinctLocationsThroughGenerationAndHttp(String modality)throws Exception {
        UUID attachment=upload(modality);var source=sources.source(owner,note,attachment);
        var lineage=EmbeddingLineage.create(configuration,new ProcessingPolicyService.AcknowledgedProcessingPolicy(policy,1,configuration.approvedPolicyFingerprint()),modality);
        var segments=new ArrayList<DerivedSegment>();
        for(int i=0;i<2;i++)segments.add(switch(modality){
            case "pdf"->new DerivedSegment("Kyoto repeated fact","pdf_text","",null,null,i+1,null,null,null,null,null,null);
            case "audio"->new DerivedSegment("Kyoto repeated fact","transcript","",null,null,null,(double)i,i+.5,null,null,null,null);
            case "video"->new DerivedSegment("Kyoto repeated fact","video_scene","",null,null,null,(double)i,i+.5,null,null,null,null);
            default->new DerivedSegment("Kyoto repeated fact","image_region","",null,null,null,null,null,.1+i*.5,.1,.2,.2);
        });
        tx(()->{representations.activate(source.expected(),lineage,segments,List.of(vector(),vector()));return null;});
        doAnswer(call->{
            KnowledgeQueryGate.EvidencePermit permit=call.getArgument(0);permit.requireDispatch();assertOutsideTransaction();
            List<StructuredKnowledgePort.Evidence> supplied=call.getArgument(3);
            return new StructuredKnowledgePort.Output(supplied.stream().map(e->new StructuredKnowledgePort.Claim(e.text(),List.of(e.id()))).toList(),false);
        }).when(provider).generate(any(),any(),any(),any());
        var result=json.readTree(query("what about Kyoto").andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(capturedEvidence()).hasSize(2).extracting(StructuredKnowledgePort.Evidence::location)
            .containsExactlyInAnyOrderElementsOf(segments.stream().map(KnowledgeQueryEngine::location).toList());
        assertThat(result.get("citations").size()).isEqualTo(2);
        for(var citation:result.get("citations")){
            assertThat(citation.get("noteId").asText()).isEqualTo(note.toString());
            assertThat(citation.get("attachmentId").asText()).isEqualTo(attachment.toString());
        }
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

    private UUID directAudioFixture(String query) throws Exception {
        return directMediaFixture(query,"audio");
    }
    private UUID directMediaFixture(String query,String modality) throws Exception {
        when(configuration.directAudioSearch()).thenReturn(true);when(configuration.provider()).thenReturn("gemini");
        when(configuration.directVideoSoundtrackSearch()).thenReturn(modality.equals("video"));
        when(configuration.embeddingModel()).thenReturn("gemini-embedding-2");when(configuration.mediaModel()).thenReturn("gemini-3.5-flash-lite");
        when(configuration.region()).thenReturn("global");when(configuration.tier()).thenReturn("unpaid");
        String code="synthetic-direct-audio-"+UUID.randomUUID();when(policyConfiguration.code()).thenReturn(code);
        policy=disclosureFixtures.insert(jdbc,code,1,true);ack(owner);
        when(dispatch.dispatchPolicy()).thenReturn("unpaid-synthetic-demo");when(audioSignals.available()).thenReturn(true);
        UUID attachment=upload(modality);var source=sources.source(owner,note,attachment);
        when(dispatch.approvedSourceFingerprints()).thenReturn(List.of(ProviderDispatchPolicy.fingerprint(source.expected())));
        when(dispatch.approvedQueryFingerprints()).thenReturn(List.of(ProviderDispatchPolicy.queryFingerprint(query)));
        var lineage=EmbeddingLineage.create(configuration,new ProcessingPolicyService.AcknowledgedProcessingPolicy(policy,1,configuration.approvedPolicyFingerprint()),modality);
        tx(()->{representations.activate(source.expected(),lineage,List.of(new DerivedSegment("Generic synthetic music",modality.equals("video")?"video_scene":"transcript","Acoustic description",null,null,null,0.,1.,null,null,null,null)),List.of(vector()));return null;});
        when(audioSignals.embed(any(),eq(query),any())).thenAnswer(call->{((KnowledgeQueryGate.EvidencePermit)call.getArgument(0)).requireGoogleAudio(query);assertOutsideTransaction();return vector();});
        doAnswer(call->{((KnowledgeQueryGate.EvidencePermit)call.getArgument(0)).requireGoogle(query);assertOutsideTransaction();
            @SuppressWarnings("unchecked") var items=(List<StructuredKnowledgePort.Evidence>)call.getArgument(3);
            return new StructuredKnowledgePort.Output(List.of(new StructuredKnowledgePort.Claim(items.getFirst().text(),List.of(items.getFirst().id()))),false);
        }).when(provider).generate(any(),any(),any(),any());
        return attachment;
    }
    @ParameterizedTest @ValueSource(strings={"supported","contradicted","uncertain"})
    void directAudioRequiresActualSupportedVerificationRatherThanItsSimilarity(String decision) throws Exception {
        String question="find synthetic audio";UUID attachment=directAudioFixture(question);
        when(audioSignals.verify(any(),eq(question),any(),eq(1d))).thenAnswer(call->{((KnowledgeQueryGate.EvidencePermit)call.getArgument(0)).requireGoogleAudio(question);assertOutsideTransaction();return new AudioSignalPort.Verification(decision,"Possibly synthetic chattering-like sounds",100,900);});
        var result=json.readTree(query(question).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        verify(audioSignals,times(1)).embed(any(),eq(question),any());verify(audioSignals,times(1)).verify(any(),eq(question),any(),eq(1d));
        if(decision.equals("supported")) {
            assertThat(result.get("aiAnswer").toString()).contains("chattering-like");
            assertThat(result.get("citations").get(0).get("attachmentId").asText()).isEqualTo(attachment.toString());
            assertThat(result.get("citations").get(0).get("location").get("timeStart").asDouble()).isEqualTo(.1);
            assertThat(result.get("citations").get(0).get("location").get("timeEnd").asDouble()).isEqualTo(.9);
        }else{assertThat(result.get("aiAnswer").isNull()).isTrue();assertThat(result.get("citations").size()).isZero();verify(provider,never()).generate(any(),any(),any(),any());}
    }
    @ParameterizedTest @ValueSource(strings={"aiOff","stale","foreign"})
    void directAudioNeverReadsOrDispatchesExcludedSources(String exclusion)throws Exception {
        String question="find synthetic audio";directAudioFixture(question);
        if(exclusion.equals("aiOff"))jdbc.update("update notes.note set ai_enabled=false,ai_generation=ai_generation+1 where note_id=?",note);
        else if(exclusion.equals("stale"))jdbc.update("update notes.note set ai_generation=ai_generation+1 where note_id=?",note);
        else {UUID other=account();ack(other);browser=browser(other);}
        query(question).andExpect(status().isOk());verify(audioSignals,never()).embed(any(),any(),any());verify(audioSignals,never()).verify(any(),any(),any(),anyDouble());
    }
    @Test void directAudioProviderFailureCannotFallBackToAnUnverifiedAcousticClaim()throws Exception {
        String question="find synthetic audio";directAudioFixture(question);
        when(audioSignals.embed(any(),eq(question),any())).thenThrow(new DerivationFailure(KnowledgeWork.Failure.QUOTA));
        var result=json.readTree(query(question).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(result.get("degraded").asText()).isEqualTo("providerUnavailable");assertThat(result.get("aiAnswer").isNull()).isTrue();
        verify(audioSignals,never()).verify(any(),any(),any(),anyDouble());verify(provider,never()).generate(any(),any(),any(),any());
    }
    @Test void directAudioRevalidatesAfterNativeInspectionBeforeVerificationOrAnswer()throws Exception {
        String question="find synthetic audio";directAudioFixture(question);var checks=new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call->checks.incrementAndGet()<2&&((Boolean)call.callRealMethod())).when(evidenceRows).current(any(),any());
        var result=json.readTree(query(question).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(result.get("degraded").asText()).isEqualTo("sourceChanged");assertThat(result.get("aiAnswer").isNull()).isTrue();
        verify(audioSignals,times(1)).embed(any(),eq(question),any());verify(audioSignals,never()).verify(any(),any(),any(),anyDouble());verify(provider,never()).generate(any(),any(),any(),any());
        var bytes=org.mockito.ArgumentCaptor.forClass(byte[].class);verify(audioSignals).embed(any(),eq(question),bytes.capture());assertThat(bytes.getValue()).containsOnly((byte)0);
    }
    @Test void directAudioBoundsInspectedSourcesAndDoesNotExpandItsEvidenceWindow()throws Exception {
        String question="find synthetic audio";UUID first=directAudioFixture(question);var fingerprints=new ArrayList<>(dispatch.approvedSourceFingerprints());
        var lineage=EmbeddingLineage.create(configuration,new ProcessingPolicyService.AcknowledgedProcessingPolicy(policy,1,configuration.approvedPolicyFingerprint()),"audio");
        for(int i=0;i<3;i++){
            UUID attachment=upload("audio");var source=sources.source(owner,note,attachment);fingerprints.add(ProviderDispatchPolicy.fingerprint(source.expected()));
            tx(()->{representations.activate(source.expected(),lineage,List.of(new DerivedSegment("Generic synthetic music","transcript","Acoustic description",null,null,null,0.,1.,null,null,null,null)),List.of(vector()));return null;});
        }
        when(dispatch.approvedSourceFingerprints()).thenReturn(fingerprints);
        when(audioSignals.verify(any(),eq(question),any(),eq(1d))).thenAnswer(call->{((KnowledgeQueryGate.EvidencePermit)call.getArgument(0)).requireGoogleAudio(question);assertOutsideTransaction();return new AudioSignalPort.Verification("supported","Synthetic matching sounds",0,1000);});
        query(question).andExpect(status().isOk());verify(audioSignals,times(3)).embed(any(),eq(question),any());verify(audioSignals,times(3)).verify(any(),eq(question),any(),eq(1d));assertThat(capturedEvidence()).hasSize(3);
    }
    @ParameterizedTest @ValueSource(strings={"supported","contradicted","uncertain"})
    void mp4SoundtrackRequiresSupportedObservationAndKeepsOriginalVideoCitation(String decision)throws Exception {
        String question="find synthetic video with background noise";UUID attachment=directMediaFixture(question,"video");
        when(audioSignals.verifyVideo(any(),eq(question),any(),eq(1d))).thenAnswer(call->{((KnowledgeQueryGate.EvidencePermit)call.getArgument(0)).requireGoogleVideo(question);assertOutsideTransaction();return new AudioSignalPort.Verification(decision,"Possibly synthetic chattering-like sounds",100,900);});
        var result=json.readTree(query(question).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        verify(audioSignals,never()).embed(any(),any(),any());verify(audioSignals,never()).verify(any(),any(),any(),anyDouble());verify(audioSignals,times(1)).verifyVideo(any(),eq(question),any(),eq(1d));
        if(decision.equals("supported")) {
            assertThat(result.get("aiAnswer").toString()).contains("chattering-like");var citation=result.get("citations").get(0);
            assertThat(citation.get("attachmentId").asText()).isEqualTo(attachment.toString());assertThat(citation.get("location").get("kind").asText()).isEqualTo("video_scene");
            assertThat(citation.get("location").get("timeStart").asDouble()).isEqualTo(.1);assertThat(citation.get("location").get("timeEnd").asDouble()).isEqualTo(.9);
        }else {assertThat(result.get("aiAnswer").isNull()).isTrue();assertThat(result.get("citations").size()).isZero();verify(provider,never()).generate(any(),any(),any(),any());}
    }
    @Test void soundtrackInspectionPrecedesAnswerContextTruncationAndStillHonorsThreeSourceLimit()throws Exception {
        String question="find synthetic video with background noise";UUID first=directMediaFixture(question,"video");
        var lineage=EmbeddingLineage.create(configuration,new ProcessingPolicyService.AcknowledgedProcessingPolicy(policy,1,configuration.approvedPolicyFingerprint()),"video");
        var firstSource=sources.source(owner,note,first);var segments=new ArrayList<DerivedSegment>();var embeddings=new ArrayList<float[]>();
        for(int i=0;i<12;i++){
            segments.add(new DerivedSegment("Generic early synthetic sound "+i,"video_scene","Acoustic description",null,null,null,0.,1.,null,null,null,null));embeddings.add(vector());
        }
        tx(()->{representations.activate(firstSource.expected(),lineage,segments,embeddings);return null;});
        var fingerprints=new ArrayList<>(dispatch.approvedSourceFingerprints());var attachments=new ArrayList<UUID>();attachments.add(first);
        for(int i=1;i<4;i++){
            UUID id=upload("video");attachments.add(id);var source=sources.source(owner,note,id);fingerprints.add(ProviderDispatchPolicy.fingerprint(source.expected()));
            float[] embedding=new float[]{1,i*.2f,0,0,0,0,0,0};
            tx(()->{representations.activate(source.expected(),lineage,List.of(new DerivedSegment("Generic later synthetic sound","video_scene","Acoustic description",null,null,null,0.,1.,null,null,null,null)),List.of(embedding));return null;});
        }
        when(dispatch.approvedSourceFingerprints()).thenReturn(fingerprints);var inspections=new java.util.concurrent.atomic.AtomicInteger();
        when(audioSignals.verifyVideo(any(),eq(question),any(),eq(1d))).thenAnswer(call->{
            ((KnowledgeQueryGate.EvidencePermit)call.getArgument(0)).requireGoogleVideo(question);assertOutsideTransaction();
            return new AudioSignalPort.Verification(inspections.incrementAndGet()==3?"supported":"contradicted","Synthetic matching chimes",100,900);
        });
        var result=json.readTree(query(question).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        verify(audioSignals,times(3)).verifyVideo(any(),eq(question),any(),eq(1d));verify(audioSignals,never()).embed(any(),any(),any());
        assertThat(capturedEvidence()).hasSize(1);assertThat(capturedEvidence().getFirst().text()).contains("matching chimes");
        assertThat(result.get("citations").size()).isEqualTo(1);assertThat(result.get("citations").get(0).get("attachmentId").asText()).isEqualTo(attachments.get(2).toString());
    }
    @Test void explicitVideoSoundQuestionCannotBeAnsweredByStandaloneWav()throws Exception {
        String question="find synthetic video with background noise";UUID video=directMediaFixture(question,"video");
        UUID wav=upload("audio");var audio=sources.source(owner,note,wav);var fingerprints=new ArrayList<>(dispatch.approvedSourceFingerprints());fingerprints.add(ProviderDispatchPolicy.fingerprint(audio.expected()));when(dispatch.approvedSourceFingerprints()).thenReturn(fingerprints);
        var lineage=EmbeddingLineage.create(configuration,new ProcessingPolicyService.AcknowledgedProcessingPolicy(policy,1,configuration.approvedPolicyFingerprint()),"audio");
        tx(()->{representations.activate(audio.expected(),lineage,List.of(new DerivedSegment("Synthetic matching music","transcript","Acoustic description",null,null,null,0.,1.,null,null,null,null)),List.of(vector()));return null;});
        when(audioSignals.verifyVideo(any(),eq(question),any(),eq(1d))).thenReturn(new AudioSignalPort.Verification("supported","Synthetic matching soundtrack",0,1000));
        var result=json.readTree(query(question).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        verify(audioSignals,never()).embed(any(),any(),any());verify(audioSignals,never()).verify(any(),any(),any(),anyDouble());
        assertThat(capturedEvidence()).allSatisfy(e->assertThat(e.modality()).isEqualTo("video"));
        assertThat(result.get("citations").get(0).get("attachmentId").asText()).isEqualTo(video.toString());
    }
    @Test void mp4AudioVerificationPreservesGroundedVisualEvidenceWithinTheSameContextCaps()throws Exception {
        String question="find synthetic video with a blue poster and soft background noise";UUID id=directMediaFixture(question,"video");var source=sources.source(owner,note,id);
        var lineage=EmbeddingLineage.create(configuration,new ProcessingPolicyService.AcknowledgedProcessingPolicy(policy,1,configuration.approvedPolicyFingerprint()),"video");
        var visual=new DerivedSegment("A blue rectangular poster is visible","video_scene","Visual description",null,null,null,0.,1.,null,null,null,null);
        var acoustic=new DerivedSegment("Generic synthetic music","video_scene","Acoustic description",null,null,null,0.,1.,null,null,null,null);
        tx(()->{representations.activate(source.expected(),lineage,List.of(visual,acoustic),List.of(vector(),vector()));return null;});
        when(audioSignals.verifyVideo(any(),eq(question),any(),eq(1d))).thenReturn(new AudioSignalPort.Verification("supported","Soft synthetic chimes",100,900));
        query(question).andExpect(status().isOk());var supplied=capturedEvidence();assertThat(supplied).hasSize(2);
        assertThat(supplied).extracting(StructuredKnowledgePort.Evidence::text).anyMatch(text->text.contains("blue rectangular poster")).anyMatch(text->text.contains("Soft synthetic chimes")).noneMatch(text->text.contains("Generic synthetic music"));
        assertThat(supplied.stream().mapToInt(e->e.text().length()).sum()).isLessThanOrEqualTo(KnowledgeQueryLimits.CHARACTERS);
        verify(audioSignals,times(1)).verifyVideo(any(),eq(question),any(),eq(1d));verify(audioSignals,never()).embed(any(),any(),any());
    }
    @ParameterizedTest @ValueSource(strings={"aiOff","stale","foreign"})
    void mp4SoundtrackNeverDispatchesExcludedSources(String exclusion)throws Exception {
        String question="find synthetic video with background noise";directMediaFixture(question,"video");
        if(exclusion.equals("aiOff"))jdbc.update("update notes.note set ai_enabled=false,ai_generation=ai_generation+1 where note_id=?",note);
        else if(exclusion.equals("stale"))jdbc.update("update notes.note set ai_generation=ai_generation+1 where note_id=?",note);
        else {UUID other=account();ack(other);browser=browser(other);}
        query(question).andExpect(status().isOk());verify(audioSignals,never()).embed(any(),any(),any());verify(audioSignals,never()).verifyVideo(any(),any(),any(),anyDouble());
    }
    @Test void mp4SoundtrackPostEffectDenialCannotBecomeAnAnswer()throws Exception {
        String question="find synthetic video with background noise";directMediaFixture(question,"video");var checks=new java.util.concurrent.atomic.AtomicInteger();
        when(audioSignals.verifyVideo(any(),eq(question),any(),eq(1d))).thenAnswer(call->{((KnowledgeQueryGate.EvidencePermit)call.getArgument(0)).requireGoogleVideo(question);assertOutsideTransaction();return new AudioSignalPort.Verification("supported","Synthetic soundtrack",0,1000);});
        doAnswer(call->checks.incrementAndGet()<2&&((Boolean)call.callRealMethod())).when(evidenceRows).current(any(),any());
        var result=json.readTree(query(question).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());assertThat(result.get("degraded").asText()).isEqualTo("sourceChanged");assertThat(result.get("aiAnswer").isNull()).isTrue();
        verify(provider,never()).generate(any(),any(),any(),any());var bytes=org.mockito.ArgumentCaptor.forClass(byte[].class);verify(audioSignals).verifyVideo(any(),eq(question),bytes.capture(),eq(1d));assertThat(bytes.getValue()).containsOnly((byte)0);
    }
    @Test void wavAndMp4ShareOneThreeSourceBudget()throws Exception {
        String question="find synthetic background music";directAudioFixture(question);when(configuration.directVideoSoundtrackSearch()).thenReturn(true);
        var fingerprints=new ArrayList<>(dispatch.approvedSourceFingerprints());var lineage=EmbeddingLineage.create(configuration,new ProcessingPolicyService.AcknowledgedProcessingPolicy(policy,1,configuration.approvedPolicyFingerprint()),"video");
        for(int i=0;i<3;i++){UUID id=upload("video");var source=sources.source(owner,note,id);fingerprints.add(ProviderDispatchPolicy.fingerprint(source.expected()));
            tx(()->{representations.activate(source.expected(),lineage,List.of(new DerivedSegment("Synthetic background music","video_scene","Acoustic description",null,null,null,0.,1.,null,null,null,null)),List.of(vector()));return null;});}
        when(dispatch.approvedSourceFingerprints()).thenReturn(fingerprints);
        when(audioSignals.verify(any(),eq(question),any(),eq(1d))).thenAnswer(call->{((KnowledgeQueryGate.EvidencePermit)call.getArgument(0)).requireGoogleAudio(question);assertOutsideTransaction();return new AudioSignalPort.Verification("supported","Synthetic music",0,1000);});
        when(audioSignals.verifyVideo(any(),eq(question),any(),eq(1d))).thenAnswer(call->{((KnowledgeQueryGate.EvidencePermit)call.getArgument(0)).requireGoogleVideo(question);assertOutsideTransaction();return new AudioSignalPort.Verification("supported","Synthetic music",0,1000);});
        query(question).andExpect(status().isOk());verify(audioSignals,times(1)).embed(any(),eq(question),any());verify(audioSignals,times(1)).verify(any(),eq(question),any(),eq(1d));verify(audioSignals,times(2)).verifyVideo(any(),eq(question),any(),eq(1d));assertThat(capturedEvidence()).hasSize(3);
    }
    @Test void mp4HistoryBindsQueryRecipeWithoutInvalidatingTheVideoIndex()throws Exception {
        String question="find synthetic video with background noise";UUID id=directMediaFixture(question,"video");var source=sources.source(owner,note,id);
        var index=EmbeddingLineage.create(configuration,new ProcessingPolicyService.AcknowledgedProcessingPolicy(policy,1,configuration.approvedPolicyFingerprint()),"video");
        when(audioSignals.verifyVideo(any(),eq(question),any(),eq(1d))).thenReturn(new AudioSignalPort.Verification("supported","Synthetic background sounds",0,1000));
        var response=query(question).andExpect(status().isOk()).andReturn().getResponse();String location=response.getHeader("Content-Location");assertThat(location).startsWith("/api/knowledge/history/");
        var before=json.readTree(mvc.perform(get(location).cookie(browser.cookie)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());assertThat(before.get("status").asText()).isEqualTo("completed");
        when(configuration.directVideoSoundtrackSearch()).thenReturn(false);
        assertThat(EmbeddingLineage.create(configuration,new ProcessingPolicyService.AcknowledgedProcessingPolicy(policy,1,configuration.approvedPolicyFingerprint()),"video")).isEqualTo(index);assertThat(tx(()->representations.ready(source.expected(),index))).isTrue();
        var after=json.readTree(mvc.perform(get(location).cookie(browser.cookie)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());assertThat(after.get("status").asText()).isEqualTo("obsolete");assertThat(after.get("result").isNull()).isTrue();
    }

    @Test void controlledZephyrScenarioReportsEachGroundingStageAndUnequalEvidenceSurvives() throws Exception {
        String fact="The fictional Zephyr observatory is located on the northern ridge of planet Aster. Its telescope dome is silver.";
        jdbc.update("update notes.note set title='Zephyr observatory',markdown=? where note_id=?",fact,note);
        derive(note,fact,new float[]{.8f,.6f,0,0,0,0,0,0});
        UUID second=note(owner,true,"Synthetic telescope log","The Zephyr observatory telescope dome is silver.");
        derive(second,"The Zephyr observatory telescope dome is silver.",new float[]{.6f,.8f,0,0,0,0,0,0});
        doAnswer(call->{
            KnowledgeQueryGate.EvidencePermit permit=call.getArgument(0);permit.requireDispatch();assertOutsideTransaction();
            List<StructuredKnowledgePort.Evidence> e=call.getArgument(3);
            assertThat(e).hasSize(2);
            return new StructuredKnowledgePort.Output(List.of(new StructuredKnowledgePort.Claim("Northern ridge of planet Aster; silver dome",e.stream().map(StructuredKnowledgePort.Evidence::id).toList())),false);
        }).when(provider).generate(any(),any(),eq("answer"),any());
        var candidates=vectors.search(owner,"note",vector(),20);
        assertThat(candidates).hasSize(2);assertThat(candidates.getFirst().expected().noteId()).isEqualTo(note);
        var result=json.readTree(query("Where is the synthetic Zephyr observatory located?").andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(result.get("degraded").isNull()).isTrue();assertThat(result.get("aiAnswer").toString()).contains("Aster");
        assertThat(result.get("citations").size()).isEqualTo(2);
        assertThat(result.get("citations").get(0).get("noteId").asText()).isEqualTo(note.toString());
        assertThat(result.get("citations").get(0).get("location").get("kind").asText()).isEqualTo("note_text");
        writeEvaluation("controlled-grounding",Map.of("queryEmbeddingAvailable",true,
            "compatibleLineage",true,"correctVectorCandidate",true,"correctRank",true,"currentEvidenceSelected",2,
            "validStructuredGeneration",true,"answerPresent",true,"expectedFactSupported",true,"correctCitationAndLocation",true,
            "postGenerationAuthorization",true));
    }

    @Test void longSemanticSourceCheckpointsAllSegmentsWithoutExceedingThirtyTwoPerDispatch() throws Exception {
        var chunks=new ArrayList<DerivedSegment>();var embeddings=new ArrayList<float[]>();
        var body=new StringBuilder();
        for(int i=0;i<69;i++){String text="Frozen film "+i;int offset=body.length();body.append(text).append('\n');chunks.add(DerivedSegment.note(text,"",offset,offset+text.length()));embeddings.add(vector());}
        jdbc.update("update notes.note set markdown=? where note_id=?",body.toString(),note);
        deriveChunks(chunks,embeddings);
        var visited=new HashSet<String>();
        doAnswer(call->{
            KnowledgeQueryGate.EvidencePermit permit=call.getArgument(0);permit.requireDispatch();assertOutsideTransaction();
            List<StructuredKnowledgePort.Evidence> e=call.getArgument(3);assertThat(e.size()).isBetween(1,32);
            e.forEach(item->assertThat(visited.add(item.text())).isTrue());
            return new StructuredKnowledgePort.Output(e.stream().map(item->new StructuredKnowledgePort.Claim(item.text(),List.of(item.id()))).toList(),false);
        }).when(provider).generate(any(),any(),eq("extract"),any());
        String handle=accept("list all films");operations.execute(claim());var r=poll(handle).get("result");
        assertThat(visited).hasSize(69);assertThat(r.get("aiAnswer").get("claims").size()).isEqualTo(69);
        assertThat(r.get("citations").size()).isEqualTo(69);assertThat(r.get("coverage").get("completed").asBoolean()).isTrue();
        assertThat(r.get("coverage").get("inspectedSources").asLong()).isEqualTo(1);
        verify(provider,times(3)).generate(any(),any(),eq("extract"),any());
        var actual=new HashSet<String>();r.get("aiAnswer").get("claims").forEach(value->actual.add(value.asText()));
        assertFrozenSetQuality(actual,new HashSet<>(chunks.stream().map(DerivedSegment::text).toList()));
        writeEvaluation("long-semantic-source",Map.of("segments",69,"dispatches",3,"maximumSegmentsPerDispatch",32,
            "extractionPrecision",1.0,"extractionRecall",1.0,"correctLocationCitations",69,"inspectedSources",1,
            "coverageComplete",true,"provider","controlled-double-NOT-Gemini-quality"));
    }

    @Test void thousandAndFirstSameModalityRootIsRankedRatherThanRejectedOrSilentlyOmitted() {
        FrozenScaleCorpus.text(jdbc,owner,1000,configuration,policy);
        UUID last=jdbc.queryForObject("select note_id from notes.note where owner_user_id=? order by note_id desc limit 1",UUID.class,owner);
        derive(last,"Frozen target beyond one thousand roots");
        assertThat(jdbc.queryForObject("select count(*) from knowledge.private_derived_representation where owner_user_id=? and state='ready'",Integer.class,owner)).isEqualTo(1001);
        var ranked=vectors.search(owner,"note",vector(),20);assertThat(ranked).hasSize(20);
        assertThat(ranked.getFirst().expected().noteId()).isEqualTo(last);assertThat(ranked.getFirst().distance()).isZero();
    }

    @Test void semanticContinuationHonorsCharacterBudgetWithoutSkippingRemainingSegments() throws Exception {
        var chunks=new ArrayList<DerivedSegment>();var embeddings=new ArrayList<float[]>();
        var body=new StringBuilder();
        for(int i=0;i<13;i++){String text="Frozen section "+i+" "+"x".repeat(5980);int offset=body.length();body.append(text).append('\n');chunks.add(DerivedSegment.note(text,"",offset,offset+text.length()));embeddings.add(vector());}
        jdbc.update("update notes.note set markdown=? where note_id=?",body.toString(),note);
        deriveChunks(chunks,embeddings);var visited=new HashSet<String>();
        doAnswer(call->{
            KnowledgeQueryGate.EvidencePermit permit=call.getArgument(0);permit.requireDispatch();assertOutsideTransaction();
            List<StructuredKnowledgePort.Evidence> e=call.getArgument(3);
            assertThat(e.size()).isBetween(1,12);assertThat(e.stream().mapToInt(item->item.text().length()).sum()).isLessThanOrEqualTo(48000);
            e.forEach(item->assertThat(visited.add(item.text())).isTrue());
            return new StructuredKnowledgePort.Output(e.stream().map(item->new StructuredKnowledgePort.Claim("Section "+visited.size()+" "+item.id(),List.of(item.id()))).toList(),false);
        }).when(provider).generate(any(),any(),eq("extract"),any());
        String handle=accept("list all films");operations.execute(claim());var result=poll(handle).get("result");
        assertThat(visited).hasSize(13);assertThat(result.get("coverage").get("completed").asBoolean()).isTrue();
        assertThat(result.get("citations").size()).isEqualTo(13);verify(provider,times(2)).generate(any(),any(),eq("extract"),any());
    }

    @Test void rankedEvidencePacksCharacterBudgetInsteadOfLosingEveryUsefulMediaResult() throws Exception {
        var lineage=EmbeddingLineage.create(configuration,new ProcessingPolicyService.AcknowledgedProcessingPolicy(policy,1,configuration.approvedPolicyFingerprint()),"image");
        for(int i=0;i<5;i++){
            UUID attachment=upload("image");var source=sources.source(owner,note,attachment);
            var segment=new DerivedSegment("Frozen image "+i+" "+"x".repeat(11970),"whole_image","",null,null,null,null,null,null,null,null,null);
            tx(()->{representations.activate(source.expected(),lineage,List.of(segment),List.of(vector()));return null;});
        }
        doAnswer(call->{
            KnowledgeQueryGate.EvidencePermit permit=call.getArgument(0);permit.requireDispatch();assertOutsideTransaction();
            List<StructuredKnowledgePort.Evidence> e=call.getArgument(3);assertThat(e).hasSize(4);
            assertThat(e.stream().mapToInt(item->item.text().length()).sum()).isLessThanOrEqualTo(48000);
            return new StructuredKnowledgePort.Output(List.of(new StructuredKnowledgePort.Claim("Supported synthetic image fact",e.stream().map(StructuredKnowledgePort.Evidence::id).toList())),false);
        }).when(provider).generate(any(),any(),eq("answer"),any());
        var result=json.readTree(query("Find the synthetic image fact").andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(result.get("degraded").isNull()).isTrue();assertThat(result.get("aiAnswer").isNull()).isFalse();
        assertThat(result.get("citations").size()).isEqualTo(4);assertThat(result.get("coverage").get("completed").asBoolean()).isFalse();
    }

    @ParameterizedTest @ValueSource(ints={1001,5000,10000})
    void exhaustiveSparseUrlCorpusMakesDurableProgressBeyondOneThousand(int count) throws Exception {
        jdbc.update("update notes.note set markdown='https://example.test/frozen-first' where note_id=?",note);
        FrozenScaleCorpus.notes(jdbc,owner,count-1);
        UUID last=jdbc.queryForObject("select note_id from notes.note where owner_user_id=? order by note_id desc limit 1",UUID.class,owner);
        jdbc.update("update notes.note set markdown='https://example.test/frozen-last',ai_enabled=false where note_id=?",last);
        String handle=accept("list every URL");int slices=0;long previous=0;var versions=new ArrayList<Long>();
        while(true) {
            var row=claim();assertThat(row.version()).isGreaterThanOrEqualTo(previous);operations.execute(row);
            var after=tx(()->operationRows.owner(owner,row.id()).orElseThrow());versions.add(after.version());
            assertThat(after.version()).isGreaterThan(previous);previous=after.version();
            assertThat(jdbc.queryForObject("select attempt_count from knowledge.knowledge_work_intent where knowledge_work_intent_id=?",Integer.class,row.id())).isEqualTo(1);
            if("completed".equals(after.state()))break;
            assertThat(after.state()).isEqualTo("queued");assertThat(after.lease()).isNull();assertThat(++slices).isLessThan(100);
            assertThat(tx(()->operationRows.current(row))).isFalse();
        }
        var r=poll(handle).get("result");assertThat(r.get("coverage").get("completed").asBoolean()).isTrue();
        assertThat(r.get("coverage").get("inspectedSources").asLong()).isEqualTo(count);
        assertThat(r.get("coverage").get("truncated").asBoolean()).isFalse();assertThat(r.get("deterministicResults").size()).isEqualTo(2);
        verifyNoInteractions(provider);
        writeEvaluation("durable-"+count,Map.of("sources",count,"slices",slices+1,"checkpointVersions",versions,"coverageComplete",true,"supportedUrlPrecision",1.0,"supportedUrlRecall",1.0,"providerRequests",0));
    }

    @Test void realWidthSyntheticVectorsMeasureExactOwnerScopedLoad() throws Exception {
        when(configuration.dimension()).thenReturn(768);
        float[] query=new float[768];var seeded=new Random(20261010L);
        for(int i=0;i<query.length;i++)query[i]=seeded.nextFloat()*2-1;
        // Dense seeded vectors exercise real storage/distance width, not real-model relevance.
        FrozenScaleCorpus.text(jdbc,owner,4999,configuration,policy,query);
        UUID foreign=account();ack(foreign);FrozenScaleCorpus.text(jdbc,foreign,40,configuration,policy,query);
        assertThat(jdbc.queryForObject("select count(*) from knowledge.private_derived_segment where owner_user_id=? and vector_dims(embedding)=768",Integer.class,owner)).isEqualTo(5000);
        var millis=new ArrayList<Double>();
        for(int run=0;run<5;run++) {
            long started=System.nanoTime();var found=vectors.search(owner,"note",query,20);millis.add((System.nanoTime()-started)/1e6);
            assertThat(found).hasSize(20).allSatisfy(candidate->assertThat(candidate.expected().owner()).isEqualTo(owner));
            assertThat(found.stream().map(c->c.expected().noteId()).distinct().count()).isEqualTo(20);
        }
        millis.sort(Double::compareTo);
        writeEvaluation("real-width-768",Map.of("seed","real-width-v1-20261010","dimension",768,"ownerSources",5000,"foreignSources",40,
            "samplesMillis",millis,"p50Millis",millis.get(2),"p95Millis",millis.get(4),"vectorKind","dense-seeded-synthetic-NOT-Gemini-semantic-quality",
            "crossOwnerCandidates",0,"providerRequests",0));
        verifyNoInteractions(provider);
    }

    @ParameterizedTest @ValueSource(ints={100,1001,5000,10000})
    void frozenMixedScaleCorpusRanksHeldOutSourcesWithBoundedCurrentBatches(int count) throws Exception {
        var osBean=java.lang.management.ManagementFactory.getOperatingSystemMXBean();
        long cpuBefore=osBean instanceof com.sun.management.OperatingSystemMXBean os?os.getProcessCpuTime():-1;
        long start=System.nanoTime();FrozenScaleCorpus.mixed(jdbc,owner,count-1,configuration,policy);
        UUID other=account();ack(other);FrozenScaleCorpus.mixed(jdbc,other,40,configuration,policy);
        double fixtureMillis=(System.nanoTime()-start)/1e6;
        writeScalePlan(count);
        var measurements=new ArrayList<Map<String,Object>>();
        for(String modality:List.of("note","image","audio","video","pdf")) {
            var targets=jdbc.queryForList("""
                select r.source_note_id,r.source_attachment_id from knowledge.private_derived_representation r
                join notes.note n on n.note_id=r.source_note_id and n.owner_user_id=r.owner_user_id
                where r.owner_user_id=? and r.modality=? and r.state='ready' and n.ai_enabled
                    and n.lifecycle_state in ('active','archived') and n.ai_generation=r.processing_generation
                    and (r.source_attachment_id is not null or n.revision=r.source_revision)
                order by r.derived_representation_id desc limit 4
                """,owner,modality);
            for(int i=0;i<targets.size();i++) {
                var target=targets.get(i);UUID n=(UUID)target.get("source_note_id"),a=(UUID)target.get("source_attachment_id");
                var source=sources.source(owner,n,a);var l=EmbeddingLineage.create(configuration,new ProcessingPolicyService.AcknowledgedProcessingPolicy(policy,1,configuration.approvedPolicyFingerprint()),modality);
                float[] v=new float[8];var seed=new Random(20261009L+List.of("note","image","audio","video","pdf").indexOf(modality)*17L+i);
                for(int component=0;component<7;component++)v[component]=2*seed.nextFloat()-1;
                var s=switch(modality) {
                    case "note"->DerivedSegment.note("Frozen Kyoto lodging judgment "+i,"Travel",1400,1400+("Frozen Kyoto lodging judgment "+i).length());
                    case "pdf"->new DerivedSegment("Frozen scanned diagram judgment "+i,"pdf_text","",null,null,17,null,null,null,null,null,null);
                    case "image"->new DerivedSegment("Frozen silver telescope visible label "+i,"whole_image","",null,null,null,null,null,null,null,null,null);
                    case "audio"->new DerivedSegment("Frozen spoken delayed deployment "+i,"transcript","",null,null,null,2.0,4.0,null,null,null,null);
                    default->new DerivedSegment("Frozen sampled red bicycle scene "+i,"video_scene","",null,null,null,2.0,4.0,null,null,null,null);
                };
                if(a==null)jdbc.update("update notes.note set markdown=? where note_id=?"," ".repeat(1400)+s.text(),n);
                tx(()->{representations.activate(source.expected(),l,List.of(s),List.of(v));return null;});
                // A different owner's exact same vector/text must never compete in this owner's ranking.
                var foreignTarget=jdbc.queryForMap("select source_note_id,source_attachment_id from knowledge.private_derived_representation where owner_user_id=? and modality=? and state='ready' order by derived_representation_id limit 1",other,modality);
                var foreignSource=sources.source(other,(UUID)foreignTarget.get("source_note_id"),(UUID)foreignTarget.get("source_attachment_id"));
                tx(()->{representations.activate(foreignSource.expected(),l,List.of(s),List.of(v));return null;});
                var latencies=new ArrayList<Double>();List<ExactPrivateVectorSearch.Candidate> ranked=List.of();
                for(int run=0;run<5;run++){
                    var batches=new ArrayList<Map<String,Object>>();
                    doAnswer(call->{long began=System.nanoTime();try{return call.callRealMethod();}finally{
                        batches.add(Map.of("sources",((List<?>)call.getArgument(0)).size(),"millis",(System.nanoTime()-began)/1e6));
                    }}).when(org.springframework.test.util.AopTestUtils.<PrivateAiSourceCurrentness>getUltimateTargetObject(currentSources)).matching(anyList());
                    long before=System.nanoTime();
                    try {ranked=vectors.search(owner,modality,v,20);}
                    catch(org.springframework.dao.QueryTimeoutException bounded) {
                        writeEvaluation("timeout-"+count,Map.of("modality",modality,"case",i,"run",run,
                            "elapsedMillis",(System.nanoTime()-before)/1e6,"sourceValidationBatches",batches));
                        if(count!=10000)throw bounded;
                        writeEvaluation("scale-10000",Map.of("seed",FrozenScaleCorpus.SEED,"sources",count,"outcome","BOUNDED_TIMEOUT",
                            "transactionBudgetSeconds",3,"failedModality",modality,"completedMeasurements",measurements,"partialRankingReturned",false,"qualityNotQualified",true));
                        return;
                    }
                    latencies.add((System.nanoTime()-before)/1e6);
                }
                assertThat(ranked.getFirst().expected()).isEqualTo(source.expected());assertThat(ranked.getFirst().segment()).isEqualTo(s);
                assertThat(ranked).allSatisfy(c->assertThat(c.expected().owner()).isEqualTo(owner));
                // Gold is the independently installed target, not whichever result the search returned first.
                UUID gold=jdbc.queryForObject("select s.derived_segment_id from knowledge.private_derived_segment s join knowledge.private_derived_representation r on r.derived_representation_id=s.parent_id where r.owner_user_id=? and r.source_note_id=? and r.source_attachment_id is not distinct from ?::uuid and r.state='ready'",UUID.class,owner,n,a);
                var metrics=FrozenRetrievalMetrics.score(ranked.stream().map(c->c.segmentId().toString()).toList(),Map.of(gold.toString(),1));
                var sourceMetrics=FrozenRetrievalMetrics.score(ranked.stream().map(c->c.expected().noteId()+":"+c.expected().attachmentId()).distinct().toList(),Map.of(n+":"+a,1));
                assertThat(metrics.recall1()).isEqualTo(1);latencies.sort(Double::compare);
                String question=KnowledgeQueryRequest.normalize(frozenQuestion(modality,i));
                doAnswer(call->{KnowledgeQueryGate.QueryPermit p=call.getArgument(0);p.requireDispatch();assertOutsideTransaction();return v;}).when(provider).embedQuery(any(),eq(question));
                doAnswer(call->{KnowledgeQueryGate.EvidencePermit p=call.getArgument(0);p.requireDispatch();assertOutsideTransaction();
                    List<StructuredKnowledgePort.Evidence> e=call.getArgument(3);assertThat(e.size()).isBetween(1,12);
                    var supported=e.stream().filter(item->item.text().equals(s.text())).findFirst().orElseThrow(()->new AssertionError("Gold segment missing from bounded evidence"));
                    return new StructuredKnowledgePort.Output(List.of(new StructuredKnowledgePort.Claim(s.text(),List.of(supported.id()))),false);
                }).when(provider).generate(any(),eq(question),eq("answer"),any());
                var response=json.readTree(query(question).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
                assertThat(response.get("degraded").isNull()).isTrue();assertThat(response.get("aiAnswer").isNull()).isFalse();
                assertThat(response.get("citations").size()).isEqualTo(1);var citation=response.get("citations").get(0);
                assertThat(citation.get("noteId").asText()).isEqualTo(n.toString());
                if(a==null)assertThat(citation.get("attachmentId").isNull()).isTrue();else assertThat(citation.get("attachmentId").asText()).isEqualTo(a.toString());
                assertThat(citation.get("location").get("kind").asText()).isEqualTo(s.kind());
                if(s.page()!=null)assertThat(citation.get("location").get("page").asInt()).isEqualTo(s.page());
                if(s.timeStart()!=null)assertThat(citation.get("location").get("timeStart").asDouble()).isEqualTo(s.timeStart());
                measurements.add(Map.of("modality",modality,"case",i,"split",i<2?"development":"held-out","sourceMetrics",sourceMetrics,"segmentMetrics",metrics,"segmentRecall20",metrics.recall20(),"correctCitationAndLocation",true,"p50Millis",latencies.get(2),"p95Millis",latencies.get(4),"latencySamplesMillis",List.copyOf(latencies)));
            }
        }
        long sourcesCount=jdbc.queryForObject("select count(*) from knowledge.private_derived_representation where owner_user_id=? and state='ready'",Long.class,owner);
        assertThat(sourcesCount).isEqualTo(count);
        var runtime=java.lang.management.ManagementFactory.getMemoryMXBean();
        var performance=Map.of("heapUsedBytes",runtime.getHeapMemoryUsage().getUsed(),"heapCommittedBytes",runtime.getHeapMemoryUsage().getCommitted(),
            "jvmCpuNanos",java.lang.management.ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os?os.getProcessCpuTime():-1,
            "databaseConnections",jdbc.queryForObject("select count(*) from pg_stat_activity where datname=current_database()",Integer.class),"searchTransactionTimeoutSeconds",3,
            "segmentCount",jdbc.queryForObject("select count(*) from knowledge.private_derived_segment where owner_user_id=?",Long.class,owner),
            "fixtureAndEvaluationCpuNanos",osBean instanceof com.sun.management.OperatingSystemMXBean os?os.getProcessCpuTime()-cpuBefore:-1,
            "fixtureAndEvaluationWallMillis",(System.nanoTime()-start)/1e6,
            "tagCount",jdbc.queryForObject("select count(*) from notes.note_tag where owner_user_id=?",Long.class,owner));
        writeEvaluation("scale-"+count,Map.of("seed",FrozenScaleCorpus.SEED,"sources",sourcesCount,"fixtureMillis",fixtureMillis,"embeddingKind","controlled-8d-seeded-NOT-Gemini-quality","measurements",measurements,"resources",performance,
            "modalityCounts",jdbc.queryForList("select modality,count(*) sources from knowledge.private_derived_representation where owner_user_id=? and state='ready' group by modality order by modality",owner),
            "sourceStates",jdbc.queryForList("select lifecycle_state,ai_enabled,count(*) sources from notes.note where owner_user_id=? group by lifecycle_state,ai_enabled order by lifecycle_state,ai_enabled",owner),
            "crossUserDistractorSources",40,
            "eligibilityByModality",jdbc.queryForList("""
                select r.modality,count(*) ingested,count(*) filter(where n.ai_enabled and n.lifecycle_state in ('active','archived')
                    and n.ai_generation=r.processing_generation and (r.source_attachment_id is not null or n.revision=r.source_revision)) eligible
                from knowledge.private_derived_representation r join notes.note n on n.note_id=r.source_note_id and n.owner_user_id=r.owner_user_id
                where r.owner_user_id=? and r.state='ready' group by r.modality order by r.modality
                """,owner)));
        writeScalePlan(count);
    }

    private void writeScalePlan(int count)throws Exception {
        var l=EmbeddingLineage.create(configuration,new ProcessingPolicyService.AcknowledgedProcessingPolicy(policy,1,configuration.approvedPolicyFingerprint()),"note");
        var named=new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc);
        var roots=jdbc.query("select derived_representation_id,source_note_id,source_revision,processing_generation from knowledge.private_derived_representation where owner_user_id=? and state='ready' and modality='note' order by derived_representation_id limit 256",
            (r,i)->new ExactPrivateVectorSearch.Root(r.getObject(1,UUID.class),new PrivateAiSourceCurrentness.Expected(owner,r.getObject(2,UUID.class),null,r.getLong(3),r.getLong(4),null)),owner);
        var current=tx(()->currentSources.matching(roots.stream().map(ExactPrivateVectorSearch.Root::expected).toList()));
        var parents=roots.stream().filter(r->current.contains(r.expected())).map(ExactPrivateVectorSearch.Root::id).toList();
        var parameters=Map.<String,Object>of("owner",owner,"lineage",l.id(),"dimension",8,"policy",policy,"parents",parents,"vector","[1,0,0,0,0,0,0,0]","limit",20);
        var plan=named.queryForList("explain (analyze,buffers,format json) "+ExactPrivateVectorSearch.candidateSql("<=>"),parameters,String.class);
        assertRepresentationScannedOnce(json.readTree(plan.getFirst()).get(0).get("Plan"));
        var rootPlan=named.queryForList("""
            explain (analyze,buffers,format json) select derived_representation_id from knowledge.private_derived_representation
            where owner_user_id=:owner and state='ready' and lineage_id=:lineage and processing_policy_id=:policy
                and embedding_dimension=:dimension and modality='note' and derived_representation_id>'00000000-0000-0000-0000-000000000000'::uuid
            order by derived_representation_id limit 256
            """,parameters,String.class);
        writeEvaluation("plan-"+count,Map.of("postgresql",jdbc.queryForObject("show server_version",String.class),"authorizedParentBatch",parents.size(),"exactCandidatePlan",json.readTree(plan.getFirst()),"rootKeysetPlan",json.readTree(rootPlan.getFirst())));
    }

    private static void assertRepresentationScannedOnce(tools.jackson.databind.JsonNode node) {
        if(node.has("Relation Name")&&"private_derived_representation".equals(node.get("Relation Name").asText()))
            assertThat(node.get("Actual Loops").asInt()).isEqualTo(1);
        if(node.has("Plans"))for(var child:node.get("Plans"))assertRepresentationScannedOnce(child);
    }

    private static String frozenQuestion(String modality,int index) {
        return switch(modality) {
            case "note"->List.of("Find Kyoto lodging judgment 0","Find Koyto lodgng","Find the place to stay in Japan","Find Kyoto থাকার জায়গা").get(index);
            case "image"->List.of("Find the silver telescope photo","Find the label visible on the observatory picture","Find the metallic viewing instrument","Find the image with writing, not its filename").get(index);
            case "audio"->List.of("Find the spoken delayed deployment","Find the recording about shipping late","Find the speaker discussing postponed release","Find the audio mentioning the delayed software launch").get(index);
            case "video"->List.of("Find the red bicycle scene","Find the clip showing a crimson bike","Find the sampled scene with a bicycle","Find the visible bike, not the recording filename").get(index);
            default->List.of("Find the scanned diagram on page seventeen","Find the fact on the later PDF page","Find the diagram text in the long document","Find the OCR represented page, not an unprocessed page").get(index);
        };
    }

    private void writeEvaluation(String name,Object report)throws Exception {
        var directory=java.nio.file.Path.of("target","retrieval-evaluation");java.nio.file.Files.createDirectories(directory);
        java.nio.file.Files.writeString(directory.resolve(name+".json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }
    private void deriveChunks(List<DerivedSegment> segments,List<float[]> embeddings){var source=sources.note(owner,note);var lineage=EmbeddingLineage.create(configuration,new ProcessingPolicyService.AcknowledgedProcessingPolicy(policy,1,configuration.approvedPolicyFingerprint()),"note");
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
        // Ordinary matches must keep ordinary lexical relevance, independent of AI
        // permission/vector similarity; preserve the exact source set above.
        var ordinary=json.readTree(mvc.perform(post("/api/notes/search").cookie(browser.cookie).header("X-CSRF-TOKEN",browser.csrf)
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("query","find Kyoto","limit",50))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        var lexicalOrder=new ArrayList<String>();ordinary.get("items").forEach(item->lexicalOrder.add(item.get("id").asText()));
        assertThat(ranked).containsExactlyElementsOf(lexicalOrder);
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
            case "policy"->disclosureFixtures.insert(jdbc,policyConfiguration.code(),2,true);
            case "lineage"->{
                when(configuration.configurationId()).thenReturn("synthetic-v2");
                doAnswer(call->{
                    List<StructuredKnowledgePort.Evidence> supplied=call.getArgument(3);
                    assertThat(supplied).hasSize(1);assertThat(supplied.getFirst().text()).isEqualTo("Synthetic ordinary body")
                        .doesNotContain("PlayStation synthetic login");
                    return new StructuredKnowledgePort.Output(List.of(),false);
                }).when(provider).generate(any(),any(),eq("answer"),any());
            }
            case "invalid-output"->doReturn(new StructuredKnowledgePort.Output(List.of(new StructuredKnowledgePort.Claim("Invented",List.of("foreign"))),false)).when(provider).generate(any(),any(),any(),any());
            case "empty"->doReturn(new StructuredKnowledgePort.Output(List.of(),false)).when(provider).generate(any(),any(),any(),any());
            case "conflict"->doReturn(new StructuredKnowledgePort.Output(List.of(new StructuredKnowledgePort.Claim("Conflicting evidence",List.of("e0"))),true)).when(provider).generate(any(),any(),any(),any());
        }
        var t=json.readTree(query("what was my PlayStation login").andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(t.get("deterministicResults").size()).isPositive();
        if(outcome.equals("conflict")){assertThat(t.get("aiAnswer").get("conflicting").asBoolean()).isTrue();assertThat(t.get("citations").size()).isPositive();}
        else {assertThat(t.get("aiAnswer").isNull()).isTrue();assertThat(t.get("citations").size()).isZero();
            assertThat(t.get("insufficientEvidence").asBoolean()).isEqualTo(outcome.equals("empty")||outcome.equals("lineage"));}
        if(outcome.equals("lineage")){verify(provider).generate(any(),any(),eq("answer"),any());verify(sources).currentMarkdown(any());}
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
        List<UUID> expected=new ArrayList<>();for(int i=0;i<33;i++){UUID n=note(owner,true,"City "+i,"Synthetic city "+i);expected.add(n);derive(n,"Synthetic city "+i);}
        var known=new HashMap<String,String>();for(int i=0;i<33;i++)known.put("Synthetic city "+i,"City "+i);
        doAnswer(call->{assertOutsideTransaction();KnowledgeQueryGate.EvidencePermit permit=call.getArgument(0);permit.requireDispatch();List<StructuredKnowledgePort.Evidence> e=call.getArgument(3);
            return new StructuredKnowledgePort.Output(e.stream().map(item->new StructuredKnowledgePort.Claim(known.get(item.text()),List.of(item.id()))).toList(),false);}).when(provider).generate(any(),any(),eq("extract"),any());
        String handle=accept("list every city I mentioned");operations.execute(claim());var t=poll(handle).get("result");
        assertThat(t.get("aiAnswer").get("claims").size()).isEqualTo(33);assertThat(t.get("coverage").get("completed").asBoolean()).isTrue();
        assertThat(t.get("coverage").get("classificationUncertain").asBoolean()).isTrue();assertThat(t.get("citations").size()).isEqualTo(33);
        Set<String> found=new HashSet<>();t.get("citations").forEach(c->found.add(c.get("noteId").asText()));assertThat(found).containsExactlyInAnyOrderElementsOf(expected.stream().map(UUID::toString).toList());
        // Frozen synthetic extraction scores are independent from traversal coverage and real-world classification recall.
        Set<String> claims=new HashSet<>();t.get("aiAnswer").get("claims").forEach(c->claims.add(c.asText()));
        assertFrozenSetQuality(claims,new HashSet<>(known.values()));
        assertFrozenSetQuality(found,new HashSet<>(expected.stream().map(UUID::toString).toList()));
        verify(provider,times(2)).generate(any(),any(),eq("extract"),any());verify(provider,never()).embedQuery(any(),any());
    }
    @Test void semanticBatchCrossesSourceAndSegmentBoundariesWithoutDroppingOrReplayingEvidence() throws Exception {
        UUID second=note(owner,true,"Second long source","temporary fixture body");
        for(UUID id:List.of(note,second)){
            var chunks=new ArrayList<DerivedSegment>();var body=new StringBuilder();
            for(int i=0;i<33;i++){String text="Frozen record "+(id.equals(note)?"A":"B")+i;int start=body.length();body.append(text).append('\n');chunks.add(DerivedSegment.note(text,"",start,start+text.length()));}
            jdbc.update("update notes.note set markdown=? where note_id=?",body.toString(),id);
            var source=sources.note(owner,id);var lineage=EmbeddingLineage.create(configuration,new ProcessingPolicyService.AcknowledgedProcessingPolicy(policy,1,configuration.approvedPolicyFingerprint()),"note");
            tx(()->{representations.activate(source.expected(),lineage,chunks,chunks.stream().map(c->vector()).toList());return null;});
        }
        var visited=new HashSet<String>();
        doAnswer(call->{assertOutsideTransaction();KnowledgeQueryGate.EvidencePermit permit=call.getArgument(0);permit.requireDispatch();
            List<StructuredKnowledgePort.Evidence> supplied=call.getArgument(3);assertThat(supplied).hasSizeBetween(1,32);
            supplied.forEach(e->assertThat(visited.add(e.text())).isTrue());
            return new StructuredKnowledgePort.Output(supplied.stream().map(e->new StructuredKnowledgePort.Claim(e.text(),List.of(e.id()))).toList(),false);
        }).when(provider).generate(any(),any(),eq("extract"),any());
        String handle=accept("list every frozen record");operations.execute(claim());var result=poll(handle).get("result");
        assertThat(visited).hasSize(66);assertThat(result.get("citations").size()).isEqualTo(66);
        assertThat(result.get("coverage").get("completed").asBoolean()).isTrue();
        assertThat(result.get("coverage").get("inspectedSources").asLong()).isEqualTo(2);
        verify(provider,times(3)).generate(any(),any(),eq("extract"),any());
    }
    @Test void semanticBatchTraversesElevenHundredSourcesAndRetainsOnlySupportedClaims() throws Exception {
        jdbc.update("update notes.note set ai_enabled=false where note_id=?",note);
        FrozenScaleCorpus.text(jdbc,owner,1100,configuration,policy);
        var expected=jdbc.queryForList("select note_id from notes.note where owner_user_id=? and ai_enabled order by note_id limit 10",UUID.class,owner);
        for(int i=0;i<expected.size();i++)derive(expected.get(i),"Frozen profession entry "+i);
        var inspected=new HashSet<String>();
        doAnswer(call->{assertOutsideTransaction();KnowledgeQueryGate.EvidencePermit permit=call.getArgument(0);permit.requireDispatch();
            List<StructuredKnowledgePort.Evidence> supplied=call.getArgument(3);assertThat(supplied).hasSizeBetween(1,32);
            return new StructuredKnowledgePort.Output(supplied.stream().filter(e->e.text().startsWith("Frozen profession entry "))
                .map(e->{assertThat(inspected.add(e.text())).isTrue();return new StructuredKnowledgePort.Claim(e.text(),List.of(e.id()));}).toList(),false);
        }).when(provider).generate(any(),any(),eq("extract"),any());
        String handle=accept("list every frozen profession entry");int slices=0;
        while(!poll(handle).get("status").asText().equals("completed")){assertThat(++slices).isLessThan(10);operations.execute(claim());}
        var result=poll(handle).get("result");assertThat(result.get("coverage").get("inspectedSources").asLong()).isEqualTo(1100);
        assertThat(result.get("coverage").get("completed").asBoolean()).isTrue();assertThat(result.get("coverage").get("truncated").asBoolean()).isFalse();
        assertThat(result.get("aiAnswer").get("claims").size()).isEqualTo(10);assertThat(result.get("citations").size()).isEqualTo(10);
        Set<String> citations=new HashSet<>();result.get("citations").forEach(c->citations.add(c.get("noteId").asText()));
        assertThat(citations).containsExactlyInAnyOrderElementsOf(expected.stream().map(UUID::toString).toList());
        verify(provider,times(35)).generate(any(),any(),eq("extract"),any());verify(provider,never()).embedQuery(any(),any());
    }
    @Test void semanticPausePersistsContinuationWithoutHoldingTheClaimOrConsumingAnotherAttempt() throws Exception {
        derive(note,"Frozen initial source");for(int i=0;i<32;i++){UUID id=note(owner,true,"Frozen source "+i,"Frozen body "+i);derive(id,"Frozen body "+i);}
        try{operations.semanticPauseMillis(15000);String handle=accept("list every frozen body");var row=claim();operations.execute(row);
            assertThat(poll(handle).get("status").asText()).isEqualTo("queued");
            assertThat(tx(()->operationRows.current(row))).isFalse();assertThat(operations.claim(2)).isEmpty();
            assertThat(jdbc.queryForObject("select next_attempt_at>clock_timestamp() and lease_token is null and checkpoint_version=1 and attempt_count=1 from knowledge.knowledge_work_intent where knowledge_work_intent_id=?",Boolean.class,row.id())).isTrue();
            verify(provider,times(1)).generate(any(),any(),eq("extract"),any());
        }finally{operations.semanticPauseMillis(0);}
    }
    @Test void exhaustiveClassificationUsesCurrentNoteSnapshotsWithoutInventingEmbeddingsOrScanningForeignOrAiOffText() throws Exception {
        jdbc.update("update notes.note set ai_enabled=false,markdown='AI_OFF_CANARY' where note_id=?",note);
        UUID foreign=account();note(foreign,true,"Foreign only","OTHER_OWNER_CANARY");
        jdbc.update("""
            insert into notes.note(note_id,owner_user_id,title,markdown,lifecycle_state,ai_enabled,revision,ai_generation,created_at,updated_at)
            select uuidv7(),?,'Frozen everyday entry '||i,
                case when i%110=0 then 'Fixture profession name '||i else 'Unrelated daily fixture '||i end,
                'active',true,1,1,clock_timestamp(),clock_timestamp() from generate_series(1,1100) i
            """,owner);
        var claimed=new HashSet<String>();
        doAnswer(call->{assertOutsideTransaction();KnowledgeQueryGate.EvidencePermit permit=call.getArgument(0);permit.requireDispatch();
            List<StructuredKnowledgePort.Evidence> supplied=call.getArgument(3);assertThat(supplied).hasSizeBetween(1,32);
            supplied.forEach(e->{assertThat(e.text()).doesNotContain("AI_OFF_CANARY","OTHER_OWNER_CANARY");assertThat(e.location().kind()).isEqualTo("note_text");});
            return new StructuredKnowledgePort.Output(supplied.stream().filter(e->e.text().startsWith("Fixture profession name "))
                .map(e->{assertThat(claimed.add(e.text())).isTrue();return new StructuredKnowledgePort.Claim(e.text(),List.of(e.id()));}).toList(),false);
        }).when(provider).generate(any(),any(),eq("extract"),any());
        String handle=accept("list every fixture profession name");int slices=0;
        while(!poll(handle).get("status").asText().equals("completed")){assertThat(++slices).isLessThan(10);operations.execute(claim());}
        var result=poll(handle).get("result");assertThat(claimed).hasSize(10);assertThat(result.get("citations").size()).isEqualTo(10);
        assertThat(result.get("coverage").get("inspectedSources").asLong()).isEqualTo(1100);assertThat(result.get("coverage").get("completed").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from knowledge.private_derived_representation where owner_user_id=?",Integer.class,owner)).isZero();
        verify(provider,times(35)).generate(any(),any(),eq("extract"),any());verify(provider,never()).embedQuery(any(),any());
        verify(sources,times(1100)).currentMarkdown(any());
    }
    @Test void unapprovedCurrentNoteSnapshotIsDeniedBeforeItsBodyIsRead() throws Exception {
        String query="list every fixture profession name";
        when(configuration.provider()).thenReturn("gemini");when(configuration.tier()).thenReturn("unpaid");when(configuration.region()).thenReturn("global");
        String code="synthetic-"+UUID.randomUUID();when(policyConfiguration.code()).thenReturn(code);
        policy=disclosureFixtures.insert(jdbc,code,1,true);ack(owner);
        when(dispatch.dispatchPolicy()).thenReturn("unpaid-synthetic-demo");
        when(dispatch.approvedQueryFingerprints()).thenReturn(List.of(ProviderDispatchPolicy.queryFingerprint(query)));
        when(dispatch.approvedSourceFingerprints()).thenReturn(List.of());
        String handle=accept(query);operations.execute(claim());
        assertThat(poll(handle).get("status").asText()).isEqualTo("obsolete");
        verify(sources,never()).currentMarkdown(any());verify(provider,never()).generate(any(),any(),any(),any());
    }
    @Test void isolatedWorkspaceAcceptsNewAndEditedNotesWithoutFingerprintingButStillRejectsStaleAiOffAndForeignSources() throws Exception {
        when(configuration.provider()).thenReturn("gemini");when(configuration.tier()).thenReturn("unpaid");when(configuration.region()).thenReturn("global");
        String code="synthetic-workspace-"+UUID.randomUUID();when(policyConfiguration.code()).thenReturn(code);
        policy=disclosureFixtures.insert(jdbc,code,1,true);ack(owner);
        var properties=new ProviderDispatchProperties("unpaid-synthetic-demo",List.of(),List.of(),List.of(),owner);
        var gate=new KnowledgeQueryGate(workspaceProcessingPolicies,configuration,new ProviderDispatchPolicy(properties,true),properties,sources);
        UUID fresh=note(owner,true,"New fictional reminder","Bring the fictional silver compass.");
        var original=sources.note(owner,fresh);String question="What did I just write about the fictional compass?";
        try(var handle=coordination.dispatch(owner,fresh)) {
            var permit=tx(()->gate.evidence(owner,question,List.of(original),handle));permit.requireGoogle(question);
            jdbc.update("update notes.note set markdown='Bring the fictional bronze compass.',revision=revision+1 where note_id=?",fresh);
            assertThatThrownBy(()->tx(()->gate.evidence(owner,question,List.of(original),handle))).isInstanceOf(DerivationFailure.class);
            var edited=sources.note(owner,fresh);tx(()->gate.evidence(owner,question,List.of(edited),handle)).requireGoogle(question);
            jdbc.update("update notes.note set ai_enabled=false,ai_generation=ai_generation+1 where note_id=?",fresh);
            assertThatThrownBy(()->tx(()->gate.evidence(owner,question,List.of(edited),handle))).isInstanceOf(DerivationFailure.class);
        }
        assertThatThrownBy(()->tx(()->gate.query(account(),question))).isInstanceOf(DerivationFailure.class);
    }
    @Test void naturalNameEnumerationUsesTheTrackedCorpusOperationAndItsCompleteBoundary() throws Exception {
        note(owner,true,"Fictional name one","Fictional scientist Orin.");note(owner,true,"Fictional name two","Fictional scientist Luma.");
        String handle=accept("Which fictional scientist names did I save???");operations.execute(claim());
        var result=poll(handle).get("result");assertThat(result.get("coverage").get("completed").asBoolean()).isTrue();
        assertThat(result.get("coverage").get("inspectedSources").asLong()).isEqualTo(3);
        verify(provider,never()).embedQuery(any(),any());verify(provider).generate(any(),any(),eq("extract"),any());
    }
    @Autowired ProcessingPolicyService workspaceProcessingPolicies;
    @Test void currentNoteSnapshotReaderRejectsDisabledAndStaleSources() {
        var source=sources.note(owner,note);
        assertThat(sources.currentMarkdown(source)).isEqualTo("Synthetic ordinary body");
        jdbc.update("update notes.note set revision=revision+1 where note_id=?",note);
        assertThatThrownBy(()->sources.currentMarkdown(source)).isInstanceOf(org.notesknowledge.websupport.ApiFailureException.class);
        var current=sources.note(owner,note);jdbc.update("update notes.note set ai_enabled=false,ai_generation=ai_generation+1 where note_id=?",note);
        assertThatThrownBy(()->sources.currentMarkdown(current)).isInstanceOf(org.notesknowledge.websupport.ApiFailureException.class);
    }
    @Test void focusedFactCanUseItsCurrentLexicalNoteBeforeBackgroundEmbeddingExists() throws Exception {
        jdbc.update("update notes.note set title='Fictional weekend budget',markdown='The current fictional weekend budget is 4850 units.' where note_id=?",note);
        doAnswer(call->{KnowledgeQueryGate.EvidencePermit permit=call.getArgument(0);permit.requireDispatch();assertOutsideTransaction();
            List<StructuredKnowledgePort.Evidence> supplied=call.getArgument(3);assertThat(supplied).hasSize(1);
            assertThat(supplied.getFirst().text()).contains("4850");
            return new StructuredKnowledgePort.Output(List.of(new StructuredKnowledgePort.Claim("4850 units",List.of(supplied.getFirst().id()))),false);
        }).when(provider).generate(any(),any(),eq("answer"),any());
        var result=json.readTree(query("What is the fictional weekend budget?").andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(result.get("aiAnswer").get("claims").get(0).asText()).isEqualTo("4850 units");
        assertThat(result.get("citations").get(0).get("noteId").asText()).isEqualTo(note.toString());
        assertThat(result.get("citations").get(0).get("location").get("kind").asText()).isEqualTo("note_text");
        assertThat(jdbc.queryForObject("select count(*) from knowledge.private_derived_representation where owner_user_id=?",Integer.class,owner)).isZero();
    }
    @Test void unrepresentedNoteDisabledDuringGenerationNeverExposesItsSnapshotClaim() throws Exception {
        doAnswer(call->{assertOutsideTransaction();KnowledgeQueryGate.EvidencePermit permit=call.getArgument(0);permit.requireDispatch();
            jdbc.update("update notes.note set ai_enabled=false,ai_generation=ai_generation+1,revision=revision+1 where note_id=?",note);
            return new StructuredKnowledgePort.Output(List.of(new StructuredKnowledgePort.Claim("Must not expose stale snapshot",List.of("e0"))),false);
        }).when(provider).generate(any(),any(),eq("extract"),any());
        String handle=accept("list every frozen fact");operations.execute(claim());var result=poll(handle).get("result");
        assertThat(result.get("aiAnswer").isNull()).isTrue();assertThat(result.get("citations").size()).isZero();
        assertThat(result.toString()).doesNotContain("Must not expose stale snapshot");verify(provider,times(1)).generate(any(),any(),eq("extract"),any());
    }
    @Test void semanticProviderFailureStopsAfterOneBoundedBatchAndCannotClaimCompleteCoverage() throws Exception {
        derive(note,"Frozen initial source");for(int i=0;i<32;i++){UUID id=note(owner,true,"Frozen source "+i,"Frozen body "+i);derive(id,"Frozen body "+i);}
        doThrow(new DerivationFailure(KnowledgeWork.Failure.PROVIDER_UNAVAILABLE)).when(provider).generate(any(),any(),eq("extract"),any());
        String handle=accept("list every frozen body");operations.execute(claim());var result=poll(handle).get("result");
        assertThat(result.get("coverage").get("completed").asBoolean()).isFalse();assertThat(result.get("coverage").get("truncated").asBoolean()).isTrue();
        assertThat(result.get("degraded").asText()).isEqualTo("providerUnavailable");assertThat(result.get("aiAnswer").isNull()).isTrue();
        verify(provider,times(1)).generate(any(),any(),eq("extract"),any());
    }
    private static DerivationFailure transientGeneration() {
        return new DerivationFailure(KnowledgeWork.Failure.TRANSIENT_DEPENDENCY,new ProviderFailureDiagnostic(
            ProviderFailureDiagnostic.Stage.GENERATION,ProviderFailureDiagnostic.Kind.SOCKET_TIMEOUT,null,
            ProviderFailureDiagnostic.Elapsed.OVER_THIRTY_SECONDS,ProviderFailureDiagnostic.Reach.UNKNOWN,false));
    }
    @Test void transientCorpusFailureResumesTheUncommittedBatchAndPreservesEarlierClaims() throws Exception {
        for(int i=0;i<32;i++)note(owner,true,"Frozen continuation "+i,"Continuation body "+i);
        var successful=new ArrayList<String>();var calls=new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call->{assertOutsideTransaction();KnowledgeQueryGate.EvidencePermit permit=call.getArgument(0);permit.requireDispatch();
            List<StructuredKnowledgePort.Evidence> supplied=call.getArgument(3);
            if(calls.incrementAndGet()==2)throw transientGeneration();
            supplied.forEach(e->{assertThat(successful).doesNotContain(e.text());successful.add(e.text());});
            return new StructuredKnowledgePort.Output(supplied.stream().map(e->new StructuredKnowledgePort.Claim(e.text(),List.of(e.id()))).toList(),false);
        }).when(provider).generate(any(),any(),eq("extract"),any());
        String handle=accept("list every continuation body");var first=claim();operations.execute(first);
        assertThat(poll(handle).get("status").asText()).isEqualTo("retry_wait");
        assertThat(poll(handle).get("inspectedSources").asLong()).isEqualTo(32);
        assertThat(operationVersion(first.id())).isEqualTo(1);assertThat(operations.claim(2)).isEmpty();
        assertThat(jdbc.queryForObject("select lease_token is null and attempt_count=1 and next_attempt_at between clock_timestamp()+interval '28 seconds' and clock_timestamp()+interval '41 seconds' from knowledge.knowledge_work_intent where knowledge_work_intent_id=?",Boolean.class,first.id())).isTrue();
        jdbc.update("update knowledge.knowledge_work_intent set next_attempt_at=clock_timestamp()-interval '1 second' where knowledge_work_intent_id=?",first.id());
        var resumed=claim();assertThat(resumed.version()).isEqualTo(1);assertThat(resumed.lease().value()).isNotEqualTo(first.lease().value());
        operations.execute(resumed);var result=poll(handle).get("result");
        assertThat(successful).hasSize(33);assertThat(calls).hasValue(3);
        assertThat(result.get("coverage").get("inspectedSources").asLong()).isEqualTo(33);
        assertThat(result.get("coverage").get("completed").asBoolean()).isTrue();assertThat(result.get("coverage").get("truncated").asBoolean()).isFalse();
        assertThat(result.get("citations").size()).isEqualTo(33);assertThat(result.get("aiAnswer").get("claims").size()).isEqualTo(33);
    }
    @Test void repeatedTransientFailureStopsAtThePersistedAttemptBudget() throws Exception {
        doThrow(transientGeneration()).when(provider).generate(any(),any(),eq("extract"),any());
        String handle=accept("list every synthetic body");
        for(int attempt=1;attempt<=3;attempt++){
            var row=claim();operations.execute(row);
            assertThat(poll(handle).get("status").asText()).isEqualTo(attempt<3?"retry_wait":"failed");
            assertThat(operationVersion(row.id())).isZero();
            if(attempt<3)jdbc.update("update knowledge.knowledge_work_intent set next_attempt_at=clock_timestamp()-interval '1 second' where knowledge_work_intent_id=?",row.id());
        }
        verify(provider,times(3)).generate(any(),any(),eq("extract"),any());assertThat(operations.claim(2)).isEmpty();
    }
    @Test void transientFailureCannotRequeueACancelledOperation() throws Exception {
        var handle=new java.util.concurrent.atomic.AtomicReference<String>();
        doAnswer(call->{mvc.perform(delete("/api/knowledge/operations/"+handle.get()).cookie(browser.cookie).header("X-CSRF-TOKEN",browser.csrf)).andExpect(status().isNoContent());throw transientGeneration();})
            .when(provider).generate(any(),any(),eq("extract"),any());
        handle.set(accept("list every synthetic body"));operations.execute(claim());
        assertThat(poll(handle.get()).get("status").asText()).isEqualTo("cancelled");assertThat(operations.claim(2)).isEmpty();
    }
    @Test void disabledSourceIsExcludedBeforeAnyRetryCanReadOrDispatchIt() throws Exception {
        doThrow(transientGeneration()).when(provider).generate(any(),any(),eq("extract"),any());
        String handle=accept("list every synthetic body");var row=claim();operations.execute(row);
        assertThat(poll(handle).get("status").asText()).isEqualTo("retry_wait");
        jdbc.update("update notes.note set ai_enabled=false,ai_generation=ai_generation+1,revision=revision+1 where note_id=?",note);
        jdbc.update("update knowledge.knowledge_work_intent set next_attempt_at=clock_timestamp()-interval '1 second' where knowledge_work_intent_id=?",row.id());
        clearInvocations(provider,sources);operations.execute(claim());var result=poll(handle).get("result");
        assertThat(result.get("aiAnswer").isNull()).isTrue();assertThat(result.get("citations").size()).isZero();
        verify(provider,never()).generate(any(),any(),any(),any());verify(sources,never()).currentMarkdown(any());
    }
    @Test void checkpointSurvivesWorkerLossAndReclaimDoesNotLosePriorAggregate() throws Exception {
        jdbc.update("update notes.note set markdown='https://example.test/first' where note_id=?",note);note(owner,false,"Second","https://example.test/second");
        for(int i=0;i<11;i++)note(owner,false,"Synthetic continuation filler","No supported link here");
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
        var lineage=EmbeddingLineage.create(configuration,new ProcessingPolicyService.AcknowledgedProcessingPolicy(policy,1,configuration.approvedPolicyFingerprint()),kind);
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
            var source=sources.note(owner,note);var l=EmbeddingLineage.create(configuration,new ProcessingPolicyService.AcknowledgedProcessingPolicy(policy,1,configuration.approvedPolicyFingerprint()),"note");
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
    @Autowired SearchHistoryService history;
    @Autowired KnowledgeQueryEngine queryEngine;
    @Autowired org.springframework.context.ApplicationContext application;
    @ParameterizedTest @ValueSource(strings={"find cobaltbeacon","what is cobaltbeacon"})
    void savedMatchesAreIndependentLexicalHitsNotNearestVideoCandidates(String question)throws Exception {
        UUID hit=note(owner,true,"cobaltbeacon saved detail","cobaltbeacon evidence");derive(hit,"cobaltbeacon evidence");
        UUID aiOff=note(owner,false,"cobaltbeacon manual reference","cobaltbeacon ordinary-only");
        UUID attachment=upload("video");var source=sources.source(owner,note,attachment);
        var lineage=EmbeddingLineage.create(configuration,new ProcessingPolicyService.AcknowledgedProcessingPolicy(policy,1,configuration.approvedPolicyFingerprint()),"video");
        tx(()->{representations.activate(source.expected(),lineage,List.of(new DerivedSegment("Unrelated basketball video","video_scene","",null,null,null,0.0,0.5,null,null,null,null)),List.of(vector()));return null;});
        doAnswer(call->{List<StructuredKnowledgePort.Evidence> supplied=call.getArgument(3);
            return new StructuredKnowledgePort.Output(supplied.stream().filter(e->e.text().contains("cobaltbeacon"))
                .map(e->new StructuredKnowledgePort.Claim("Supported saved detail",List.of(e.id()))).toList(),false);
        }).when(provider).generate(any(),any(),any(),any());
        var result=json.readTree(query(question).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        var matches=result.get("deterministicResults").toString();assertThat(matches).contains(hit.toString(),aiOff.toString()).doesNotContain(note.toString(),attachment.toString());
        assertThat(result.get("citations").toString()).contains(hit.toString()).doesNotContain(aiOff.toString(),attachment.toString());
        assertThat(capturedEvidence()).noneMatch(e->e.text().contains("ordinary-only"));
        // Controlled provider reports no supported claim for the unrelated question.
        doReturn(new StructuredKnowledgePort.Output(List.of(),false)).when(provider).generate(any(),eq("find xylophonenosource"),any(),any());
        var unsupported=json.readTree(query("find xylophonenosource").andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(unsupported.get("deterministicResults").size()).isZero();assertThat(unsupported.get("aiAnswer").isNull()).isTrue();
        when(provider.available()).thenReturn(false);
        var degraded=json.readTree(query(question).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(degraded.get("deterministicResults").toString()).contains(aiOff.toString(),hit.toString()).doesNotContain(attachment.toString(),note.toString());
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.CsvSource({"true,true","true,false","false,true","false,false"})
    void historyReplayAndPollingPreserveExactlyTheOriginalOccurrencePresentation(boolean deduplicate,boolean includeOccurrences)throws Exception {
        jdbc.update("update notes.note set markdown='https://example.test/shared https://example.test/shared',created_at='2021-01-01T00:00:00Z'::timestamptz where note_id=?",note);
        UUID second=note(owner,false,"Second URL source","https://example.test/shared");jdbc.update("update notes.note set created_at='2021-02-01T00:00:00Z'::timestamptz where note_id=?",second);
        UUID outside=note(owner,false,"Outside scope","https://example.test/excluded");jdbc.update("update notes.note set created_at='2022-01-01T00:00:00Z'::timestamptz where note_id=?",outside);
        var response=mvc.perform(post("/api/knowledge/query").cookie(browser.cookie).header("X-CSRF-TOKEN",browser.csrf).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("query","all links","scope",Map.of("savedDuring",syntheticPeriod()),"presentation",Map.of("deduplicate",deduplicate,"includeOccurrences",includeOccurrences)))))
            .andExpect(status().isAccepted()).andReturn().getResponse();
        var accepted=json.readTree(response.getContentAsString());var row=claim();operations.execute(row);var original=poll(accepted.get("operationId").asText()).get("result");
        var detail=json.readTree(mvc.perform(get(response.getHeader("Content-Location")).cookie(browser.cookie)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(detail.get("result")).isEqualTo(original);assertThat(detail.get("savedDuring").get("from").asText()).isEqualTo("2021-01-01T00:00:00Z");
        assertThat(original.toString()).doesNotContain(outside.toString(),"excluded");
        if(deduplicate){assertThat(original.get("deterministicResults").size()).isEqualTo(1);assertThat(original.get("deterministicResults").get(0).get("occurrences").size()).isEqualTo(includeOccurrences?3:1);}
        assertThat(jdbc.queryForObject("select input_ciphertext is null from knowledge.knowledge_work_intent where knowledge_work_intent_id=?",Boolean.class,row.id())).isTrue();
        var replay=json.readTree(json.writeValueAsBytes(history.detail(owner,history.idForWork(owner,row.id())).result()));assertThat(replay).isEqualTo(original);
        // Reproduce the previously shipped V020 envelope: full occurrences were
        // stored even when the original request asked for a collapsed view.
        var completed=operationRows.owner(owner,row.id()).orElseThrow();
        var stored=json.readValue(cipher.open(new KnowledgeOperationMaterialCipher.Context(row.id(),owner,row.purpose(),KnowledgeOperationMaterialCipher.Kind.RESULT,completed.version()),completed.result()),KnowledgeQueryResult.Stored.class);
        UUID historyId=history.idForWork(owner,row.id());var legacy=cipher.seal(new KnowledgeOperationMaterialCipher.Context(historyId,owner,"search_history",KnowledgeOperationMaterialCipher.Kind.RESULT,1),json.writeValueAsBytes(new SearchHistoryService.Snapshot(1,false,json.readValue(completed.metadata(),KnowledgeOperationService.Metadata.class).boundary(),stored)));
        jdbc.update("update knowledge.search_history_entry set result_ciphertext=?,result_nonce=?,result_key_version=? where history_id=?",legacy.ciphertext(),legacy.nonce(),legacy.keyVersion(),historyId);
        var legacyReplay=json.readTree(json.writeValueAsBytes(history.detail(owner,historyId).result()));assertThat(legacyReplay).isEqualTo(original);
        var other=browser(account());mvc.perform(get(response.getHeader("Content-Location")).cookie(other.cookie)).andExpect(status().isNotFound());
        verify(provider,never()).generate(any(),any(),any(),any());
    }
    @Test void expiredOperationalInputDoesNotEraseOwnerBoundHistoryPresentationMetadata(){
        UUID id=jdbc.queryForObject("select uuidv7()",UUID.class);Instant created=Instant.now().minusSeconds(172800),expiry=created.plusSeconds(86400);
        var frame=cipher.seal(new KnowledgeOperationMaterialCipher.Context(id,owner,"deterministic_corpus",KnowledgeOperationMaterialCipher.Kind.INPUT,0),new byte[]{1});
        tx(()->{operationRows.insert(id,owner,"deterministic_corpus",created,expiry,"{\"includeOccurrences\":false}",frame);operationRows.expire();return null;});
        assertThat(operationRows.owner(owner,id)).isEmpty();assertThat(json.readTree(operationRows.historyMetadata(owner,id).orElseThrow()).get("includeOccurrences").asBoolean()).isFalse();
        assertThat(operationRows.historyMetadata(account(),id)).isEmpty();
    }
    @Test void retainedHistoryRemainsDecryptableAcrossPlannedKeyRotationAndFailsClosedWithoutTheFormerKey()throws Exception {
        var response=query("find synthetic").andExpect(status().isOk()).andReturn().getResponse();UUID id=UUID.fromString(response.getHeader("Content-Location").replace("/api/knowledge/history/",""));
        jdbc.update("update knowledge.search_history_entry set created_at=clock_timestamp()-interval '29 days',expires_at=clock_timestamp()+interval '1 hour' where history_id=?",id);
        byte[] changed=new byte[32];java.util.Arrays.fill(changed,(byte)70);
        var rotated=historyWith(new KnowledgeOperationMaterialCipher("v2",Base64.getEncoder().encodeToString(changed),"v1",Base64.getEncoder().encodeToString(new byte[32])));
        assertThat(rotated.detail(owner,id).query()).isEqualTo("find synthetic");
        var unavailable=historyWith(new KnowledgeOperationMaterialCipher("v2",Base64.getEncoder().encodeToString(changed),"",""));
        assertThatThrownBy(()->unavailable.detail(owner,id)).isInstanceOfSatisfying(org.notesknowledge.websupport.ApiFailureException.class,e->assertThat(e.kind()).isEqualTo(org.notesknowledge.websupport.ApiFailureException.Kind.SERVICE_UNAVAILABLE));
    }
    private SearchHistoryService historyWith(KnowledgeOperationMaterialCipher keys){return new SearchHistoryService(application.getBean(SearchHistoryRepository.class),operationRows,keys,application.getBean(KnowledgeOperationHandleCodec.class),application.getBeanProvider(org.notesknowledge.identity.AccountEligibilityApi.class),application.getBeanProvider(PlatformTransactionManager.class),application.getBeanProvider(org.notesknowledge.websupport.OpaqueCursorCodec.class),sources,application.getBean(KnowledgeQueryGate.class),application.getBean(org.notesknowledge.DatabaseUuidV7Generator.class),application.getBean(java.time.Clock.class),json);}
    @Test void conservativeBatchSizeTraversesEverySourceWithoutLargerModelContexts() throws Exception {
        try{queryEngine.extractionBatchSize(12);for(int i=0;i<32;i++)note(owner,true,"Synthetic record "+i,"Frozen conservative record "+i);
            var seen=new HashSet<String>();var sizes=new ArrayList<Integer>();
            doAnswer(call->{List<StructuredKnowledgePort.Evidence> supplied=call.getArgument(3);sizes.add(supplied.size());supplied.forEach(e->assertThat(seen.add(e.text())).isTrue());return new StructuredKnowledgePort.Output(supplied.stream().map(e->new StructuredKnowledgePort.Claim(e.text(),List.of(e.id()))).toList(),false);}).when(provider).generate(any(),any(),eq("extract"),any());
            String handle=accept("list all synthetic records");operations.execute(claim());var result=poll(handle).get("result");
            assertThat(sizes).containsExactly(12,12,9);assertThat(seen).hasSize(33);assertThat(result.get("citations").size()).isEqualTo(33);assertThat(result.get("coverage").get("completed").asBoolean()).isTrue();
        }finally{queryEngine.extractionBatchSize(32);}
    }
    @Test void historyEncryptedOwnerOnlyAndSurvivesNewSession() throws Exception {
        String q="find synthetic privacy marker";var response=query(q).andExpect(status().isOk()).andReturn().getResponse();
        String path=response.getHeader("Content-Location");assertThat(path).startsWith("/api/knowledge/history/");
        UUID id=UUID.fromString(path.substring(path.lastIndexOf('/')+1));
        assertThat(jdbc.queryForObject("select position(?::bytea in question_ciphertext)=0 from knowledge.search_history_entry where history_id=?",Boolean.class,q.getBytes(StandardCharsets.UTF_8),id)).isTrue();
        browser=browser(owner);var detail=mvc.perform(get(path).cookie(browser.cookie)).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(detail.getHeader("Cache-Control")).contains("no-store");assertThat(json.readTree(detail.getContentAsString()).get("query").asText()).isEqualTo(q);
        var other=browser(account());mvc.perform(get(path).cookie(other.cookie)).andExpect(status().isNotFound());
        mvc.perform(delete(path).cookie(other.cookie).header("X-CSRF-TOKEN",other.csrf)).andExpect(status().isNotFound());
        mvc.perform(delete(path).cookie(browser.cookie)).andExpect(status().isForbidden());
        var list=json.readTree(mvc.perform(get("/api/knowledge/history").cookie(browser.cookie)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(list.get("items").size()).isEqualTo(1);assertThat(list.get("items").get(0).get("result").isNull()).isTrue();
    }
    @Test void historyRemoveFencesLateWorkerAndMakesOldOperationUnavailable() throws Exception {
        String handle=accept("all synthetic books");var row=claim();UUID id=history.idForWork(owner,row.id());
        mvc.perform(delete("/api/knowledge/history/"+id).cookie(browser.cookie).header("X-CSRF-TOKEN",browser.csrf)).andExpect(status().isNoContent());
        operations.execute(row);assertThat(poll(handle).get("status").asText()).isEqualTo("cancelled");
        assertThat(jdbc.queryForObject("select count(*) from knowledge.search_history_entry where history_id=?",Integer.class,id)).isZero();
        assertThat(jdbc.queryForObject("select input_ciphertext is null and result_ciphertext is null from knowledge.knowledge_work_intent where knowledge_work_intent_id=?",Boolean.class,row.id())).isTrue();
    }
    @Test void historyClearErasesCompletedAnswersAndCancelsQueuedSearches() throws Exception {
        String handle=accept("all links");var row=claim();operations.execute(row);assertThat(poll(handle).get("status").asText()).isEqualTo("completed");
        String queued=accept("all other links");mvc.perform(delete("/api/knowledge/history").cookie(browser.cookie).header("X-CSRF-TOKEN",browser.csrf)).andExpect(status().isNoContent());
        assertThat(poll(handle).get("status").asText()).isEqualTo("obsolete");assertThat(poll(queued).get("status").asText()).isEqualTo("cancelled");
        assertThat(jdbc.queryForObject("select count(*) from knowledge.search_history_entry where owner_user_id=?",Integer.class,owner)).isZero();
    }
    @Test void historyCapExpiryCursorAndEnvelopeConstraintsAreDatabaseTruth() throws Exception {
        var request=KnowledgeQueryRequest.parse(Map.of("query","find synthetic"));
        for(int i=0;i<52;i++){var id=history.begin(owner,request,null);history.fail(owner,id);}
        assertThat(jdbc.queryForObject("select count(*) from knowledge.search_history_entry where owner_user_id=?",Integer.class,owner)).isEqualTo(50);
        var page=history.list(owner,null,20);assertThat(page.items()).hasSize(20);assertThat(page.nextCursor()).isNotBlank();
        var second=history.list(owner,page.nextCursor(),20);assertThat(second.items()).hasSize(20);assertThat(second.items().stream().map(SearchHistoryService.View::id)).doesNotContainAnyElementsOf(page.items().stream().map(SearchHistoryService.View::id).toList());
        UUID id=page.items().getFirst().id();
        assertThatThrownBy(()->jdbc.update("update knowledge.search_history_entry set question_nonce=decode('00','hex') where history_id=?",id)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("update knowledge.search_history_entry set expires_at=created_at+interval '31 days' where history_id=?",id)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("update knowledge.search_history_entry set state='completed' where history_id=?",id)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        jdbc.update("update knowledge.search_history_entry set created_at=statement_timestamp()-interval '31 days',expires_at=statement_timestamp()-interval '1 day' where history_id=?",id);
        assertThatThrownBy(()->history.detail(owner,id)).isInstanceOf(org.notesknowledge.websupport.ApiFailureException.class);tx(()->{history.sweep();return null;});
        assertThat(jdbc.queryForObject("select count(*) from knowledge.search_history_entry where history_id=?",Integer.class,id)).isZero();
        assertThatThrownBy(()->history.list(account(),page.nextCursor(),20)).isInstanceOf(org.notesknowledge.websupport.ApiFailureException.class);
    }
    @Test void historySourceChangeAndAiOffCannotExposeRetainedAiAnswer() throws Exception {
        derive(note,"Synthetic supported source");String handle=accept("all synthetic names");var row=claim();operations.execute(row);var id=history.idForWork(owner,row.id());
        assertThat(history.detail(owner,id).result().aiAnswer()).isNotNull();jdbc.update("update notes.note set ai_enabled=false,ai_generation=ai_generation+1 where note_id=?",note);
        var hidden=history.detail(owner,id);assertThat(hidden.status()).isEqualTo("obsolete");assertThat(hidden.result()).isNull();
        assertThat(jdbc.queryForObject("select result_ciphertext is null from knowledge.search_history_entry where history_id=?",Boolean.class,id)).isTrue();
    }
    @Test void historyAiOffDeterministicMatchesRemainAllowedButChangedSourceIsDenied() throws Exception {
        jdbc.update("update notes.note set ai_enabled=false,markdown='https://example.test/synthetic' where note_id=?",note);
        String handle=accept("all links");var row=claim();operations.execute(row);var id=history.idForWork(owner,row.id());
        assertThat(history.detail(owner,id).result().deterministicResults()).hasSize(1);
        jdbc.update("update notes.note set revision=revision+1,markdown='changed' where note_id=?",note);
        assertThat(history.detail(owner,id).result()).isNull();
    }
    @Test void historyDeletionDuringProviderCallCannotRestoreItsAnswer() throws Exception {
        derive(note,"Synthetic concurrent history source");String handle=accept("all synthetic names");var row=claim();UUID id=history.idForWork(owner,row.id());
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(call->{KnowledgeQueryGate.EvidencePermit permit=call.getArgument(0);permit.requireDispatch();assertOutsideTransaction();entered.countDown();assertThat(release.await(5,TimeUnit.SECONDS)).isTrue();return new StructuredKnowledgePort.Output(List.of(new StructuredKnowledgePort.Claim("Synthetic late answer",List.of("e0"))),false);}).when(provider).generate(any(),any(),eq("extract"),any());
        try(var pool=Executors.newSingleThreadExecutor()){var running=pool.submit(()->operations.execute(row));try{assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();history.remove(owner,id);}finally{release.countDown();}running.get(5,TimeUnit.SECONDS);}
        assertThat(poll(handle).get("status").asText()).isEqualTo("cancelled");assertThat(history.list(owner,null,20).items()).isEmpty();
    }
    @Test void historySuspensionAndTamperedEnvelopeFailClosed() throws Exception {
        String handle=accept("all links");var row=claim();UUID id=history.idForWork(owner,row.id());operations.execute(row);
        jdbc.update("update knowledge.search_history_entry set result_ciphertext=set_byte(result_ciphertext,0,get_byte(result_ciphertext,0)#1) where history_id=?",id);
        var failed=mvc.perform(get("/api/knowledge/history/"+id).cookie(browser.cookie)).andExpect(status().isServiceUnavailable()).andReturn().getResponse();
        assertThat(json.readTree(failed.getContentAsString()).get("code").asText()).isEqualTo("service_unavailable");assertThat(failed.getContentAsString()).doesNotContain("ciphertext","Synthetic",owner.toString());
        jdbc.update("update identity.account set account_state='suspended' where user_id=?",owner);
        assertThat(mvc.perform(get("/api/knowledge/history").cookie(browser.cookie)).andReturn().getResponse().getStatus()).isIn(401,403);
    }
    @Test void historyAccountDeletionConsequenceErasesWithoutResurrection() throws Exception {
        String handle=accept("all links");var row=claim();tx(()->{history.erase(owner);return null;});operations.execute(row);
        assertThat(poll(handle).get("status").asText()).isEqualTo("cancelled");assertThat(history.list(owner,null,20).items()).isEmpty();
    }
    @Test void largerExtractionBatchesKeepEverySourceAndReduceDispatches() throws Exception {
        for(int i=0;i<64;i++)note(owner,true,"Synthetic football record "+i,"Frozen record "+i);
        var visited=new HashSet<String>();var sizes=new ArrayList<Integer>();
        doAnswer(call->{assertOutsideTransaction();KnowledgeQueryGate.EvidencePermit permit=call.getArgument(0);permit.requireDispatch();List<StructuredKnowledgePort.Evidence> supplied=call.getArgument(3);sizes.add(supplied.size());
            supplied.forEach(e->assertThat(visited.add(e.text())).isTrue());return new StructuredKnowledgePort.Output(supplied.stream().map(e->new StructuredKnowledgePort.Claim(e.text(),List.of(e.id()))).toList(),false);
        }).when(provider).generate(any(),any(),eq("extract"),any());
        String handle=accept("which all footballers i have saved");operations.execute(claim());var result=poll(handle);
        assertThat(sizes).containsExactly(32,32,1);assertThat(visited).hasSize(65);assertThat(result.get("totalSources").asLong()).isEqualTo(65);
        assertThat(result.get("result").get("coverage").get("completed").asBoolean()).isTrue();assertThat(result.get("result").get("citations").size()).isEqualTo(65);
    }
    private PrivateQuerySource.SavedDuring syntheticPeriod(){return new PrivateQuerySource.SavedDuring(Instant.parse("2021-01-01T00:00:00Z"),Instant.parse("2022-01-01T00:00:00Z"));}
    @Test void savedPeriodScopesCountsPagesFingerprintAndAttachmentUploadIndependentlyOfParent()throws Exception {
        jdbc.update("update notes.note set created_at='2020-01-01T00:00:00Z'::timestamptz where note_id=?",note);
        UUID inside=note(owner,true,"Period source","https://example.test/inside");
        jdbc.update("update notes.note set created_at='2021-01-01T00:00:00Z'::timestamptz where note_id=?",inside);
        UUID media=syntheticAttachment(Instant.parse("2021-06-01T00:00:00Z"),"image");
        UUID excluded=syntheticAttachment(Instant.parse("2022-01-01T00:00:00Z"),"image");
        var b=sources.capture(owner,List.of("active"),syntheticPeriod());
        assertThat(sources.count(owner,b,true)).isEqualTo(2);
        var page=sources.page(owner,b,null,32,true);assertThat(page.sources()).hasSize(2);
        assertThat(page.sources()).extracting(x->x.expected().attachmentId()).containsExactly(null,media);
        String fingerprint=sources.fingerprint(owner,b,true);jdbc.update("update notes.note set markdown='outside mutation' where note_id=?",note);
        assertThat(sources.fingerprint(owner,b,true)).isEqualTo(fingerprint);
        var e=sources.source(owner,note,media).expected();var out=sources.source(owner,note,excluded).expected();
        assertThat(tx(()->currentSources.matching(List.of(e,out),syntheticPeriod()))).containsExactly(e);
        UUID other=account();assertThat(sources.count(other,sources.capture(other,List.of("active"),syntheticPeriod()),true)).isZero();
    }
    @Test void savedPeriodFiltersVectorAndLexicalCandidatesBeforeTheirBudgets()throws Exception {
        jdbc.update("update notes.note set title='periodword',created_at='2020-01-01T00:00:00Z'::timestamptz where note_id=?",note);derive(note,"periodword old nearest",vector());
        UUID inside=note(owner,true,"periodword","periodword inside evidence");jdbc.update("update notes.note set created_at='2021-01-01T00:00:00Z'::timestamptz where note_id=?",inside);derive(inside,"periodword inside evidence",new float[]{.8f,.6f,0,0,0,0,0,0});
        var expected=sources.note(owner,inside).expected();assertThat(tx(()->currentSources.matching(List.of(expected),syntheticPeriod()))).as("current in-period source before vector scoring").containsExactly(expected);
        assertThat(vectors.search(owner,"note",vector(),2)).as("unfiltered fixture vectors").hasSize(2);
        assertThat(vectors.search(owner,"note",vector(),1,syntheticPeriod())).extracting(x->x.expected().noteId()).containsExactly(inside);
        var r=json.readTree(mvc.perform(post("/api/knowledge/query").cookie(browser.cookie).header("X-CSRF-TOKEN",browser.csrf).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("query","find periodword","scope",Map.of("savedDuring",syntheticPeriod()))))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(r.get("deterministicResults").toString()).contains(inside.toString()).doesNotContain(note.toString());
        assertThat(capturedEvidence()).allSatisfy(e->assertThat(e.text()).doesNotContain("old nearest"));
    }
    @Test void savedPeriodDurableOperationAndEncryptedHistoryKeepExactScopeAndNoWidening()throws Exception {
        jdbc.update("update notes.note set markdown='https://example.test/outside',created_at='2020-01-01T00:00:00Z'::timestamptz where note_id=?",note);
        UUID inside=note(owner,false,"Inside","https://example.test/inside");jdbc.update("update notes.note set created_at='2021-06-01T00:00:00Z'::timestamptz where note_id=?",inside);
        var response=mvc.perform(post("/api/knowledge/query").cookie(browser.cookie).header("X-CSRF-TOKEN",browser.csrf).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("query","all links","scope",Map.of("savedDuring",syntheticPeriod()))))).andExpect(status().isAccepted()).andReturn().getResponse();
        var accepted=json.readTree(response.getContentAsString());assertThat(accepted.get("totalSources").asLong()).isEqualTo(1);var row=claim();operations.execute(row);
        var result=poll(accepted.get("operationId").asText());assertThat(result.get("status").asText()).isEqualTo("completed");
        assertThat(result.get("result").get("deterministicResults").toString()).contains("https://example.test/inside").doesNotContain("https://example.test/outside");
        var entry=history.detail(owner,history.idForWork(owner,row.id()));assertThat(entry.savedDuring()).isEqualTo(syntheticPeriod());assertThat(entry.query()).isEqualTo("all links");
        assertThat(history.list(owner,null,20).items().getFirst().savedDuring()).isEqualTo(syntheticPeriod());
        verify(provider,never()).embedQuery(any(),any());verify(provider,never()).generate(any(),any(),any(),any());
    }
    private UUID upload(String kind)throws Exception{var response=mvc.perform(multipart("/api/notes/"+note+"/attachments").file(org.notesknowledge.notes.DerivationMediaFixtures.media(kind)).cookie(browser.cookie).header("X-CSRF-TOKEN",browser.csrf)).andExpect(status().isCreated()).andReturn().getResponse();return UUID.fromString(json.readTree(response.getContentAsString()).get("id").asText());}
    private static class SyntheticWorkerLoss extends RuntimeException { }
    private void derive(UUID id,String text){derive(id,text,vector());}
    private void derive(UUID id,String text,float[] embedding){UUID user=jdbc.queryForObject("select owner_user_id from notes.note where note_id=?",UUID.class,id);var e=new PrivateAiSourceCurrentness.Expected(user,id,null,1,1,null);
        var l=EmbeddingLineage.create(configuration,new ProcessingPolicyService.AcknowledgedProcessingPolicy(policy,1,configuration.approvedPolicyFingerprint()),"note");tx(()->{representations.activate(e,l,List.of(new DerivedSegment(text,"note_text","",0,text.length(),null,null,null,null,null,null,null)),List.of(embedding));return null;});}
    private <T>T tx(java.util.function.Supplier<T> fn){return new TransactionTemplate(transactions).execute(s->fn.get());}
    private org.springframework.test.web.servlet.ResultActions query(String q)throws Exception{return mvc.perform(post("/api/knowledge/query").cookie(browser.cookie).header("X-CSRF-TOKEN",browser.csrf).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("query",q))));}
    private String accept(String q)throws Exception{var response=query(q).andExpect(status().isAccepted()).andReturn().getResponse();String handle=json.readTree(response.getContentAsString()).get("operationId").asText();assertThat(response.getHeader("Location")).isEqualTo("/api/knowledge/operations/"+handle);return handle;}
    private tools.jackson.databind.JsonNode poll(String handle)throws Exception{return json.readTree(mvc.perform(get("/api/knowledge/operations/"+handle).cookie(browser.cookie)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());}
    private KnowledgeOperationRepository.Row claim(){
        var claimed=operations.claim(2);
        // Never hide accidental claims of work left by another fixture behind a post-claim filter.
        assertThat(claimed.isEmpty()).isFalse();
        for(var row:claimed)assertThat(row.owner()).isEqualTo(owner);
        return claimed.getFirst();
    }
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
