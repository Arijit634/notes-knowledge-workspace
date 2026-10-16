package org.notesknowledge.knowledge;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.notesknowledge.LeaseOwner;
import org.notesknowledge.identity.IdentitySessionPrincipal;
import org.notesknowledge.knowledge.spi.PrivateAiSourceCurrentness.Expected;
import org.notesknowledge.notes.MeaningfulMediaFixtures;
import org.notesknowledge.security.RateLimitPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.session.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.*;

/** Tier 1 only: real upload/parser/database/query pipeline, controlled semantic/answer adapters.
 * The relevance judgments below are frozen separately from the query strings. This proves wiring,
 * NOT Gemini relevance, transcription/OCR accuracy, exhaustive media coverage or native media vectors.
 */
@Tag("DATABASE") @Tag("API") @Tag("SECURITY") @Tag("RETRIEVAL") @Tag("EVALUATION")
@Testcontainers @SpringBootTest(properties={"knowledge.operations.scheduling-enabled=false","knowledge.derivation.scheduling-enabled=false"})
@AutoConfigureMockMvc @org.springframework.context.annotation.Import(org.notesknowledge.notes.DerivationMediaFixtures.class)
class MultimodalPipelineQualificationTest {
    @Container static final PostgreSQLContainer postgres=new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18-trixie")
        .withDatabaseName("multimodal_pipeline").withUsername("synthetic_migrator").withPassword("synthetic-qualification-password");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",postgres::getJdbcUrl);r.add("spring.datasource.username",postgres::getUsername);r.add("spring.datasource.password",postgres::getPassword);
        r.add("identity.rate.key-base64",()->"AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=");
    }
    @Autowired JdbcTemplate jdbc; @Autowired MockMvc mvc; @Autowired ObjectMapper json;
    @Autowired SessionRepository<? extends Session> sessions; @Autowired PlatformTransactionManager transactions;
    @Autowired KnowledgeWorkRepository rows; @Autowired KnowledgeWorkService work; @Autowired DerivationExecutor executor;
    @Autowired ExactPrivateVectorSearch vectors;
    @Autowired PrivateRepresentationRepository representations;
    @Autowired DerivationReconciler reconciler;
    @MockitoBean AiDerivationProperties config; @MockitoBean KnowledgePolicyProperties policyConfig;
    @MockitoBean TextEmbeddingPort embeddings; @MockitoBean MediaUnderstandingPort media;
    @MockitoBean StructuredKnowledgePort answer; @MockitoBean RateLimitPort rates;
    UUID owner,note,policy; Cookie cookie;String csrf;
    List<StructuredKnowledgePort.Evidence> selected=List.of();
    static final List<String> FACTS=List.of("The fictional beacon flashes amber at dusk.","The fictional deep vault stores copper keys.",
        "The fictional archive opens at dawn; its door is violet.","A blue square appears in the left-hand image region.",
        "The scanned page shows a blue square and ASTER.","The fictional observatory is on planet Aster. Its dome is silver.",
        "The sampled video scene shows a blue square and a red circle above ASTER.");
    static final Map<String,Integer> QUESTIONS=Map.ofEntries(
        Map.entry("What color does the signal show when evening arrives?",0),Map.entry("Where were the metal access pieces kept?",1),
        Map.entry("When can I enter the repository with the purple entrance?",2),Map.entry("Where was that picture with two colored shapes?",3),
        Map.entry("What did the photographed page show?",4),Map.entry("What place did the voice name for the silver domed building?",5),
        Map.entry("Which clip had the geometric shapes and a label?",6),Map.entry("Where is the obsvtry on plnt astr?",5));
    @BeforeEach void prepare()throws Exception {
        when(config.configured()).thenReturn(true);when(config.provider()).thenReturn("synthetic");when(config.tier()).thenReturn("synthetic");when(config.region()).thenReturn("synthetic");
        when(config.embeddingModel()).thenReturn("frozen-semantic-oracle");when(config.mediaModel()).thenReturn("frozen-media-oracle");when(config.modelRevision()).thenReturn("v1");
        when(config.configurationId()).thenReturn("fixture-v1");when(config.adapterVersion()).thenReturn("fixture-v1");when(config.dimension()).thenReturn(8);when(config.operator()).thenReturn("cosine");when(config.normalization()).thenReturn("none");
        when(config.approvedPolicyFingerprint()).thenReturn("a".repeat(64));when(rates.evaluate(any())).thenReturn(new RateLimitPort.Allowed());
        when(embeddings.available()).thenReturn(true);when(media.available()).thenReturn(true);when(answer.available()).thenReturn(true);
        when(embeddings.embed(any(),any())).thenAnswer(call->{outside();AiProcessingGate.SourceAiPermit permit=call.getArgument(0);permit.requireDispatch();List<String> texts=call.getArgument(1);return texts.stream().map(MultimodalPipelineQualificationTest::textVector).toList();});
        when(answer.embedQuery(any(),any())).thenAnswer(call->{outside();KnowledgeQueryGate.QueryPermit permit=call.getArgument(0);permit.requireDispatch();return axis(QUESTIONS.getOrDefault(call.getArgument(1),7));});
        when(answer.generate(any(),any(),any(),any())).thenAnswer(call->{outside();KnowledgeQueryGate.EvidencePermit permit=call.getArgument(0);permit.requireDispatch();selected=List.copyOf(call.getArgument(3));
            int gold=QUESTIONS.getOrDefault(call.getArgument(1),7);var claims=new ArrayList<StructuredKnowledgePort.Claim>();
            for(var e:selected)if(gold<FACTS.size()&&e.text().contains(FACTS.get(gold)))claims.add(new StructuredKnowledgePort.Claim(FACTS.get(gold),List.of(e.id())));
            return new StructuredKnowledgePort.Output(claims,false);});
        String code="pipeline-"+UUID.randomUUID();when(policyConfig.code()).thenReturn(code);
        policy=jdbc.queryForObject("insert into knowledge.processing_policy(policy_code,policy_version,policy_fingerprint,disclosure_revision,effective_at) values(?,1,?,'synthetic-1',clock_timestamp()-interval '1 minute') returning processing_policy_id",UUID.class,code,"a".repeat(64));
        owner=jdbc.queryForObject("select uuidv7()",UUID.class);String email="fictional-"+owner+"@example.test";
        jdbc.update("insert into identity.account(user_id,canonical_email,display_email,email_verified_at,account_state,created_at,updated_at) values(?,?,?,now(),'active',now(),now())",owner,email,email);
        jdbc.update("insert into knowledge.processing_policy_acknowledgement(user_id,processing_policy_id,disclosure_revision) values(?,?,'synthetic-1')",owner,policy);
        note=note("Fictional collection","Current fictional media library",true);browser();selected=List.of();
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    @ParameterizedTest @ValueSource(strings={"note","long-note","pdf","image","scan","audio","video"})
    void validBytesTraverseUploadDerivationStorageRetrievalAndGroundedCitation(String kind)throws Exception {
        int gold=List.of("note","long-note","pdf","image","scan","audio","video").indexOf(kind);UUID attachment=null;String modality=kind;
        if(kind.equals("note")||kind.equals("long-note")) {
            jdbc.update("update notes.note set markdown=? where note_id=?",kind.equals("long-note")?"# Inventory\n"+("Ordinary fictional inventory without an answer.\n".repeat(300))+"\n# Deep vault\n"+FACTS.get(gold):FACTS.get(gold),note);modality="note";
        } else {
            attachment=upload(kind);when(media.describe(any(),any(),any())).thenAnswer(call->{outside();AiProcessingGate.SourceAiPermit p=call.getArgument(0);p.requireDispatch();
                byte[] actual=call.getArgument(1);
                if(kind.equals("scan"))try(var pdf=org.apache.pdfbox.Loader.loadPDF(actual)) {
                    assertThat(pdf.getNumberOfPages()).isEqualTo(1);assertThat(new org.apache.pdfbox.text.PDFTextStripper().getText(pdf)).isBlank();
                    assertThat(pdf.getPage(0).getResources().getXObjectNames()).isNotEmpty();
                } else if(kind.equals("image")) {
                    // Upload deliberately normalizes image bytes; compare decoded pixels, not PNG encoding.
                    var decoded=javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(actual));
                    assertThat(decoded.getWidth()).isEqualTo(160);assertThat(decoded.getHeight()).isEqualTo(96);
                    assertThat(decoded.getRGB(24,24)&0xffffff).isEqualTo(0x0000ff);assertThat(decoded.getRGB(116,30)&0xffffff).isEqualTo(0xff0000);
                } else if(kind.equals("pdf"))try(var pdf=org.apache.pdfbox.Loader.loadPDF(actual)) {
                    assertThat(pdf.getNumberOfPages()).isEqualTo(3);
                    assertThat(new org.apache.pdfbox.text.PDFTextStripper().getText(pdf)).contains(FACTS.get(gold));
                } else assertThat(actual).isEqualTo(MeaningfulMediaFixtures.media(kind).getBytes());
                if(kind.equals("audio"))return List.of(new DerivedSegment(FACTS.get(gold),"transcript","",null,null,null,0.,p.source().durationSeconds(),null,null,null,null));
                if(kind.equals("pdf"))return List.of(); // Native facts remain authoritative parser provenance; vision has no additional fact here.
                return List.of(segment(kind,FACTS.get(gold)));});
            if(kind.equals("scan"))modality="pdf";
        }
        derive(attachment,modality);
        assertThat(jdbc.queryForObject("select count(*) from knowledge.private_derived_representation where owner_user_id=? and state='ready' and derivation_class='text_surrogate'",Integer.class,owner)).isEqualTo(1);
        String question=QUESTIONS.entrySet().stream().filter(e->e.getValue()==gold).map(Map.Entry::getKey).sorted().findFirst().orElseThrow();
        var result=query(question);assertThat(result.get("degraded").isNull()).isTrue();assertThat(result.get("aiAnswer").get("claims").get(0).asText()).isEqualTo(FACTS.get(gold));
        assertThat(selected).hasSize(1);assertThat(selected.getFirst().text()).contains(FACTS.get(gold));
        var citations=result.get("citations");assertThat(citations.size()).isEqualTo(1);var citation=citations.get(0);assertThat(citation.get("noteId").asText()).isEqualTo(note.toString());
        if(attachment==null)assertThat(citation.get("attachmentId").isNull()).isTrue();else assertThat(citation.get("attachmentId").asText()).isEqualTo(attachment.toString());
        var location=citation.get("location");
        switch(kind) {
            case "note","long-note" -> {assertThat(location.get("kind").asText()).isEqualTo("note_text");if(kind.equals("long-note"))assertThat(location.get("start").asInt()).isGreaterThan(10000);}
            case "pdf" -> assertThat(location.get("page").asInt()).isEqualTo(3);
            case "scan" -> assertThat(location.get("page").asInt()).isEqualTo(1);
            case "image" -> {assertThat(location.get("kind").asText()).isEqualTo("image_region");assertThat(location.get("x").asDouble()).isEqualTo(.05);assertThat(location.get("width").asDouble()).isEqualTo(.3);}
            case "audio","video" -> {assertThat(location.get("timeStart").asDouble()).isZero();if(kind.equals("audio"))assertThat(location.get("timeEnd").asDouble()).isGreaterThan(1);else assertThat(location.get("timeEnd").asDouble()).isEqualTo(1);}
        }
        assertThat(result.get("coverage").get("boundary").asText()).isEqualTo("bounded");assertThat(result.get("coverage").get("completed").asBoolean()).isFalse();
        if(kind.equals("pdf"))verify(media).describe(any(),any(),any());
        var unsupported=query("What is the fictional antimatter refrigerator serial number?");assertInsufficient(unsupported);
        assertThat(vectors.search(owner,modality,axis(gold),20)).isNotEmpty();
        evidence("multimodal-pipeline-"+kind,Map.of("tier","Tier 1 controlled providers; NOT Gemini quality","modality",kind,
            "positiveCases",1,"unsupportedCases",1,"correctSelectedEvidence",true,"correctCitation",true,
            "groundedClaim",true,"insufficientEvidence",true,"coverage","bounded, not exhaustive","representation","text_surrogate"));
    }
    @Test void unreadableScannedPageFailsWithoutInventedSegmentsOrAnswer()throws Exception {
        UUID id=upload("unreadable");when(media.describe(any(),any(),any())).thenReturn(List.of());var claim=derive(id,"pdf");
        assertThat(state(claim)).isEqualTo("failed");assertThat(jdbc.queryForObject("select count(*) from knowledge.private_derived_segment where owner_user_id=?",Integer.class,owner)).isZero();
        assertInsufficient(query("What did the photographed page show?"));
    }
    @Test void pageEighteenFactSurvivesRealExtractionAndCitesOnlyItsActualPage()throws Exception {
        var original=org.notesknowledge.notes.QualityMediaFixtures.pdf(18,false,false);
        var response=mvc.perform(multipart("/api/notes/"+note+"/attachments")
            .file(original).cookie(cookie).header("X-CSRF-TOKEN",csrf))
            .andExpect(status().isCreated()).andReturn().getResponse();
        UUID attachment=UUID.fromString(json.readTree(response.getContentAsString()).get("id").asText());
        var permits=new ArrayList<AiProcessingGate.SourceAiPermit>();var scopes=new ArrayList<MediaUnderstandingRequest>();
        when(media.describe(any(),any(),any())).thenAnswer(call->{
            outside();AiProcessingGate.SourceAiPermit p=call.getArgument(0);p.requireDispatch();permits.add(p);scopes.add(call.getArgument(2));
            assertThat((byte[])call.getArgument(1)).isEqualTo(original.getBytes());
            return List.of();
        });
        String question="What amount did the report recommend setting aside for equipment?";
        doAnswer(call->{outside();AiProcessingGate.SourceAiPermit p=call.getArgument(0);p.requireDispatch();
            List<String> texts=call.getArgument(1);return texts.stream().map(t->axis(t.contains("740 fictional credits")?0:7)).toList();}).when(embeddings).embed(any(),any());
        doAnswer(call->{outside();KnowledgeQueryGate.QueryPermit p=call.getArgument(0);p.requireDispatch();return axis(0);}).when(answer).embedQuery(any(),any());
        doAnswer(call->{outside();KnowledgeQueryGate.EvidencePermit p=call.getArgument(0);p.requireDispatch();
            selected=List.copyOf(call.getArgument(3));var claims=selected.stream().filter(e->e.text().contains("740 fictional credits"))
                .map(e->new StructuredKnowledgePort.Claim("740 fictional credits",List.of(e.id()))).toList();return new StructuredKnowledgePort.Output(claims,false);}).when(answer).generate(any(),any(),any(),any());
        assertThat(state(derive(attachment,"pdf"))).isEqualTo("completed");verify(media,times(3)).describe(any(),any(),any());
        var distinctPermits=Collections.newSetFromMap(new IdentityHashMap<AiProcessingGate.SourceAiPermit,Boolean>());distinctPermits.addAll(permits);
        assertThat(distinctPermits).hasSize(3);
        assertThat(scopes.stream().map(MediaUnderstandingRequest::firstPage)).containsExactly(1,9,17);
        assertThat(scopes.stream().map(MediaUnderstandingRequest::lastPage)).containsExactly(8,16,18);
        assertThat(jdbc.queryForObject("select count(*) from knowledge.private_derived_segment where owner_user_id=?",Integer.class,owner)).isEqualTo(18);
        var result=query(question);assertThat(selected).hasSize(1);assertThat(result.get("aiAnswer").get("claims").get(0).asText()).isEqualTo("740 fictional credits");
        var citation=result.get("citations").get(0);assertThat(citation.get("attachmentId").asText()).isEqualTo(attachment.toString());
        assertThat(citation.get("location").get("page").asInt()).isEqualTo(18);assertThat(result.get("coverage").get("completed").asBoolean()).isFalse();
        evidence("page-eighteen-pipeline",Map.of("provider","controlled doubles, not Gemini relevance","pages",18,"segments",18,
            "correctAttachment",true,"correctPage",18,"generationEvidenceSegments",1,"coverage","bounded, not exhaustive"));
    }
    @Test void partialTranscriptAndSampledFramesCannotFabricateMissingFactsOrCompleteCoverage()throws Exception {
        for(String kind:List.of("audio","video")) {
            UUID id=upload(kind);when(media.describe(any(),any(),any())).thenReturn(List.of(segment(kind,"A limited fictional excerpt without the requested destination.")));
            assertThat(state(derive(id,kind))).isEqualTo("completed");
        }
        assertThat(jdbc.queryForObject("select count(*) from knowledge.private_derived_segment where owner_user_id=?",Integer.class,owner)).isEqualTo(2);
        assertInsufficient(query("What place did the voice name for the silver domed building?"));assertInsufficient(query("Which clip had the geometric shapes and a label?"));
    }
    @Test void mixedPdfPreservesNativeTextAndReadsScannedPageWithOriginalProvenance()throws Exception {
        UUID id=upload("mixed");
        when(media.describe(any(),any(),any())).thenAnswer(call->{
            outside();AiProcessingGate.SourceAiPermit permit=call.getArgument(0);permit.requireDispatch();
            var scope=(MediaUnderstandingRequest)call.getArgument(2);assertThat(scope.firstPage()).isEqualTo(1);assertThat(scope.lastPage()).isEqualTo(3);
            return List.of(new DerivedSegment(FACTS.get(4),"pdf_text","",null,null,3,null,null,null,null,null,null));
        });
        assertThat(state(derive(id,"pdf"))).isEqualTo("completed");verify(media).describe(any(),any(),any());
        assertThat(jdbc.queryForObject("select count(*) from knowledge.private_derived_segment where owner_user_id=?",Integer.class,owner)).isEqualTo(3);
        var result=query("What did the photographed page show?");
        assertThat(result.get("aiAnswer").get("claims").get(0).asText()).isEqualTo(FACTS.get(4));
        assertThat(result.get("citations").get(0).get("location").get("page").asInt()).isEqualTo(3);
    }
    @Test void missingScannedPageOrPartialProviderFailureCannotActivateNativeOnlyPdf()throws Exception {
        UUID id=upload("mixed");when(media.describe(any(),any(),any())).thenReturn(List.of());
        assertThat(state(derive(id,"pdf"))).isEqualTo("failed");
        assertThat(jdbc.queryForObject("select count(*) from knowledge.private_derived_representation where owner_user_id=? and state='ready'",Integer.class,owner)).isZero();
        verify(embeddings,never()).embed(any(),any());assertInsufficient(query("What did the photographed page show?"));
    }
    @Test void audioRetainsSeparateTopicsWithOriginalTimeAndImageRetainsGroundedOcr()throws Exception {
        UUID audio=upload("audio"),image=upload("image");
        when(media.describe(any(),any(),any())).thenAnswer(call->{
            outside();AiProcessingGate.SourceAiPermit p=call.getArgument(0);p.requireDispatch();
            var scope=(MediaUnderstandingRequest)call.getArgument(2);
            if(p.source().modality().equals("audio")) {
                assertThat((byte[])call.getArgument(1)).isEqualTo(MeaningfulMediaFixtures.media("audio").getBytes());
                assertThat(scope.durationSeconds()).isEqualTo(p.source().durationSeconds());
                double split=scope.durationSeconds()/2;
                return List.of(new DerivedSegment("The fictional observatory is on planet Aster.","transcript","",null,null,null,0.,split,null,null,null,null),
                    new DerivedSegment("Its dome is silver. [uncertain trailing word]","transcript","",null,null,null,split,scope.durationSeconds(),null,null,null,null));
            }
            return List.of(new DerivedSegment("Blue square left of red circle.","whole_image","",null,null,null,null,null,null,null,null,null),
                new DerivedSegment("ASTER","image_region","",null,null,null,null,null,.05,.65,.6,.3));
        });
        assertThat(state(derive(audio,"audio"))).isEqualTo("completed");assertThat(state(derive(image,"image"))).isEqualTo("completed");
        var timed=jdbc.queryForList("select time_start,time_end from knowledge.private_derived_segment where owner_user_id=? and segment_kind='transcript' order by ordinal",owner);
        assertThat(timed).hasSize(2);
        assertThat(jdbc.queryForList("select surrogate_text from knowledge.private_derived_segment where owner_user_id=?",String.class,owner)).contains("ASTER","Its dome is silver. [uncertain trailing word]");
    }
    @Test void unsupportedEmptyMediaCannotCreateAnAttachmentOrProviderWork()throws Exception {
        mvc.perform(multipart("/api/notes/"+note+"/attachments").file(new org.springframework.mock.web.MockMultipartFile("file","empty.mp4","video/mp4",new byte[0]))
            .cookie(cookie).header("X-CSRF-TOKEN",csrf)).andExpect(status().isUnprocessableEntity());
        assertThat(jdbc.queryForObject("select count(*) from notes.attachment where note_id=?",Integer.class,note)).isZero();verifyNoInteractions(media,embeddings);
        assertInsufficient(query("Which clip had the geometric shapes and a label?"));
    }
    @Test void overlappingImageAndAudioInOneNoteRetainIndependentAttachmentEvidence()throws Exception {
        UUID image=upload("image"),audio=upload("audio");
        when(media.describe(any(),any(),any())).thenAnswer(call->{outside();AiProcessingGate.SourceAiPermit permit=call.getArgument(0);permit.requireDispatch();
            return List.of(permit.source().modality().equals("image")?segment("image",FACTS.get(3)):
                new DerivedSegment(FACTS.get(5),"transcript","",null,null,null,0.,permit.source().durationSeconds(),null,null,null,null));});
        assertThat(state(derive(image,"image"))).isEqualTo("completed");assertThat(state(derive(audio,"audio"))).isEqualTo("completed");
        var r=query("What place did the voice name for the silver domed building?");
        assertThat(r.get("aiAnswer").get("claims").get(0).asText()).isEqualTo(FACTS.get(5));assertThat(selected).hasSize(1);
        assertThat(r.get("citations").size()).isEqualTo(1);assertThat(r.get("citations").get(0).get("attachmentId").asText()).isEqualTo(audio.toString());
        assertThat(jdbc.queryForObject("select markdown from notes.note where note_id=?",String.class,note)).isEqualTo("Current fictional media library");
    }
    @Test void nonSpeechAudioReindexesWithoutLegacyReuseAndRetainsQualifiedTemporalEvidence()throws Exception {
        UUID attachment=upload("tones");
        var expected=new Expected(owner,note,attachment,1,1,1L);
        var current=EmbeddingLineage.create(config,new ProcessingPolicyService.AcknowledgedProcessingPolicy(policy,1,"a".repeat(64)),"audio");
        String legacyConfiguration=current.configuration().replace("audio-speech-acoustic-surrogate-v3","media-scoped-coverage-surrogate-v2");
        var legacy=new EmbeddingLineage(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
            .digest(legacyConfiguration.getBytes(StandardCharsets.UTF_8))),legacyConfiguration,"audio",8,"cosine",policy,"none");
        // Model an already-completed prior extraction, not a pending new upload.
        jdbc.update("delete from knowledge.knowledge_work_intent where owner_user_id=? and source_attachment_id=?",owner,attachment);
        new TransactionTemplate(transactions).executeWithoutResult(s->representations.activate(expected,legacy,
            List.of(new DerivedSegment("Prior speech-only surrogate.","transcript","",null,null,null,0.,4.,null,null,null,null)),List.of(axis(7))));
        assertThat(vectors.search(owner,"audio",axis(7),10)).isEmpty();
        org.notesknowledge.knowledge.spi.PrivateDerivationSource.Cursor cursor=null;int pages=0;
        do {cursor=reconciler.page(cursor);assertThat(++pages).isLessThan(32);}while(cursor!=null);
        assertThat(jdbc.queryForObject("select count(*) from knowledge.knowledge_work_intent where owner_user_id=? and source_attachment_id=? and target_lineage_id=? and state='queued'",Integer.class,owner,attachment,current.id())).isEqualTo(1);
        var pending=json.readTree(mvc.perform(get("/api/notes/"+note+"/ai-processing").cookie(cookie)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("attachments");
        assertThat(pending.size()).isEqualTo(1);assertThat(pending.get(0).get("status").asText()).isEqualTo("reprocessing");
        when(media.describe(any(),any(),any())).thenAnswer(call->{
            outside();AiProcessingGate.SourceAiPermit permit=call.getArgument(0);permit.requireDispatch();
            assertThat((byte[])call.getArgument(1)).isEqualTo(MeaningfulMediaFixtures.media("tones").getBytes());
            MediaUnderstandingRequest request=call.getArgument(2);
            return GoogleDerivationAdapters.validateMediaOutput(request,new GoogleDerivationAdapters.MediaOutput(true,List.of(),true,List.of(
                new DerivedSegment("Possibly electronic tones change from higher to lower.","transcript","Acoustic description",null,null,null,0.,4.,null,null,null,null))));
        });
        var claim=work.claim(new LeaseOwner("fictional-audio-reindex"),10).stream().filter(c->c.intent().expected().equals(expected)).findFirst().orElseThrow();
        executor.execute(claim);assertThat(state(claim)).isEqualTo("completed");
        var ready=json.readTree(mvc.perform(get("/api/notes/"+note+"/ai-processing").cookie(cookie)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("attachments");
        assertThat(ready.get(0).get("status").asText()).isEqualTo("ready");
        assertThat(jdbc.queryForObject("select state from knowledge.private_derived_representation where owner_user_id=? and source_attachment_id=? and lineage_id=?",String.class,owner,attachment,legacy.id())).isEqualTo("obsolete");
        var candidates=vectors.search(owner,"audio",axis(7),10);assertThat(candidates).hasSize(1);
        assertThat(candidates.getFirst().expected()).isEqualTo(expected);
        doAnswer(call->{
            outside();KnowledgeQueryGate.EvidencePermit permit=call.getArgument(0);permit.requireDispatch();selected=List.copyOf(call.getArgument(3));
            assertThat(selected).hasSize(1);assertThat(selected.getFirst().text()).startsWith("Audio description (model inference, not spoken words): ");
            return new StructuredKnowledgePort.Output(List.of(new StructuredKnowledgePort.Claim("The description suggests higher tones followed by lower tones.",List.of("e0"))),false);
        }).when(answer).generate(any(),any(),any(),any());
        var result=query("What happens to the tones in this recording?");
        assertThat(result.get("aiAnswer").get("claims").get(0).asText()).contains("description suggests");
        var citation=result.get("citations").get(0);assertThat(citation.get("attachmentId").asText()).isEqualTo(attachment.toString());
        assertThat(citation.get("location").get("kind").asText()).isEqualTo("transcript");
        assertThat(citation.get("location").get("timeStart").asDouble()).isZero();assertThat(citation.get("location").get("timeEnd").asDouble()).isEqualTo(4.);
        String etag=mvc.perform(get("/api/notes/"+note).cookie(cookie)).andExpect(status().isOk()).andReturn().getResponse().getHeader("ETag");
        mvc.perform(put("/api/notes/"+note+"/ai-access").cookie(cookie).header("X-CSRF-TOKEN",csrf).header("If-Match",etag)
            .contentType("application/json").content("{\"aiEnabled\":false}")).andExpect(status().isOk());
        assertThat(vectors.search(owner,"audio",axis(7),10)).isEmpty();assertThat(query("What happens to the tones in this recording?").get("aiAnswer").isNull()).isTrue();
        verify(media,times(1)).describe(any(),any(),any());verify(answer,times(1)).generate(any(),any(),any(),any());
        mvc.perform(get("/api/notes/"+note+"/attachments/"+attachment+"/content").cookie(cookie)).andExpect(status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().bytes(MeaningfulMediaFixtures.media("tones").getBytes()));
    }
    @Test void realisticFrozenQuestionsKeepIndependentJudgmentsAndExcludeObsoleteAndAiOffMaterial()throws Exception {
        var expectedIds=new HashMap<Integer,UUID>();
        for(int i=0;i<FACTS.size();i++){UUID id=note("Fictional current evidence "+i,FACTS.get(i),true);expectedIds.put(i,id);UUID prior=note;note=id;derive(null,"note");note=prior;}
        for(int i=0;i<30;i++){UUID id=note("Near duplicate inventory "+i,"Ordinary fictional inventory without any supported destination.",true);UUID prior=note;note=id;derive(null,"note");note=prior;}
        UUID excluded=note("Aster private-only","Aster AI-OFF fictional observatory",false);
        UUID obsolete=note("Old Aster","Aster old destination",true);UUID prior=note;note=obsolete;derive(null,"note");note=prior;
        jdbc.update("update notes.note set lifecycle_state='trashed',trashed_at=clock_timestamp() where note_id=?",obsolete);
        var results=new ArrayList<FrozenRetrievalMetrics.Ranking>();
        for(var q:QUESTIONS.entrySet()) {
            var ranked=vectors.search(owner,"note",axis(q.getValue()),20);var ids=ranked.stream().map(c->c.expected().noteId().toString()).toList();
            results.add(FrozenRetrievalMetrics.score(ids,Map.of(expectedIds.get(q.getValue()).toString(),2)));
            assertThat(ids).doesNotContain(excluded.toString(),obsolete.toString());var response=query(q.getKey());
            assertThat(response.get("aiAnswer").get("claims").get(0).asText()).isEqualTo(FACTS.get(q.getValue()));
            assertThat(selected).hasSize(1);assertThat(response.get("citations").get(0).get("noteId").asText()).isEqualTo(expectedIds.get(q.getValue()).toString());
        }
        // Tiny seeded-oracle tier, deliberately not the production 90% Gemini maturity gate.
        for(var score:results){assertThat(score.recall1()).isEqualTo(1);assertThat(score.recall5()).isEqualTo(1);assertThat(score.recall10()).isEqualTo(1);assertThat(score.recall20()).isEqualTo(1);assertThat(score.mrr()).isEqualTo(1);assertThat(score.ndcg10()).isEqualTo(1);}
        mvc.perform(post("/api/notes/search").cookie(cookie).header("X-CSRF-TOKEN",csrf).contentType("application/json").content("{\"query\":\"Aster\"}"))
            .andExpect(status().isOk()).andExpect(r->assertThat(r.getResponse().getContentAsString()).contains(excluded.toString()));
        assertInsufficient(query("What is the fictional antimatter refrigerator serial number?"));
        evidence("realistic-frozen-pipeline",Map.of("tier","Tier 1 seeded oracle; NOT Gemini relevance","questions",QUESTIONS.size(),
            "independentlyFrozenJudgments",FACTS.size(),"nearDuplicateDistractors",30,"rankings",results,"correctEvidenceSelection",QUESTIONS.size(),
            "correctCitations",QUESTIONS.size(),"correctGroundedClaims",QUESTIONS.size(),"unsupportedQuestions",1,"unsupportedCorrect",1));
    }
    private UUID upload(String kind)throws Exception {var r=mvc.perform(multipart("/api/notes/"+note+"/attachments").file(MeaningfulMediaFixtures.media(kind)).cookie(cookie).header("X-CSRF-TOKEN",csrf)).andExpect(status().isCreated()).andReturn().getResponse();return UUID.fromString(json.readTree(r.getContentAsString()).get("id").asText());}
    private KnowledgeWork.Claim derive(UUID attachment,String modality) {
        var expected=new Expected(owner,note,attachment,1,1,attachment==null?null:1L);var lineage=EmbeddingLineage.create(config,new ProcessingPolicyService.AcknowledgedProcessingPolicy(policy,1,"a".repeat(64)),modality);
        new TransactionTemplate(transactions).execute(s->rows.enqueue(attachment==null?KnowledgeWork.Kind.NOTE:KnowledgeWork.Kind.ATTACHMENT,expected,lineage.id()));
        var claim=work.claim(new LeaseOwner("fictional-pipeline"),10).stream().filter(c->c.intent().expected().equals(expected)).findFirst().orElseThrow();executor.execute(claim);
        return claim;
    }
    private String state(KnowledgeWork.Claim c){return jdbc.queryForObject("select state from knowledge.knowledge_work_intent where knowledge_work_intent_id=?",String.class,c.intent().id());}
    private UUID note(String title,String body,boolean ai){return jdbc.queryForObject("insert into notes.note(note_id,owner_user_id,title,markdown,lifecycle_state,ai_enabled,revision,ai_generation,created_at,updated_at) values(uuidv7(),?,?,?,'active',?,1,1,clock_timestamp(),clock_timestamp()) returning note_id",UUID.class,owner,title,body,ai);}
    private JsonNode query(String query)throws Exception {selected=List.of();var r=mvc.perform(post("/api/knowledge/query").cookie(cookie).header("X-CSRF-TOKEN",csrf).contentType("application/json").content(json.writeValueAsBytes(Map.of("query",query)))).andExpect(status().isOk()).andReturn().getResponse();return json.readTree(r.getContentAsString());}
    private void assertInsufficient(JsonNode r){assertThat(r.get("degraded").isNull()).isTrue();assertThat(r.get("aiAnswer").isNull()).isTrue();assertThat(r.get("citations").isEmpty()).isTrue();assertThat(r.get("insufficientEvidence").asBoolean()).isTrue();assertThat(r.get("coverage").get("completed").asBoolean()).isFalse();}
    private void evidence(String name,Map<String,?> data)throws Exception {var directory=java.nio.file.Path.of("target/retrieval-evaluation");java.nio.file.Files.createDirectories(directory);java.nio.file.Files.writeString(directory.resolve(name+".json"),json.writeValueAsString(data));}
    private static DerivedSegment segment(String kind,String text){return switch(kind){case "image"->new DerivedSegment(text,"image_region","",null,null,null,null,null,.05,.08,.3,.5);case "scan"->new DerivedSegment(text,"pdf_text","",null,null,1,null,null,null,null,null,null);case "audio"->new DerivedSegment(text,"transcript","",null,null,null,0.,1.,null,null,null,null);case "video"->new DerivedSegment(text,"video_scene","",null,null,null,0.,1.,null,null,null,null);default->throw new IllegalArgumentException();};}
    private static float[] textVector(String text){for(int i=0;i<FACTS.size();i++)if(text.contains(FACTS.get(i)))return axis(i);return axis(7);}
    private static float[] axis(int index){float[] v=new float[8];v[index]=1;return v;}
    private static void outside(){assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();}
    private void browser()throws Exception {
        Session session=sessions.createSession();var context=SecurityContextHolder.createEmptyContext();context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(new IdentitySessionPrincipal(owner),null,List.of(new SimpleGrantedAuthority("ROLE_USER"))));session.setAttribute("SPRING_SECURITY_CONTEXT",context);save(session);
        cookie=new Cookie("SESSION",Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8)));var r=mvc.perform(get("/api/auth/csrf").cookie(cookie)).andExpect(status().isOk()).andReturn().getResponse();if(r.getCookie("SESSION")!=null)cookie=r.getCookie("SESSION");csrf=json.readTree(r.getContentAsString()).get("csrfToken").asText();
    }
    @SuppressWarnings({"rawtypes","unchecked"}) private void save(Session s){((SessionRepository)sessions).save(s);}
}
