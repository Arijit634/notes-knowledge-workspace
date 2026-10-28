package org.notesknowledge.knowledge;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import com.sun.net.httpserver.HttpServer;
import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.notesknowledge.DispatchCoordinator;
import org.notesknowledge.knowledge.spi.PrivateAiSourceCurrentness.Expected;
import org.notesknowledge.knowledge.spi.PrivateQuerySource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.ObjectMapper;

/** Real pinned SDK/Spring AI wire tests against loopback only, with no environment credentials. */
@Tag("FAST") @Tag("SECURITY") @Tag("RETRIEVAL") @Tag("EVALUATION")
class GoogleDerivationAdaptersTest {
    private final ObjectMapper json=new ObjectMapper();
    private final io.micrometer.core.instrument.simple.SimpleMeterRegistry metrics=new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    private final GoogleDerivationAdapters adapters=new GoogleDerivationAdapters(metrics);
    private final UUID owner=new UUID(0,1),note=new UUID(0,2);
    private final Expected expected=new Expected(owner,note,null,1,1,null);
    private final String query="Where is the synthetic observatory?";
    private final List<String> requests=new CopyOnWriteArrayList<>();
    private final List<String> requestPaths=new CopyOnWriteArrayList<>();
    private HttpServer server;
    private Client sdk;
    private ObjectProvider<Client> clients;
    private int status=200;
    private String response="{\"embeddings\":[{\"values\":[1.0,0.0]}]}";
    private AiDerivationProperties config(String model) {
        return config(model,"gemini-3.8-flash");
    }
    private AiDerivationProperties config(String model,String mediaModel) {
        return new AiDerivationProperties(true,false,"gemini",model,mediaModel,"synthetic-v1","global","unpaid",
            "synthetic-qualification-v1","google-v2",2,"cosine","none","a".repeat(64));
    }
    @BeforeEach @SuppressWarnings("unchecked") void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            requestPaths.add(exchange.getRequestURI().getPath());
            if(exchange.getRequestURI().getRawQuery()!=null)throw new AssertionError("SDK must not put credentials in a query string");
            requests.add(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            byte[] bytes=response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        sdk=Client.builder().apiKey("synthetic-not-a-provider-key").httpOptions(GoogleDerivationAdapters.providerHttpOptions(new MockEnvironment()).toBuilder()
            // Status/parsing contracts use the production deadline, not an unrelated
            // two-second scheduling threshold on a resource-constrained test host.
            .baseUrl("http://127.0.0.1:"+server.getAddress().getPort()).timeout(10000)
            .retryOptions(HttpRetryOptions.builder().attempts(1).build()).build()).build();
        clients=mock(ObjectProvider.class);when(clients.getIfAvailable()).thenReturn(sdk);
    }
    @AfterEach void stop(){
        try{sdk.close();server.stop(0);
            for(var meter:metrics.getMeters()){
                assertThat(meter.getId().getName()).isEqualTo("notes.workspace.ai.provider");
                assertThat(meter.getId().getTags()).allSatisfy(tag->{
                    assertThat(tag.getKey()).isIn("capability","outcome");
                    assertThat(tag.getValue()).doesNotContain(query,"synthetic-not-a-provider-key",owner.toString(),note.toString());
                });
            }
        }finally{metrics.close();}
    }
    private KnowledgeQueryGate gate() {
        return gate(false);
    }
    private KnowledgeQueryGate gate(boolean workspace) {
        var policies=mock(ProcessingPolicyService.class);var sources=mock(PrivateQuerySource.class);
        when(policies.policyPrerequisite(owner)).thenReturn(Optional.of(new ProcessingPolicyService.AcknowledgedProcessingPolicy(new UUID(0,3),1,"a".repeat(64))));
        when(sources.matches(any(),eq(true))).thenReturn(true);
        var properties=workspace?new ProviderDispatchProperties("unpaid-synthetic-demo",List.of(),List.of(),List.of(),owner)
            :new ProviderDispatchProperties("unpaid-synthetic-demo",List.of(ProviderDispatchPolicy.fingerprint(expected)),List.of(ProviderDispatchPolicy.queryFingerprint(query)));
        return new KnowledgeQueryGate(policies,config("gemini-embedding-2"),new ProviderDispatchPolicy(properties,workspace),properties,sources);
    }
    @Test void actualSdkAcceptsFreshWorkspaceQuestionButCannotSubstituteTheBoundQuestion() {
        String fresh="What fictional reminder did I just add?";var g=gate(true);
        var permit=g.evidence(owner,fresh,List.of(new PrivateQuerySource.Source(expected,"active",true,"note",Instant.EPOCH,"")),mock(DispatchCoordinator.Handle.class));
        generated("{\"claims\":[{\"text\":\"Fictional reminder\",\"evidenceIds\":[\"e0\"]}],\"conflicting\":false}");
        var adapter=adapters.googleStructuredKnowledgePort(config("gemini-embedding-2"),clients,json);
        assertThat(adapter.generate(permit,fresh,"answer",List.of(new StructuredKnowledgePort.Evidence("e0","Fictional reminder","note"))).claims()).hasSize(1);
        assertThat(requests).hasSize(1);assertThat(requests.getFirst()).contains(fresh);
        assertThatThrownBy(()->adapter.generate(permit,fresh+"!","answer",List.of(new StructuredKnowledgePort.Evidence("e0","Fictional reminder","note")))).isInstanceOf(DerivationFailure.class);
        assertThat(requests).hasSize(1);
    }
    private KnowledgeQueryGate.EvidencePermit evidence() {
        return gate().evidence(owner,query,List.of(new PrivateQuerySource.Source(expected,"active",true,"note",Instant.EPOCH,"")),mock(DispatchCoordinator.Handle.class));
    }
    private AiProcessingGate.SourceAiPermit sourcePermit(){return mock(AiProcessingGate.SourceAiPermit.class);}
    private void generated(String output) {
        response=json.writeValueAsString(Map.of("modelVersion","gemini-3.8-flash","candidates",List.of(Map.of("content",Map.of("role","model","parts",List.of(Map.of("text",output))),"finishReason","STOP"))));
    }
    @Test void videoFailuresDistinguishCompletionSchemaAndTemporalEvidenceWithoutRetainingOutput(){
        var port=adapters.googleMediaUnderstandingPort(config("gemini-embedding-2"),clients,json);
        var request=new MediaUnderstandingRequest("video/mp4",null,null,2.);
        response="{\"candidates\":[{\"finishReason\":\"MAX_TOKENS\",\"content\":{\"parts\":[{\"text\":\"synthetic-private-marker\"}]}}]}";
        assertThatThrownBy(()->port.describe(sourcePermit(),new byte[]{1},request)).isInstanceOfSatisfying(DerivationFailure.class,f->{
            assertThat(f.category).isEqualTo(KnowledgeWork.Failure.INVALID_OUTPUT);assertThat(f.diagnostic.detail()).isEqualTo(ProviderFailureDiagnostic.Detail.COMPLETION);
            assertThat(f.toString()+f.diagnostic).doesNotContain("synthetic-private-marker");});
        generated("not-valid-json-synthetic-private-marker");
        assertThatThrownBy(()->port.describe(sourcePermit(),new byte[]{1},request)).isInstanceOfSatisfying(DerivationFailure.class,f->assertThat(f.diagnostic.detail()).isEqualTo(ProviderFailureDiagnostic.Detail.JSON_SCHEMA));
        generated(json.writeValueAsString(Map.of("complete",true,"checkedPages",List.of(),"noSpeech",false,"segments",List.of(new DerivedSegment("synthetic-private-marker","video_scene","",null,null,null,0.,3.,null,null,null,null)))));
        assertThatThrownBy(()->port.describe(sourcePermit(),new byte[]{1},request)).isInstanceOfSatisfying(DerivationFailure.class,f->assertThat(f.diagnostic.detail()).isEqualTo(ProviderFailureDiagnostic.Detail.TEMPORAL_EVIDENCE));
    }
    @ParameterizedTest @ValueSource(strings={"image/png","image/jpeg","application/pdf","video/mp4"})
    void nonAudioSchemaRequiresFalseNoSpeechAndStillRejectsContradictoryProviderOutput(String type) {
        var request=new MediaUnderstandingRequest(type,type.equals("application/pdf")?1:null,type.equals("application/pdf")?1:null,type.equals("video/mp4")?2.:null);
        var pages=type.equals("application/pdf")?List.of(1):List.of();
        generated(json.writeValueAsString(Map.of("complete",true,"checkedPages",pages,"noSpeech",false,"segments",List.of())));
        var port=adapters.googleMediaUnderstandingPort(config("gemini-embedding-2"),clients,json);
        assertThat(port.describe(sourcePermit(),new byte[]{1},request)).isEmpty();
        var flag=json.readTree(requests.getLast()).path("generationConfig").path("responseJsonSchema").path("properties").path("noSpeech");
        assertThat(flag.path("enum").size()).isEqualTo(1);assertThat(flag.path("enum").get(0).asBoolean()).isFalse();
        generated(json.writeValueAsString(Map.of("complete",true,"checkedPages",pages,"noSpeech",true,"segments",List.of())));
        assertThatThrownBy(()->port.describe(sourcePermit(),new byte[]{1},request)).isInstanceOfSatisfying(DerivationFailure.class,
            failure->assertThat(failure.category).isEqualTo(KnowledgeWork.Failure.INVALID_OUTPUT));
    }
    @Test void audioKeepsItsNoSpeechCapabilityInTheRealSdkSchema() {
        generated("{\"complete\":true,\"checkedPages\":[],\"noSpeech\":true,\"segments\":[]}");
        var result=adapters.googleMediaUnderstandingPort(config("gemini-embedding-2"),clients,json)
            .describe(sourcePermit(),new byte[]{1},new MediaUnderstandingRequest("audio/wav",null,null,2.));
        assertThat(result).hasSize(1);assertThat(result.getFirst().kind()).isEqualTo("transcript");
        assertThat(json.readTree(requests.getLast()).path("generationConfig").path("responseJsonSchema").path("properties").path("noSpeech").has("enum")).isFalse();
    }
    @ParameterizedTest @ValueSource(strings={"gemini-3.8-flash","gemini-3.6-flash","gemini-3.5-flash-lite"})
    void explicitLowThinkingIsSentThroughTheRealSdkAndUnknownConfigurationIsRejected(String mediaModel) {
        var configured=new GoogleDerivationAdapters(metrics,new MockEnvironment().withProperty("knowledge.derivation.thinking-level","LOW"));
        generated("{\"complete\":true,\"checkedPages\":[],\"noSpeech\":false,\"segments\":[]}");
        configured.googleMediaUnderstandingPort(config("gemini-embedding-2",mediaModel),clients,json).describe(sourcePermit(),new byte[]{1},new MediaUnderstandingRequest("image/png",null,null,null));
        assertThat(json.readTree(requests.getFirst()).path("generationConfig").path("thinkingConfig").path("thinkingLevel").asString()).isEqualTo("LOW");
        assertThat(requestPaths.getFirst()).endsWith("/models/"+mediaModel+":generateContent");
        assertThatThrownBy(()->configured.googleMediaUnderstandingPort(config("gemini-embedding-2","unqualified-model"),clients,json)).isInstanceOf(IllegalArgumentException.class);
        assertThat(requests).hasSize(1);
        for(String value:List.of("HIGH","arbitrary"))assertThatThrownBy(()->new GoogleDerivationAdapters(metrics,new MockEnvironment().withProperty("knowledge.derivation.thinking-level",value))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void explicitMinimalThinkingIsLimitedToTheQualifiedFlashLiteModel() {
        var configured=new GoogleDerivationAdapters(metrics,new MockEnvironment().withProperty("knowledge.derivation.thinking-level","MINIMAL"));
        generated("{\"complete\":true,\"checkedPages\":[],\"noSpeech\":false,\"segments\":[]}");
        configured.googleMediaUnderstandingPort(config("gemini-embedding-2","gemini-3.5-flash-lite"),clients,json)
            .describe(sourcePermit(),new byte[]{1},new MediaUnderstandingRequest("image/png",null,null,null));
        assertThat(json.readTree(requests.getFirst()).path("generationConfig").path("thinkingConfig").path("thinkingLevel").asString()).isEqualTo("MINIMAL");
        for(String model:List.of("gemini-3.8-flash","gemini-3.6-flash","unqualified-model"))
            assertThatThrownBy(()->configured.googleMediaUnderstandingPort(config("gemini-embedding-2",model),clients,json)).isInstanceOf(IllegalArgumentException.class);
        assertThat(requests).hasSize(1);
    }
    @Test void operatorTimeoutRetainsDefaultAndRejectsUnboundedOrLeaseLengthRequests() {
        assertThat(GoogleDerivationAdapters.providerTimeout(new MockEnvironment())).isEqualTo(10000);
        assertThat(GoogleDerivationAdapters.providerTimeout(new MockEnvironment().withProperty("knowledge.derivation.provider-timeout-ms","20000"))).isEqualTo(20000);
        assertThat(GoogleDerivationAdapters.providerTimeout(new MockEnvironment().withProperty("knowledge.derivation.provider-timeout-ms","25000"))).isEqualTo(25000);
        for(String value:List.of("0","-1","999","25001","30000","2147483647"))
            assertThatThrownBy(()->GoogleDerivationAdapters.providerTimeout(new MockEnvironment().withProperty("knowledge.derivation.provider-timeout-ms",value))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void longerProviderDeadlineRequiresExplicitLeaseHeadroomAndStillHasAHardCeiling() {
        var environment=new MockEnvironment().withProperty("knowledge.derivation.lease-seconds","90")
            .withProperty("knowledge.derivation.provider-timeout-ms","60000");
        assertThat(GoogleDerivationAdapters.providerTimeout(environment)).isEqualTo(60000);
        assertThatThrownBy(()->GoogleDerivationAdapters.providerTimeout(environment.withProperty("knowledge.derivation.lease-seconds","60")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->GoogleDerivationAdapters.providerTimeout(environment.withProperty("knowledge.derivation.lease-seconds","121")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->GoogleDerivationAdapters.providerTimeout(environment.withProperty("knowledge.derivation.lease-seconds","120")
            .withProperty("knowledge.derivation.provider-timeout-ms","60001"))).isInstanceOf(IllegalArgumentException.class);
    }
    @ParameterizedTest @ValueSource(ints={128,129})
    void mediaSegmentLimitRemainsAuthoritativeWithoutTheProviderCompilerBound(int count) {
        var segment=Map.of("text","Fictional visible mark","kind","whole_image","heading","");
        generated(json.writeValueAsString(Map.of("complete",true,"checkedPages",List.of(),"noSpeech",false,
            "segments",Collections.nCopies(count,segment))));
        var port=adapters.googleMediaUnderstandingPort(config("gemini-embedding-2"),clients,json);
        if(count==128)assertThat(port.describe(sourcePermit(),new byte[]{1},new MediaUnderstandingRequest("image/png",null,null,null))).hasSize(128);
        else assertThatThrownBy(()->port.describe(sourcePermit(),new byte[]{1},new MediaUnderstandingRequest("image/png",null,null,null)))
            .isInstanceOfSatisfying(DerivationFailure.class,failure->assertThat(failure.category).isEqualTo(KnowledgeWork.Failure.INVALID_OUTPUT)).hasNoCause();
        var schema=json.readTree(requests.getFirst()).path("generationConfig").path("responseJsonSchema");
        assertThat(schema.path("properties").path("segments").has("maxItems")).isFalse();
        assertThat(schema.path("properties").path("checkedPages").path("maxItems").asInt()).isEqualTo(8);
        assertThat(requests).hasSize(1);
    }
    @Test void embedding2UsesSeparateDocumentRequestsAndQueryInstructionWithoutLegacyFields() {
        var permit=sourcePermit();var port=adapters.googleTextEmbeddingPort(config("gemini-embedding-2"),clients);
        assertThat(port.embed(permit,List.of("Synthetic lunar station","Synthetic forest lab"))).hasSize(2);
        var structured=adapters.googleStructuredKnowledgePort(config("gemini-embedding-2"),clients,json);
        assertThat(structured.embedQuery(gate().query(owner,query),query)).containsExactly(1f,0f);
        assertThat(requests).hasSize(3);
        assertThat(requests.get(0)).contains("title: none | text: Synthetic lunar station");
        assertThat(requests.get(1)).contains("title: none | text: Synthetic forest lab");
        assertThat(requests.get(2)).contains("task: search result | query: "+query);
        for(String request:requests)assertThat(request).contains("\"outputDimensionality\":2").doesNotContain("taskType","autoTruncate","synthetic-not-a-provider-key");
        assertThat(requestPaths).allMatch(p->p.contains("gemini-embedding-2"));
        verify(permit,atLeast(3)).requireGoogleDispatch();
    }
    @Test void legacyModelRetainsItsHistoricalSpringAiWireFormat() {
        // The existing model format is not silently rewritten into Embedding 2's incompatible space.
        new GeminiTextEmbeddings(sdk,config("gemini-embedding-001"),false).embed(List.of("Synthetic"),()->{});
        assertThat(requests.getFirst()).contains("models/gemini-embedding-001","Synthetic").doesNotContain("title: none | text:");
    }
    @Test @SuppressWarnings("unchecked")
    void packagedAcceptanceTransportSpeaksTheRealEmbeddingAndMediaSdkContracts()throws Exception {
        try(var controlled=new PackagedMultimediaSearchAcceptance.ControlledGemini();var ownClient=Client.builder().apiKey("synthetic-not-a-provider-key")
                .httpOptions(HttpOptions.builder().baseUrl("http://127.0.0.1:"+controlled.server.getAddress().getPort()).timeout(10000)
                    .retryOptions(HttpRetryOptions.builder().attempts(1).build()).build()).build()){
            ObjectProvider<Client> ownClients=mock(ObjectProvider.class);when(ownClients.getIfAvailable()).thenReturn(ownClient);
            var c=new AiDerivationProperties(true,false,"gemini","gemini-embedding-2","gemini-3.8-flash","synthetic-v1","global","unpaid","synthetic-qualification-v1","google-v2",8,"cosine","none","a".repeat(64));
            var vector=adapters.googleTextEmbeddingPort(c,ownClients).embed(sourcePermit(),List.of("Aster fictional fact"));
            assertThat(vector).hasSize(1);assertThat(vector.getFirst()).hasSize(8);
            byte[] bytes={1,2,3};var segment=new DerivedSegment("Aster synthetic caption","whole_image","",null,null,null,null,null,null,null,null,null);
            controlled.media(bytes,List.of(segment));
            try {
                assertThat(adapters.googleMediaUnderstandingPort(c,ownClients,json).describe(sourcePermit(),bytes,new MediaUnderstandingRequest("image/png",null,null,null))).containsExactly(segment);
            }catch(DerivationFailure failure){throw new AssertionError("controlled_media_contract_"+failure.category+"_"+(failure.diagnostic==null?"none":failure.diagnostic.kind())+"_"+controlled.failureCode);}
            assertThat(controlled.failureCode).isEqualTo("none");
        }
    }
    @Test void eachEmbeddingDispatchRevalidatesAndStopsBeforeTheNextRequest() {
        var calls=new AtomicInteger();
        assertThatThrownBy(()->new GeminiTextEmbeddings(sdk,config("gemini-embedding-2"),false).embed(List.of("First","Second"),()->{
            if(calls.incrementAndGet()==2)throw new DerivationFailure(KnowledgeWork.Failure.INVALID_SOURCE);
        })).isInstanceOf(DerivationFailure.class);
        assertThat(requests).hasSize(1);
    }
    @Test void outputDimensionFiniteNonzeroAndResultCardinalityAreMandatory() {
        for(String body:List.of("{\"embeddings\":[{\"values\":[1]}]}","{\"embeddings\":[{\"values\":[0,0]}]}",
                "{\"embeddings\":[{\"values\":[1e100,0]}]}","{}")) {
            response=body;
            assertThatThrownBy(()->new GeminiTextEmbeddings(sdk,config("gemini-embedding-2"),false).embed(List.of("Synthetic"),()->{}))
                .isInstanceOfSatisfying(DerivationFailure.class,f->assertThat(f.category).isEqualTo(KnowledgeWork.Failure.INVALID_OUTPUT));
        }
        assertThat(requests).hasSize(4);
    }
    @Test void payloadBoundsRejectBeforeSdkDispatch() {
        var port=new GeminiTextEmbeddings(sdk,config("gemini-embedding-2"),false);
        for(List<String> texts:List.of(List.<String>of(),Collections.nCopies(17,"Synthetic"),List.of(" "),List.of("界".repeat(3000))))
            assertThatThrownBy(()->port.embed(texts,()->{})).isInstanceOf(DerivationFailure.class);
        assertThat(requests).isEmpty();
    }
    @Test void extractionAllowsThirtyTwoEvidenceItemsButFocusedGenerationAndOversizeRemainBounded() {
        generated("{\"claims\":[{\"text\":\"Synthetic last source\",\"evidenceIds\":[\"e31\"]}],\"conflicting\":false}");
        var adapter=adapters.googleStructuredKnowledgePort(config("gemini-embedding-2"),clients,json);
        var payload=java.util.stream.IntStream.range(0,32).mapToObj(i->new StructuredKnowledgePort.Evidence("e"+i,"Synthetic record "+i,"note")).toList();
        assertThat(adapter.generate(evidence(),query,"extract",payload).claims().getFirst().evidenceIds()).containsExactly("e31");
        assertThat(requests).hasSize(1);
        var tooMany=new ArrayList<>(payload);tooMany.add(new StructuredKnowledgePort.Evidence("e32","Synthetic overflow","note"));
        assertThatThrownBy(()->adapter.generate(evidence(),query,"extract",tooMany)).isInstanceOf(DerivationFailure.class);
        assertThatThrownBy(()->adapter.generate(evidence(),query,"answer",payload.subList(0,13))).isInstanceOf(DerivationFailure.class);
        assertThat(requests).hasSize(1);
    }
    @Test void structuredGenerationIsSchemaBoundWithoutCandidateOverrideToolsOrCache() {
        generated("{\"claims\":[{\"text\":\"The fictional observatory is on Luna.\",\"evidenceIds\":[\"e0\"]}],\"conflicting\":false}");
        var location=new KnowledgeQueryResult.Location("pdf_text",null,null,7,null,null,null,null,null,null);
        var result=adapters.googleStructuredKnowledgePort(config("gemini-embedding-2"),clients,json).generate(evidence(),query,"answer",
            List.of(new StructuredKnowledgePort.Evidence("e0","The fictional observatory is on Luna.","pdf",location)));
        assertThat(result.claims()).hasSize(1);assertThat(result.claims().getFirst().evidenceIds()).containsExactly("e0");
        assertThat(requests).hasSize(1);
        var wire=json.readTree(requests.getFirst());
        assertThat(wire.get("generationConfig").has("candidateCount")).isFalse();
        assertThat(wire.get("generationConfig").get("responseMimeType").asText()).isEqualTo("application/json");
        assertThat(wire.get("generationConfig").has("responseJsonSchema")).isTrue();
        assertThat(wire.has("tools")).isFalse();assertThat(wire.has("cachedContent")).isFalse();
        String instruction=wire.get("contents").get(0).get("parts").get(0).get("text").asText();
        var supplied=json.readTree(instruction.substring(instruction.indexOf('{'))).get("evidence").get(0);
        assertThat(supplied.get("location").get("page").asInt()).isEqualTo(7);
        assertThat(supplied.get("location").get("kind").asText()).isEqualTo("pdf_text");
        assertThat(instruction).doesNotContain(owner.toString(),note.toString());
    }
    @ParameterizedTest @ValueSource(strings={"MAX_TOKENS","SAFETY","RECITATION","OTHER","FINISH_REASON_UNSPECIFIED"})
    void validStructuredJsonWithoutSuccessfulCompletionNeverBecomesGroundedEvidence(String finishReason) {
        generated("{\"claims\":[{\"text\":\"Synthetic partial claim\",\"evidenceIds\":[\"e0\"]}],\"conflicting\":false}");
        response=response.replace("\"STOP\"","\""+finishReason+"\"");
        var port=adapters.googleStructuredKnowledgePort(config("gemini-embedding-2"),clients,json);
        assertThatThrownBy(()->port.generate(evidence(),query,"answer",
                List.of(new StructuredKnowledgePort.Evidence("e0","Synthetic partial claim","note"))))
            .isInstanceOfSatisfying(DerivationFailure.class,f->{
                assertThat(f.category).isEqualTo(KnowledgeWork.Failure.INVALID_OUTPUT);
                assertThat(f.diagnostic.kind()).isEqualTo(ProviderFailureDiagnostic.Kind.PARSING);
                assertThat(f.diagnostic.providerReached()).isEqualTo(ProviderFailureDiagnostic.Reach.HTTP_RESPONSE_RECEIVED);
                assertThat(f.diagnostic.structurallyValidResponse()).isFalse();
            }).hasCause(null).hasMessage("Derivation unavailable");
        assertThat(requests).hasSize(1);
    }
    @ParameterizedTest @ValueSource(strings={"answer","extract","tags"})
    void authorityLostAtFinalGenerationBoundaryNeverDispatchesOrBecomesTransportFailure(String task) {
        generated("{\"claims\":[{\"text\":\"Synthetic\",\"evidenceIds\":[\"e0\"]}],\"conflicting\":false}");
        var permit=spy(evidence());
        doCallRealMethod().doThrow(new DerivationFailure(KnowledgeWork.Failure.INVALID_SOURCE))
            .when(permit).requireGoogle(query);
        var port=adapters.googleStructuredKnowledgePort(config("gemini-embedding-2"),clients,json);
        var selected=List.of(new StructuredKnowledgePort.Evidence("e0","Synthetic","note"));
        assertThatThrownBy(()->port.generate(permit,query,task,selected))
            .isInstanceOfSatisfying(DerivationFailure.class,f->assertThat(f.category).isEqualTo(KnowledgeWork.Failure.INVALID_SOURCE))
            .hasCause(null).hasMessage("Derivation unavailable");
        verify(permit,times(2)).requireGoogle(query);
        assertThat(requests).isEmpty(); // Serialization must not count as disclosure.
        assertThat(port.generate(evidence(),query,task,selected).claims()).hasSize(1);
        assertThat(requests).hasSize(1); // Independent fresh authority, not a retry.
    }
    @Test void ordinaryGenerationUsesThePinnedModelWithoutStructuredOrToolOptions() {
        generated("Synthetic service available.");
        var options=org.springframework.ai.google.genai.GoogleGenAiChatOptions.builder().model("gemini-3.8-flash")
            .maxOutputTokens(128).googleSearchRetrieval(false).includeServerSideToolInvocations(false).useCachedContent(false).build();
        var model=org.springframework.ai.google.genai.GoogleGenAiChatModel.builder().genAiClient(sdk).options(options)
            .retryTemplate(GoogleDerivationAdapters.noHiddenRetry()).build();
        var result=model.call(new org.springframework.ai.chat.prompt.Prompt("Synthetic availability probe",options));
        assertThat(result.getResult().getOutput().getText()).isEqualTo("Synthetic service available.");
        assertThat(requests).hasSize(1);
        assertThat(requestPaths.getFirst()).contains("gemini-3.8-flash");
        assertThat(requests.getFirst()).doesNotContain("responseJsonSchema","tools","cachedContent","candidateCount");
    }
    @ParameterizedTest @ValueSource(ints={400,401,403,404,429,500,502,503,504})
    void generationHttpFailuresAreSanitizedAndAnIndependentOperationCanRecover(int code) {
        var port=adapters.googleStructuredKnowledgePort(config("gemini-embedding-2"),clients,json);
        status=code;response="{\"error\":{\"code\":"+code+",\"message\":\"synthetic-sensitive-provider-body\",\"status\":\"UNKNOWN\"}}";
        var category=code==400?KnowledgeWork.Failure.INVALID_OUTPUT:code==429?KnowledgeWork.Failure.QUOTA:
            code<500?KnowledgeWork.Failure.PROVIDER_UNAVAILABLE:KnowledgeWork.Failure.TRANSIENT_DEPENDENCY;
        assertThatThrownBy(()->port.generate(evidence(),query,"answer",List.of(new StructuredKnowledgePort.Evidence("e0","Synthetic","note"))))
            .isInstanceOfSatisfying(DerivationFailure.class,f->{
                assertThat(f.category).as("sanitized provider diagnostic: %s",f.diagnostic).isEqualTo(category);
                assertThat(f.diagnostic.httpStatus()).isEqualTo(code);
                assertThat(f.diagnostic.providerReached()).isEqualTo(ProviderFailureDiagnostic.Reach.HTTP_RESPONSE_RECEIVED);
                assertThat(f.diagnostic.structurallyValidResponse()).isFalse();
                assertThat(json.writeValueAsString(f.diagnostic)).doesNotContain("synthetic-sensitive-provider-body",query);
            }).hasCause(null).hasMessage("Derivation unavailable");
        assertThat(requests).hasSize(1); // No retry inside either pinned client layer.
        status=200;generated("{\"claims\":[{\"text\":\"Synthetic\",\"evidenceIds\":[\"e0\"]}],\"conflicting\":false}");
        var recovered=port.generate(evidence(),query,"answer",List.of(new StructuredKnowledgePort.Evidence("e0","Synthetic","note")));
        assertThat(recovered.claims().getFirst().text()).isEqualTo("Synthetic");
        assertThat(requests).hasSize(2); // Fresh permit and independent operation, not cached failure.
    }
    @ParameterizedTest @ValueSource(strings={"{}","{\"candidates\":[]}","{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[]},\"finishReason\":\"STOP\"}]}"})
    void absentGenerationCandidatesNeverBecomeAnAnswer(String body) {
        response=body;
        var port=adapters.googleStructuredKnowledgePort(config("gemini-embedding-2"),clients,json);
        assertThatThrownBy(()->port.generate(evidence(),query,"answer",List.of(new StructuredKnowledgePort.Evidence("e0","Synthetic","note"))))
            .isInstanceOf(DerivationFailure.class).hasCause(null).hasMessage("Derivation unavailable");
        assertThat(requests).hasSize(1);
    }
    @Test void interruptedTransportHasNoProviderReceiptClaimOrRetainedException() {
        var safe=GoogleDerivationAdapters.providerFailure(new com.google.genai.errors.GenAiIOException("synthetic-sensitive",
            new java.io.InterruptedIOException("synthetic-sensitive")),ProviderFailureDiagnostic.Stage.GENERATION,System.nanoTime());
        assertThat(safe.category).isEqualTo(KnowledgeWork.Failure.TRANSIENT_DEPENDENCY);
        assertThat(safe.diagnostic.kind()).isEqualTo(ProviderFailureDiagnostic.Kind.INTERRUPTED_IO);
        assertThat(safe.diagnostic.providerReached()).isEqualTo(ProviderFailureDiagnostic.Reach.UNKNOWN);
        assertThat(safe).hasCause(null).hasMessage("Derivation unavailable");
    }
    @Test void malformedAndUnreferencedStructuredOutputIsRejected() {
        var port=adapters.googleStructuredKnowledgePort(config("gemini-embedding-2"),clients,json);
        for(String output:List.of("not json","{\"claims\":[{\"text\":\"Unsupported\",\"evidenceIds\":[\"e9\"]}],\"conflicting\":false}")) {
            generated(output);
            assertThatThrownBy(()->port.generate(evidence(),query,"answer",List.of(new StructuredKnowledgePort.Evidence("e0","Synthetic","note"))))
                .isInstanceOfSatisfying(DerivationFailure.class,f->assertThat(f.category).isEqualTo(KnowledgeWork.Failure.INVALID_OUTPUT));
        }
    }
    @ParameterizedTest @ValueSource(strings={"image/png","audio/wav","video/mp4","application/pdf"})
    void fourMediaModalitiesUseInlineBytesAndStructuredSchema(String type) throws Exception {
        String segment=switch(type) {
            case "application/pdf" -> "{\"text\":\"Synthetic scanned fact\",\"kind\":\"pdf_text\",\"heading\":\"\",\"page\":1}";
            case "audio/wav","video/mp4" -> "{\"text\":\"Synthetic timed fact\",\"kind\":\""+(type.equals("audio/wav")?"transcript":"video_scene")+"\",\"heading\":\"\",\"timeStart\":0,\"timeEnd\":0.5}";
            default -> "{\"text\":\"Synthetic description\",\"kind\":\"whole_image\",\"heading\":\"\"}";
        };
        generated("{\"complete\":true,\"checkedPages\":"+(type.equals("application/pdf")?"[1]":"[]")+",\"noSpeech\":false,\"segments\":["+segment+"]}");
        String kind=switch(type){case "image/png"->"image";case "audio/wav"->"audio";case "video/mp4"->"video";default->"pdf";};
        byte[] bytes=org.notesknowledge.notes.DerivationMediaFixtures.media(kind).getBytes();
        var scope=new MediaUnderstandingRequest(type,type.equals("application/pdf")?1:null,type.equals("application/pdf")?1:null,
            type.equals("audio/wav")||type.equals("video/mp4")?.5:null);
        var result=adapters.googleMediaUnderstandingPort(config("gemini-embedding-2"),clients,json).describe(sourcePermit(),bytes,scope);
        assertThat(result).hasSize(1);
        var wire=json.readTree(requests.getFirst());
        var inline=wire.get("contents").get(0).get("parts").get(1).get("inlineData");
        assertThat(inline.get("mimeType").asText()).isEqualTo(type);
        assertThat(Base64.getDecoder().decode(inline.get("data").asText())).isEqualTo(bytes);
        assertThat(wire.get("generationConfig").get("responseJsonSchema").get("properties").get("segments").get("items").get("properties").get("page").has("anyOf")).isTrue();
        assertThat(requests.getFirst()).contains("responseJsonSchema").doesNotContain("fileUri","candidateCount","googleSearch","cachedContent","nullable");
        assertThat(requestPaths).hasSize(1).allSatisfy(path->assertThat(path).endsWith(":generateContent").doesNotContain("files","upload"));
        assertThat(requests.getFirst().getBytes(StandardCharsets.UTF_8).length).isLessThan(MediaUnderstandingRequest.MAX_INLINE_REQUEST_BYTES);
        // Actual synthetic upload fixtures, but no claim of live media understanding or native embeddings.
    }
    @Test void videoWireRequestsVisualActionsSeparatelyFromCaptionEvidence()throws Exception {
        generated("{\"complete\":true,\"checkedPages\":[],\"noSpeech\":false,\"segments\":["
            +"{\"text\":\"A green robot lifts a red box.\",\"kind\":\"video_scene\",\"heading\":\"\",\"timeStart\":0,\"timeEnd\":0.5},"
            +"{\"text\":\"On-screen text: Quiet afternoon.\",\"kind\":\"video_scene\",\"heading\":\"\",\"timeStart\":0,\"timeEnd\":0.5}]}");
        byte[] bytes=org.notesknowledge.notes.DerivationMediaFixtures.media("video").getBytes();
        var segments=adapters.googleMediaUnderstandingPort(config("gemini-embedding-2"),clients,json)
            .describe(sourcePermit(),bytes,new MediaUnderstandingRequest("video/mp4",null,null,.5));
        assertThat(segments).extracting(DerivedSegment::text).containsExactly("A green robot lifts a red box.","On-screen text: Quiet afternoon.");
        var parts=json.readTree(requests.getFirst()).get("contents").get(0).get("parts");
        assertThat(parts.get(0).get("text").asText()).contains("visual scene independently of on-screen text",
            "Caption/OCR text alone is not a scene description","actions, interactions","not only the opening frame",
            "only when confidently identifiable","never treat a caption as proof","Do not invent names");
        assertThat(Base64.getDecoder().decode(parts.get(1).get("inlineData").get("data").asText())).isEqualTo(bytes);
        assertThat(requests.getFirst()).doesNotContain("Homelander","Soldier Boy","googleSearch","fileUri");
        var wire=json.readTree(requests.getFirst());
        assertThat(wire.get("generationConfig").get("mediaResolution").asText()).isEqualTo("MEDIA_RESOLUTION_HIGH");
        assertThat(parts.get(1).get("videoMetadata").get("fps").asDouble()).isEqualTo(2);
        assertThat(requests).hasSize(1);
    }
    @Test void quotaAndUnavailableStatusesAreSanitizedAndNeverRetried() {
        var port=adapters.googleStructuredKnowledgePort(config("gemini-embedding-2"),clients,json);
        for(int code:new int[]{429,401,403,404,500}) {
            status=code;response="{\"error\":{\"code\":"+code+",\"message\":\"synthetic-provider-message\",\"status\":\"RESOURCE_EXHAUSTED\"}}";
            var expectedCategory=code==429?KnowledgeWork.Failure.QUOTA:code==500?KnowledgeWork.Failure.TRANSIENT_DEPENDENCY:KnowledgeWork.Failure.PROVIDER_UNAVAILABLE;
            assertThatThrownBy(()->port.embedQuery(gate().query(owner,query),query))
                .isInstanceOfSatisfying(DerivationFailure.class,f->assertThat(f.category).isEqualTo(expectedCategory))
                .hasMessageNotContaining("synthetic-provider-message").hasCause(null);
        }
        assertThat(requests).hasSize(5);
        status=429;
        assertThatThrownBy(()->port.generate(evidence(),query,"answer",List.of(new StructuredKnowledgePort.Evidence("e0","Synthetic","note"))))
            .isInstanceOfSatisfying(DerivationFailure.class,f->assertThat(f.category).isEqualTo(KnowledgeWork.Failure.QUOTA));
        assertThat(requests).hasSize(6);
    }
    @Test void realPdfPageChildWireContainsOnlyTheApprovedPngAndOriginalPageOrdinal()throws Exception {
        var original=org.notesknowledge.notes.MeaningfulMediaFixtures.media("mixed").getBytes();
        try(var rendered=org.notesknowledge.notes.PdfPageRuntimeFixtures.render(original,3)) {
            byte[] png=rendered.bytes();
            var e=new Expected(owner,note,new UUID(0,4),1,1,1L);
            var child=new TransformedMediaIdentity(e,TransformedMediaIdentity.digest(original),original.length,"application/pdf",
                rendered.profile(),rendered.implementationVersion(),rendered.artifactSha256(),rendered.configurationSha256(),
                TransformedMediaIdentity.digest(png),png.length,"image/png",new TransformedMediaIdentity.Scope("pdf_pages",3,3,0,0,0),
                "a".repeat(64),"gemini","gemini-3.8-flash","media-understanding","a".repeat(64));
            generated("{\"complete\":true,\"checkedPages\":[3],\"noSpeech\":false,\"segments\":[{\"text\":\"A blue square on ASTER\",\"kind\":\"pdf_text\",\"heading\":\"\",\"page\":3}]}");
            var permit=sourcePermit();var port=adapters.googleMediaUnderstandingPort(config("gemini-embedding-2"),clients,json);
            var request=new MediaUnderstandingRequest("image/png",3,3,null);
            assertThatThrownBy(()->port.describe(permit,png,request)).isInstanceOf(DerivationFailure.class);
            assertThat(requests).isEmpty();
            assertThat(port.describeChild(permit,child,original,png,request).getFirst().page()).isEqualTo(3);
            verify(permit,times(2)).requireGoogleChildDispatch(child,original,png);
            var parts=json.readTree(requests.getFirst()).get("contents").get(0).get("parts");
            assertThat(parts.size()).isEqualTo(2);assertThat(parts.get(0).get("text").asText()).contains("ONLY original PDF page 3");
            assertThat(parts.get(1).get("inlineData").get("mimeType").asText()).isEqualTo("image/png");
            assertThat(Base64.getDecoder().decode(parts.get(1).get("inlineData").get("data").asText())).isEqualTo(png).isNotEqualTo(original);
            assertThat(requests.getFirst()).doesNotContain(Base64.getEncoder().encodeToString(original),"fileUri");
            assertThatThrownBy(()->port.describeChild(permit,child,original,png,new MediaUnderstandingRequest("image/png",2,2,null))).isInstanceOf(DerivationFailure.class);
            doThrow(new DerivationFailure(KnowledgeWork.Failure.INVALID_SOURCE)).when(permit).requireGoogleChildDispatch(child,original,png);
            assertThatThrownBy(()->port.describeChild(permit,child,original,png,request)).isInstanceOfSatisfying(DerivationFailure.class,
                failure->assertThat(failure.category).isEqualTo(KnowledgeWork.Failure.INVALID_SOURCE));
            assertThat(requests).hasSize(1);
        }
    }
    @Test void partialTruncatedMissingCoverageAndUnknownTimingNeverReturnSuccessfulMedia() {
        var port=adapters.googleMediaUnderstandingPort(config("gemini-embedding-2"),clients,json);
        var pdf=new MediaUnderstandingRequest("application/pdf",1,2,null);
        for(String output:List.of("{\"complete\":false,\"checkedPages\":[1,2],\"noSpeech\":false,\"segments\":[]}",
                "{\"complete\":true,\"checkedPages\":[1],\"noSpeech\":false,\"segments\":[]}")) {
            generated(output);
            assertThatThrownBy(()->port.describe(sourcePermit(),new byte[]{1},pdf)).isInstanceOf(DerivationFailure.class);
        }
        generated("{\"complete\":true,\"checkedPages\":[1,2],\"noSpeech\":false,\"segments\":[]}");response=response.replace("STOP","MAX_TOKENS");
        assertThatThrownBy(()->port.describe(sourcePermit(),new byte[]{1},pdf)).isInstanceOf(DerivationFailure.class);
        generated("{\"complete\":true,\"checkedPages\":[],\"noSpeech\":false,\"segments\":[{\"text\":\"uncertain\",\"kind\":\"transcript\",\"heading\":\"\"}]}");
        assertThatThrownBy(()->port.describe(sourcePermit(),new byte[]{1},new MediaUnderstandingRequest("audio/wav",null,null,3.))).isInstanceOf(DerivationFailure.class);
        assertThat(requests).hasSize(4);assertThat(requestPaths).allSatisfy(p->assertThat(p).endsWith(":generateContent"));
    }
    @ParameterizedTest @ValueSource(strings={"image/png","audio/wav","application/pdf"})
    void absentSpringAiMediaCompletionMetadataFailsClosedAfterRealSdkResponse(String type) throws Exception {
        generated("{\"complete\":true,\"checkedPages\":"+(type.equals("application/pdf")?"[1]":"[]")+",\"noSpeech\":false,\"segments\":[]}");
        String kind=switch(type){case "image/png"->"image";case "audio/wav"->"audio";case "video/mp4"->"video";default->"pdf";};
        var bytes=org.notesknowledge.notes.DerivationMediaFixtures.media(kind).getBytes();
        var scope=new MediaUnderstandingRequest(type,type.equals("application/pdf")?1:null,type.equals("application/pdf")?1:null,
            type.equals("audio/wav")||type.equals("video/mp4")?.5:null);
        var permit=sourcePermit();var port=adapters.googleMediaUnderstandingPort(config("gemini-embedding-2"),clients,json);
        // Fault-inject only nullable Spring AI metadata after a real STOP HTTP/SDK response;
        // omitting a provider wire field is not equivalent to a null Spring AI object.
        try(var generations=mockConstruction(org.springframework.ai.chat.model.Generation.class,(generation,context)->{
            when(generation.getOutput()).thenReturn((org.springframework.ai.chat.messages.AssistantMessage)context.arguments().getFirst());
            when(generation.getMetadata()).thenReturn(null);
        })) {
            assertThatThrownBy(()->port.describe(permit,bytes,scope))
                .isInstanceOfSatisfying(DerivationFailure.class,f->{
                    assertThat(f.category).isEqualTo(KnowledgeWork.Failure.INVALID_OUTPUT);
                    assertThat(f.diagnostic.stage()).isEqualTo(ProviderFailureDiagnostic.Stage.MEDIA);
                    assertThat(f.diagnostic.kind()).isEqualTo(ProviderFailureDiagnostic.Kind.PARSING);
                    assertThat(f.diagnostic.providerReached()).isEqualTo(ProviderFailureDiagnostic.Reach.HTTP_RESPONSE_RECEIVED);
                    assertThat(f.diagnostic.structurallyValidResponse()).isFalse();
                }).hasCause(null).hasMessage("Derivation unavailable");
            assertThat(generations.constructed()).hasSize(1);
        }
        verify(permit,times(2)).requireGoogleDispatch();assertThat(requests).hasSize(1);
    }
    @Test void inlineByteAndVideoDurationBudgetRejectBeforeAnyHttpRequest() {
        var port=adapters.googleMediaUnderstandingPort(config("gemini-embedding-2"),clients,json);
        assertThatThrownBy(()->port.describe(sourcePermit(),new byte[20_000_000],new MediaUnderstandingRequest("video/mp4",null,null,2.))).isInstanceOf(DerivationFailure.class);
        assertThatThrownBy(()->port.describe(sourcePermit(),new byte[]{1},new MediaUnderstandingRequest("video/mp4",null,null,300.))).isInstanceOf(DerivationFailure.class);
        assertThat(requests).isEmpty();assertThat(requestPaths).isEmpty();
    }
    @Test void actualSdkCarriesNonSpeechWavAndReturnsQualifiedAcousticEvidence()throws Exception {
        var wav=org.notesknowledge.notes.MeaningfulMediaFixtures.media("tones").getBytes();
        generated("{\"complete\":true,\"checkedPages\":[],\"noSpeech\":true,\"segments\":[{\"text\":\"Possibly electronic tones change from higher to lower.\",\"kind\":\"transcript\",\"heading\":\"Acoustic description\",\"timeStart\":0,\"timeEnd\":4}]}");
        var port=adapters.googleMediaUnderstandingPort(config("gemini-embedding-2"),clients,json);
        var permit=sourcePermit();var result=port.describe(permit,wav,new MediaUnderstandingRequest("audio/wav",null,null,4.));
        assertThat(result).hasSize(1);assertThat(result.getFirst().text()).startsWith("Audio description (model inference, not spoken words): ");
        assertThat(result.getFirst().timeEnd()).isEqualTo(4.);verify(permit,times(2)).requireGoogleDispatch();
        assertThat(requests).hasSize(1);
        var wire=json.readTree(requests.getFirst());var parts=wire.get("contents").get(0).get("parts");
        assertThat(parts.get(0).get("text").asText()).contains("Acoustic description","not spoken words");
        assertThat(parts.get(1).get("inlineData").get("mimeType").asText()).isEqualTo("audio/wav");
        assertThat(Base64.getDecoder().decode(parts.get(1).get("inlineData").get("data").asText())).isEqualTo(wav);
        assertThat(wire.has("tools")).isFalse();assertThat(requests.getFirst()).doesNotContain("fileUri","cachedContent");
    }
    @ParameterizedTest @ValueSource(strings={"missing","null","MAX_TOKENS","SAFETY","empty-candidates","missing-content","thought-only","tool-only"})
    void nativeVideoCompletionMustBeExplicitStopWithBoundedText(String mode) {
        var candidate=new java.util.LinkedHashMap<String,Object>();
        candidate.put("content",Map.of("role","model","parts",List.of(Map.of("text","{\"complete\":true,\"checkedPages\":[],\"noSpeech\":false,\"segments\":[]}"))));
        candidate.put("finishReason","STOP");
        switch(mode){
            case "missing"->candidate.remove("finishReason");case "null"->candidate.put("finishReason",null);
            case "MAX_TOKENS","SAFETY"->candidate.put("finishReason",mode);
            case "missing-content"->candidate.remove("content");
            case "thought-only"->candidate.put("content",Map.of("parts",List.of(Map.of("text","synthetic thought","thought",true))));
            case "tool-only"->candidate.put("content",Map.of("parts",List.of(Map.of("functionCall",Map.of("name","synthetic_never_called","args",Map.of())))));
        }
        response=json.writeValueAsString(Map.of("candidates",mode.equals("empty-candidates")?List.of():List.of(candidate)));
        var permit=sourcePermit();
        assertThatThrownBy(()->adapters.googleMediaUnderstandingPort(config("gemini-embedding-2"),clients,json)
            .describe(permit,new byte[]{1},new MediaUnderstandingRequest("video/mp4",null,null,1.)))
            .isInstanceOfSatisfying(DerivationFailure.class,f->{assertThat(f.category).isEqualTo(KnowledgeWork.Failure.INVALID_OUTPUT);
                assertThat(f.diagnostic.stage()).isEqualTo(ProviderFailureDiagnostic.Stage.MEDIA);}).hasCause(null);
        verify(permit,times(2)).requireGoogleDispatch();assertThat(requests).hasSize(1);
    }
    @Test void onlyMediaExtractorLineagesChangeAndTextLineageKeepsItsExactHash() throws Exception {
        var policy=new ProcessingPolicyService.AcknowledgedProcessingPolicy(new UUID(0,3),1,"a".repeat(64));
        for(String modality:List.of("note","image","audio","video","pdf")) {
            var lineage=EmbeddingLineage.create(config("gemini-embedding-2"),policy,modality);
            String legacy=lineage.configuration().replace("media-scoped-coverage-surrogate-v2","media-typed-bounded-v1")
                .replace("audio-speech-acoustic-surrogate-v3","media-typed-bounded-v1")
                .replace("video-visual-high-2fps-surrogate-v4","media-typed-bounded-v1");
            String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(legacy.getBytes(StandardCharsets.UTF_8)));
            if(modality.equals("note"))assertThat(lineage.id()).isEqualTo(hash);
            else assertThat(lineage.id()).isNotEqualTo(hash);
        }
    }
    @Test void audioAndTitlePoliciesKeepUnchangedMediaLineagesPinned() {
        var policy=new ProcessingPolicyService.AcknowledgedProcessingPolicy(new UUID(0,3),1,"a".repeat(64));
        var unchanged=Map.of(
            "note","edb939d1d6a39709a1424e00daa33be2bc0a91f0d15e04a9c3eba0013ba18196",
            "image","0b9549107ca501e284fc534c778cc414c613f2d53b59fecd86a0fabf3e8e1828",
            "pdf","06a016fe32a07221a67ba6e1332dff74c121345f3198e674948832e4dc4638df");
        unchanged.forEach((modality,hash)->assertThat(EmbeddingLineage.create(config("gemini-embedding-2"),policy,modality).id()).isEqualTo(hash));
        var audio=EmbeddingLineage.create(config("gemini-embedding-2"),policy,"audio");
        assertThat(audio.id()).isNotEqualTo("517832f7fad262113cf18c388374cdd5b31045270e500308d9fb4ebbfc66b411");
        assertThat(audio.configuration()).contains("audio-speech-acoustic-surrogate-v3");
        var video=EmbeddingLineage.create(config("gemini-embedding-2"),policy,"video");
        assertThat(video.id()).isNotEqualTo("9d979bbc5cfb02c7cacd73e249ada34577b7dab3e4c0245340e8271b24285365");
        assertThat(video.configuration()).contains("video-visual-high-2fps-surrogate-v4");
    }
    @Test void wrongQueryAndMissingKeyFailBeforeNetwork() {
        var port=adapters.googleStructuredKnowledgePort(config("gemini-embedding-2"),clients,json);
        assertThatThrownBy(()->port.embedQuery(gate().query(owner,query),query+"!")).isInstanceOf(DerivationFailure.class);
        assertThat(adapters.derivationGoogleClient(config("gemini-embedding-2"),new MockEnvironment())).isNull();
        assertThat(requests).isEmpty();
    }
    @Test void diagnosticsDiscardMessagesAndClassifyTransportWithoutClaimingProviderReceipt() {
        var failure=new com.google.genai.errors.GenAiIOException("synthetic-private-message",
            new java.net.SocketTimeoutException("synthetic-provider-body"));
        var safe=GoogleDerivationAdapters.providerFailure(failure,ProviderFailureDiagnostic.Stage.GENERATION,System.nanoTime()-11_000_000_000L);
        assertThat(safe.category).isEqualTo(KnowledgeWork.Failure.TRANSIENT_DEPENDENCY);
        assertThat(safe.diagnostic.kind()).isEqualTo(ProviderFailureDiagnostic.Kind.SOCKET_TIMEOUT);
        assertThat(safe.diagnostic.elapsed()).isEqualTo(ProviderFailureDiagnostic.Elapsed.TEN_TO_THIRTY_SECONDS);
        assertThat(safe.diagnostic.providerReached()).isEqualTo(ProviderFailureDiagnostic.Reach.UNKNOWN);
        assertThat(safe.diagnostic.httpStatus()).isNull();
        assertThat(safe.diagnostic.structurallyValidResponse()).isFalse();
        assertThat(safe).hasCause(null).hasMessage("Derivation unavailable");
        assertThat(json.writeValueAsString(safe.diagnostic)).doesNotContain("synthetic-private-message","synthetic-provider-body");
    }
    @Test void parsingDiagnosticsDistinguishAReceivedResponseFromTransportFailure() {
        generated("not json");
        var port=adapters.googleStructuredKnowledgePort(config("gemini-embedding-2"),clients,json);
        assertThatThrownBy(()->port.generate(evidence(),query,"answer",List.of(new StructuredKnowledgePort.Evidence("e0","Synthetic","note"))))
            .isInstanceOfSatisfying(DerivationFailure.class,f->{
                assertThat(f.category).isEqualTo(KnowledgeWork.Failure.INVALID_OUTPUT);
                assertThat(f.diagnostic.kind()).isEqualTo(ProviderFailureDiagnostic.Kind.PARSING);
                assertThat(f.diagnostic.providerReached()).isEqualTo(ProviderFailureDiagnostic.Reach.HTTP_RESPONSE_RECEIVED);
                assertThat(f.diagnostic.structurallyValidResponse()).isFalse();
            }).hasCause(null);
        assertThat(requests).hasSize(1);
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(ints={400,401,403,429,500,502,503,504})
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void realSdkHttpFailureClassificationAndDiagnosticsAreBoundedAndPrivate(int code,org.springframework.boot.test.system.CapturedOutput output) {
        status=code;response="{\"error\":{\"code\":"+code+",\"message\":\"synthetic-provider-private-body\",\"status\":\"UNAVAILABLE\"}}";
        var port=adapters.googleStructuredKnowledgePort(config("gemini-embedding-2"),clients,json);
        assertThatThrownBy(()->port.generate(evidence(),query,"extract",List.of(new StructuredKnowledgePort.Evidence("e0","Synthetic private source","note"))))
            .isInstanceOfSatisfying(DerivationFailure.class,f->{
                assertThat(f.diagnostic.httpStatus()).isEqualTo(code);
                assertThat(f.retryableProvider()).isEqualTo(code>=500);
                assertThat(f).hasCause(null).hasMessageNotContaining("synthetic-provider-private-body");
            });
        assertThat(requests).hasSize(1);assertThat(output.getAll()).contains("AI provider request unavailable")
            .doesNotContain("synthetic-provider-private-body","Synthetic private source",query,owner.toString());
    }
    @Test void configurationAndHttp400FailuresAreNotRetryableTransientFailures() {
        var configurationFailure=GoogleDerivationAdapters.providerFailure(new IllegalArgumentException("synthetic-private-message"));
        assertThat(configurationFailure.category).isEqualTo(KnowledgeWork.Failure.INVALID_OUTPUT);
        status=400;response="{\"error\":{\"code\":400,\"message\":\"synthetic-provider-body\",\"status\":\"INVALID_ARGUMENT\"}}";
        var port=adapters.googleStructuredKnowledgePort(config("gemini-embedding-2"),clients,json);
        assertThatThrownBy(()->port.generate(evidence(),query,"answer",List.of(new StructuredKnowledgePort.Evidence("e0","Synthetic","note"))))
            .isInstanceOfSatisfying(DerivationFailure.class,f->{
                assertThat(f.category).isEqualTo(KnowledgeWork.Failure.INVALID_OUTPUT);
                assertThat(f.diagnostic.httpStatus()).isEqualTo(400);
                assertThat(f.diagnostic.kind()).isEqualTo(ProviderFailureDiagnostic.Kind.HTTP);
                assertThat(f.diagnostic.providerReached()).isEqualTo(ProviderFailureDiagnostic.Reach.HTTP_RESPONSE_RECEIVED);
            }).hasCause(null).hasMessageNotContaining("synthetic-provider-body");
        assertThat(requests).hasSize(1);
    }
    @Test void embedding2TaskFormatCreatesANewLineageWithoutChangingLegacyFormat() {
        var policy=new ProcessingPolicyService.AcknowledgedProcessingPolicy(new UUID(0,3),1,"a".repeat(64));
        var lineage=EmbeddingLineage.create(config("gemini-embedding-2"),policy,"note");
        assertThat(lineage.configuration()).contains(GeminiTextEmbeddings.EMBEDDING_2_FORMAT);
        String oldConfiguration=lineage.configuration().replace(GeminiTextEmbeddings.EMBEDDING_2_FORMAT,"document/text-surrogate/float32");
        assertThat(lineage.id()).isNotEqualTo(java.util.HexFormat.of().formatHex(digest(oldConfiguration)));
        assertThat(EmbeddingLineage.create(config("gemini-embedding-001"),policy,"note").configuration()).contains("document/text-surrogate/float32").doesNotContain(GeminiTextEmbeddings.EMBEDDING_2_FORMAT);
    }
    private byte[] digest(String text){try{return java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));}catch(Exception impossible){throw new AssertionError(impossible);}}
}
