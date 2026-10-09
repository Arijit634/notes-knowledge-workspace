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
    private final GoogleDerivationAdapters adapters=new GoogleDerivationAdapters();
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
        return new AiDerivationProperties(true,false,"gemini",model,"gemini-3.8-flash","synthetic-v1","global","unpaid",
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
        sdk=Client.builder().apiKey("synthetic-not-a-provider-key").httpOptions(HttpOptions.builder()
            .baseUrl("http://127.0.0.1:"+server.getAddress().getPort()).timeout(2000)
            .retryOptions(HttpRetryOptions.builder().attempts(1).build()).build()).build();
        clients=mock(ObjectProvider.class);when(clients.getIfAvailable()).thenReturn(sdk);
    }
    @AfterEach void stop(){sdk.close();server.stop(0);}
    private KnowledgeQueryGate gate() {
        var policies=mock(ProcessingPolicyService.class);var sources=mock(PrivateQuerySource.class);
        when(policies.policyPrerequisite(owner)).thenReturn(Optional.of(new ProcessingPolicyService.AcknowledgedProcessingPolicy(new UUID(0,3),1,"a".repeat(64))));
        when(sources.matches(any(),eq(true))).thenReturn(true);
        var properties=new ProviderDispatchProperties("unpaid-synthetic-demo",List.of(ProviderDispatchPolicy.fingerprint(expected)),List.of(ProviderDispatchPolicy.queryFingerprint(query)));
        return new KnowledgeQueryGate(policies,config("gemini-embedding-2"),new ProviderDispatchPolicy(properties),properties,sources);
    }
    private KnowledgeQueryGate.EvidencePermit evidence() {
        return gate().evidence(owner,query,List.of(new PrivateQuerySource.Source(expected,"active",true,"note",Instant.EPOCH,"")),mock(DispatchCoordinator.Handle.class));
    }
    private AiProcessingGate.SourceAiPermit sourcePermit(){return mock(AiProcessingGate.SourceAiPermit.class);}
    private void generated(String output) {
        response=json.writeValueAsString(Map.of("modelVersion","gemini-3.8-flash","candidates",List.of(Map.of("content",Map.of("role","model","parts",List.of(Map.of("text",output))),"finishReason","STOP"))));
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
    @Test void structuredGenerationIsSchemaBoundWithoutCandidateOverrideToolsOrCache() {
        generated("{\"claims\":[{\"text\":\"The fictional observatory is on Luna.\",\"evidenceIds\":[\"e0\"]}],\"conflicting\":false}");
        var result=adapters.googleStructuredKnowledgePort(config("gemini-embedding-2"),clients,json).generate(evidence(),query,"answer",
            List.of(new StructuredKnowledgePort.Evidence("e0","The fictional observatory is on Luna.","note")));
        assertThat(result.claims()).hasSize(1);assertThat(result.claims().getFirst().evidenceIds()).containsExactly("e0");
        assertThat(requests).hasSize(1);
        var wire=json.readTree(requests.getFirst());
        assertThat(wire.get("generationConfig").has("candidateCount")).isFalse();
        assertThat(wire.get("generationConfig").get("responseMimeType").asText()).isEqualTo("application/json");
        assertThat(wire.get("generationConfig").has("responseJsonSchema")).isTrue();
        assertThat(wire.has("tools")).isFalse();assertThat(wire.has("cachedContent")).isFalse();
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
        generated("[{\"text\":\"Synthetic description\",\"kind\":\"whole_image\",\"heading\":\"\"}]");
        String kind=switch(type){case "image/png"->"image";case "audio/wav"->"audio";case "video/mp4"->"video";default->"pdf";};
        byte[] bytes=org.notesknowledge.notes.DerivationMediaFixtures.media(kind).getBytes();
        var result=adapters.googleMediaUnderstandingPort(config("gemini-embedding-2"),clients,json).describe(sourcePermit(),bytes,type);
        assertThat(result).hasSize(1);
        var wire=json.readTree(requests.getFirst());
        var inline=wire.get("contents").get(0).get("parts").get(1).get("inlineData");
        assertThat(inline.get("mimeType").asText()).isEqualTo(type);
        assertThat(Base64.getDecoder().decode(inline.get("data").asText())).isEqualTo(bytes);
        assertThat(wire.get("generationConfig").get("responseJsonSchema").get("items").get("properties").get("page").has("anyOf")).isTrue();
        assertThat(requests.getFirst()).contains("responseJsonSchema").doesNotContain("fileUri","candidateCount","googleSearch","cachedContent","nullable");
        // Actual synthetic upload fixtures, but no claim of live media understanding or native embeddings.
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
    @Test void wrongQueryAndMissingKeyFailBeforeNetwork() {
        var port=adapters.googleStructuredKnowledgePort(config("gemini-embedding-2"),clients,json);
        assertThatThrownBy(()->port.embedQuery(gate().query(owner,query),query+"!")).isInstanceOf(DerivationFailure.class);
        assertThat(adapters.derivationGoogleClient(config("gemini-embedding-2"),new MockEnvironment())).isNull();
        assertThat(requests).isEmpty();
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
