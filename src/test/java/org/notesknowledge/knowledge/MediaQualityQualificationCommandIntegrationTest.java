package org.notesknowledge.knowledge;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.sun.net.httpserver.HttpServer;
import com.google.genai.Client;
import com.google.genai.types.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/** Actual command, SDK, upload, PostgreSQL gates/leases/activation and citation engine.
 * External model responses and selected child transforms are controlled.
 * Redis rate enforcement is real. The PDF fixture exercises the real worker/protocol, not OS isolation.
 * Simulated pacing clock is not an auth clock and cannot extend production permits/leases. */
@Tag("DATABASE") @Tag("SECURITY") @Tag("RETRIEVAL") @Tag("EVALUATION")
class MediaQualityQualificationCommandIntegrationTest {
    @TempDir Path directory;
    private static HttpServer server;
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final AtomicInteger calls=new AtomicInteger();
    private static final List<Integer> wireBytes=new java.util.concurrent.CopyOnWriteArrayList<>();
    private static final List<String> models=new java.util.concurrent.CopyOnWriteArrayList<>();

    @ParameterizedTest @ValueSource(booleans={false,true})
    void preparedFrozenUploadsExecuteThroughActualSdkAndPersistedAuthorizedSearchWithoutAutoApproval(boolean children)throws Exception {
        calls.set(0);wireBytes.clear();models.clear();
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",MediaQualityQualificationCommandIntegrationTest::respond);server.start();
        try(var postgres=new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18-trixie").withDatabaseName("nkw_media_qualification")
                .withUsername("synthetic_migrator").withPassword("synthetic-qualification-password");
            var redis=new org.testcontainers.containers.GenericContainer<>("redis:8.10.2").withExposedPorts(6379)){
            postgres.start();redis.start();
            String[] args={"--spring.datasource.url="+postgres.getJdbcUrl(),"--spring.datasource.username="+postgres.getUsername(),"--spring.datasource.password="+postgres.getPassword(),
                "--spring.data.redis.host="+redis.getHost(),"--spring.data.redis.port="+redis.getMappedPort(6379),
                "--knowledge.derivation.provider=gemini","--knowledge.derivation.embedding-model=gemini-embedding-2","--knowledge.derivation.media-model=gemini-3.8-flash",
                "--knowledge.derivation.model-revision=synthetic-wire-v1","--knowledge.derivation.region=global","--knowledge.derivation.tier=unpaid",
                "--knowledge.derivation.configuration-id=synthetic-wire-v1","--knowledge.derivation.adapter-version=google-v2","--knowledge.derivation.dimension=8",
                "--knowledge.derivation.operator=cosine","--knowledge.derivation.normalization=none","--knowledge.derivation.approved-policy-fingerprint="+"a".repeat(64),
                "--knowledge.derivation.pdf-page-children="+children,"--knowledge.derivation.video-children="+children,
                "--knowledge.derivation.pdf-transform-fingerprint="+org.notesknowledge.notes.PdfPageRuntimeFixtures.transformFingerprint(),
                "--knowledge.derivation.video-transform-fingerprint="+org.notesknowledge.notes.VideoClipRuntimeFixtures.fingerprint()};
            var selection=children?List.of("mixed","long-video"):List.of("image","mixed","audio","tones","video","diagram-image","diagram-pdf","text-pdf","scan-pdf");
            Class<?>[] configurations={ControlledProvider.class,org.notesknowledge.notes.PdfPageRuntimeFixtures.class,org.notesknowledge.notes.VideoClipRuntimeFixtures.class};
            try(var context=MediaQualityQualificationCommand.start(directory,null,args,configurations)){
                MediaQualityQualificationCommand.prepare(context,directory,selection);
                assertThat(calls).hasValue(0); // Neither upload nor planning is disclosure.
                assertThatThrownBy(()->MediaQualityQualificationCommand.prepare(context,directory,selection)).isInstanceOf(IllegalStateException.class);
            }
            var plan=JSON.readValue(Files.readAllBytes(directory.resolve("plan.json")),MediaQualityQualificationCommand.Plan.class);
            assertThat(plan.sources()).hasSize(selection.size());assertThat(plan.children()).hasSize(children?5:0);
            var clock=new QualificationRequestBudgetTest.MutableClock();
            try(var context=MediaQualityQualificationCommand.start(directory,plan,args,configurations)){
                MediaQualityQualificationCommand.execute(context,directory,plan,new QualificationRequestBudget.Limits(64,64L*1024*1024,114688,15),clock,
                    duration->clock.now=clock.now.plus(duration),"controlled-sdk");
                var jdbc=context.getBean(JdbcTemplate.class);
                assertThat(jdbc.queryForObject("select count(*) from knowledge.private_derived_representation where state='ready' and owner_user_id=?",Integer.class,plan.owner())).isEqualTo(selection.size());
                assertThat(jdbc.queryForObject("select count(*) from knowledge.private_derived_segment where owner_user_id=?",Integer.class,plan.owner())).isGreaterThanOrEqualTo(selection.size());
                for(String modality:List.of("image","audio","video","pdf")){
                    var expected=plan.sources().stream().filter(s->s.metadata().modality().equals(modality)).map(s->s.metadata().expected().attachmentId()).toList();
                    var candidates=context.getBean(ExactPrivateVectorSearch.class).search(plan.owner(),modality,new float[]{1,0,0,0,0,0,0,0},100);
                    assertThat(candidates.stream().map(candidate->candidate.expected().attachmentId()).distinct().toList()).containsAll(expected);
                }
                assertThatThrownBy(()->MediaQualityQualificationCommand.execute(context,directory,plan,new QualificationRequestBudget.Limits(64,64L*1024*1024,114688,15),clock,
                    duration->clock.now=clock.now.plus(duration),"controlled-sdk")).isInstanceOf(FileAlreadyExistsException.class);
            }
            var result=JSON.readTree(Files.readAllBytes(directory.resolve("result.json")));
            assertThat(result.get("mode").asText()).isEqualTo("controlled-sdk");assertThat(result.get("qualityQualified").asBoolean()).isFalse();
            assertThat(result.get("queries").size()).isEqualTo(children?1:5);
            for(var query:result.get("queries")){
                assertThat(query.get("degraded").asText()).isEqualTo("none");assertThat(query.get("claims").asInt()).isEqualTo(1);
                assertThat(query.get("validatedCitations").asInt()).isGreaterThan(0);
            }
            assertThat(result.get("budget").get("requests").asInt()).isEqualTo(calls.get());
            assertThat(wireBytes).hasSize(calls.get()).allMatch(size->size>0&&size<20_000_000);
            assertThat(models).anyMatch(path->path.contains("gemini-embedding-2")).anyMatch(path->path.contains("gemini-3.8-flash"));
            assertThat(Files.readString(directory.resolve("result.json"))).doesNotContain("synthetic-not-a-provider-key","embeddings","values","Authorization");
            System.out.println(JSON.writeValueAsString(Map.of("mode","controlled-sdk","acceptedMediaCases",selection.size(),"approvedChildren",plan.children().size(),"requests",calls.get(),
                "serializedSdkBytes",wireBytes.stream().mapToLong(Integer::longValue).sum(),"peakSerializedSdkBytes",wireBytes.stream().mapToInt(Integer::intValue).max().orElse(0),
                "queryCases",children?1:5,"liveQualityQualified",false)));
        }finally{server.stop(0);}
    }
    private static void respond(com.sun.net.httpserver.HttpExchange exchange)throws java.io.IOException {
        int status=200;String response;
        try {
            require(exchange.getRequestURI().getRawQuery()==null);
            byte[] bytes=exchange.getRequestBody().readNBytes(20_000_001);require(bytes.length<20_000_000);
            calls.incrementAndGet();require(calls.get()<=64);wireBytes.add(bytes.length);models.add(exchange.getRequestURI().getPath());
            var input=JSON.readTree(bytes);
            if(exchange.getRequestURI().getPath().contains("embedContent")||exchange.getRequestURI().getPath().contains("batchEmbedContents"))
                response="{\"embeddings\":[{\"values\":[1,0,0,0,0,0,0,0]}]}";
            else {
                require(input.get("generationConfig").get("maxOutputTokens").asInt()==8192);
                var parts=input.get("contents").get(0).get("parts");String instruction=null;tools.jackson.databind.JsonNode media=null;
                for(var part:parts){if(part.has("text"))instruction=part.get("text").asText();if(part.has("inlineData"))media=part.get("inlineData");}
                require(instruction!=null);
                Object output;
                if(media!=null){
                    String type=media.get("mimeType").asText();byte[] payload=Base64.getDecoder().decode(media.get("data").asText());
                    var segments=new ArrayList<Map<String,Object>>();var pages=new ArrayList<Integer>();boolean noSpeech=false;
                    var childPage=java.util.regex.Pattern.compile("ONLY original PDF page ([0-9]+)").matcher(instruction);
                    if(type.equals("image/png")&&childPage.find()){
                        int page=Integer.parseInt(childPage.group(1));pages.add(page);
                        segments.add(Map.of("text","ASTER blue square red circle","kind","pdf_text","heading","","page",page));
                    }else if(type.equals("application/pdf")){
                        var match=java.util.regex.Pattern.compile("requested page ([0-9]+) through ([0-9]+)").matcher(instruction);require(match.find());
                        for(int page=Integer.parseInt(match.group(1));page<=Integer.parseInt(match.group(2));page++){
                            pages.add(page);segments.add(Map.of("text","ASTER blue square red circle equipment reserve 740 credits","kind","pdf_text","heading","","page",page));}
                    }else if(type.equals("audio/wav")){
                        noSpeech=payload.length==64044;
                        segments.add(Map.of("text",noSpeech?"Possibly high tones change to lower tones":"The fictional observatory is on planet Aster. Its dome is silver.",
                            "kind","transcript","heading",noSpeech?"Acoustic description":"Transcript","timeStart",0,"timeEnd",1));
                    }else if(type.equals("video/mp4"))segments.add(Map.of("text","ASTER blue square beside red circle","kind","video_scene","heading","","timeStart",0,"timeEnd",1));
                    else segments.add(Map.of("text","ASTER blue square beside red circle equipment reserve 740 credits","kind","whole_image","heading",""));
                    Arrays.fill(payload,(byte)0);output=Map.of("complete",true,"checkedPages",pages,"noSpeech",noSpeech,"segments",segments);
                }else {
                    var evidence=JSON.readTree(instruction.substring(instruction.indexOf('\n')+1)).get("evidence");
                    var ids=new ArrayList<String>();for(var item:evidence)ids.add(item.get("id").asText());
                    output=Map.of("claims",List.of(Map.of("text","Controlled supported claim","evidenceIds",ids)),"conflicting",false);
                }
                response=JSON.writeValueAsString(Map.of("modelVersion","gemini-3.8-flash","candidates",List.of(Map.of("content",Map.of("role","model","parts",List.of(Map.of("text",JSON.writeValueAsString(output)))),"finishReason","STOP"))));
            }
        }catch(Exception|AssertionError failure){status=500;response="{\"error\":{\"code\":500,\"status\":\"INTERNAL\",\"message\":\"Synthetic controlled transport failure\"}}";}
        byte[] bytes=response.getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");
        exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
    }
    private static void require(boolean value){if(!value)throw new AssertionError("Controlled wire invariant failed");}
    @TestConfiguration(proxyBeanMethods=false)
    static class ControlledProvider {
        @Bean @Primary Client controlledQualificationClient(){return Client.builder().apiKey("synthetic-not-a-provider-key")
            .httpOptions(HttpOptions.builder().baseUrl("http://127.0.0.1:"+server.getAddress().getPort()).timeout(2000)
                .retryOptions(HttpRetryOptions.builder().attempts(1).build()).build()).build();}
    }
}
