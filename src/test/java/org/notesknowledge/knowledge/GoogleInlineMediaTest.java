package org.notesknowledge.knowledge;

import static org.assertj.core.api.Assertions.*;
import com.google.genai.Client;
import com.google.genai.types.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import tools.jackson.databind.ObjectMapper;

@Tag("FAST") @Tag("SECURITY") @Tag("RETRIEVAL") @Tag("EVALUATION")
class GoogleInlineMediaTest {
    private final ObjectMapper json=new ObjectMapper();
    private HttpServer server;
    private HttpOptions options;
    private Client configured;
    private volatile byte[] received;
    private volatile int requests,status=200;
    private volatile boolean chunked;
    private volatile java.util.concurrent.CountDownLatch hold;
    private String response="{\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":[{\"text\":\"{}\"}]}}]}";
    @BeforeEach void start()throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            requests++;received=exchange.getRequestBody().readNBytes(65_537);
            if(received.length>65_536)throw new AssertionError("Small controlled request exceeded its bound");
            if(hold!=null)try{hold.await(10,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
            if(status==307)exchange.getResponseHeaders().set("Location","/redirect-must-not-be-followed");
            byte[] value=response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status,chunked?0:value.length);exchange.getResponseBody().write(value);exchange.close();
        });server.start();
        options=HttpOptions.builder().baseUrl("http://127.0.0.1:"+server.getAddress().getPort()).timeout(10000)
            .retryOptions(HttpRetryOptions.builder().attempts(1).build()).build();
        configured=Client.builder().apiKey("synthetic-not-a-provider-key").httpOptions(options).build();
    }
    @AfterEach void stop(){configured.close();server.stop(0);}
    private GenerateContentResponse call(byte[] original,Runnable authorize) {
        try(var inline=new GoogleInlineMedia(options).open(configured,original,"video/mp4",json,authorize)) {
            return inline.models.generateContent("gemini-3.5-flash-lite",Content.fromParts(
                Part.fromBytes(GoogleInlineMedia.placeholder(),"video/mp4"),Part.fromText("A synthetic question")),
                GenerateContentConfig.builder().maxOutputTokens(8192).build());
        }
    }
    @Test void originalBytesAreSentOnceAndFinalDispatchVetoPreventsAllNetwork()throws Exception {
        byte[] original={0,1,2,3,127,-128,-1};var checks=new AtomicInteger();
        assertThat(call(original,checks::incrementAndGet).candidates().orElseThrow()).hasSize(1);
        assertThat(checks).hasValue(1);assertThat(requests).isEqualTo(1);
        var data=json.readTree(received).path("contents").path(0).path("parts").path(0).path("inlineData").path("data").asString();
        assertThat(java.util.Base64.getDecoder().decode(data)).isEqualTo(original);
        assertThat(original).containsExactly((byte)0,(byte)1,(byte)2,(byte)3,(byte)127,(byte)-128,(byte)-1);
        assertThatThrownBy(()->call(original,()->{throw new DerivationFailure(KnowledgeWork.Failure.INVALID_SOURCE);}))
            .isInstanceOfSatisfying(DerivationFailure.class,f->assertThat(f.category).isEqualTo(KnowledgeWork.Failure.INVALID_SOURCE));
        assertThat(requests).isEqualTo(1);
    }
    @Test void redirectsAndServerFailuresNeverReplayOriginalMedia() {
        for(int code:new int[]{307,429,503}) {
            status=code;
            assertThatThrownBy(()->call(new byte[]{1,2},()->{})).isInstanceOf(RuntimeException.class);
        }
        assertThat(requests).isEqualTo(3);
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void oversizedProviderBodyIsRejectedBeforeSdkMaterialization(boolean streamed) {
        chunked=streamed;
        response="x".repeat(1_048_577);
        assertThatThrownBy(()->call(new byte[]{1,2},()->{})).isInstanceOfSatisfying(DerivationFailure.class,
            f->assertThat(f.category).isEqualTo(KnowledgeWork.Failure.INVALID_OUTPUT));
        assertThat(requests).isEqualTo(1);
    }
    @Test void requestDeadlineAndOneShotScopePreventResendingMedia() {
        var shortOptions=options.toBuilder().timeout(1000).build();hold=new java.util.concurrent.CountDownLatch(1);
        try(var inline=new GoogleInlineMedia(shortOptions).open(configured,new byte[]{1,2},"video/mp4",json,()->{})) {
            assertThatThrownBy(()->inline.models.generateContent("gemini-3.5-flash-lite",
                Content.fromParts(Part.fromBytes(GoogleInlineMedia.placeholder(),"video/mp4")),null)).isInstanceOf(RuntimeException.class);
            hold.countDown();
            assertThatThrownBy(()->inline.models.generateContent("gemini-3.5-flash-lite",
                Content.fromParts(Part.fromBytes(GoogleInlineMedia.placeholder(),"video/mp4")),null)).isInstanceOf(RuntimeException.class);
        }finally{hold.countDown();}
        assertThat(requests).isEqualTo(1);
    }
    @Test void serializedBudgetIsEnforcedBeforeNetwork() {
        assertThatThrownBy(()->call(new byte[13_500_000],()->{})).isInstanceOfSatisfying(DerivationFailure.class,
            f->assertThat(f.category).isEqualTo(KnowledgeWork.Failure.BUDGET_EXCEEDED));
        assertThat(requests).isZero();
    }
    @Test void missingPlaceholderAndMultipleMediaCannotSendAnUnboundBody() {
        try(var inline=new GoogleInlineMedia(options).open(configured,new byte[]{1,2},"video/mp4",json,()->{})) {
            for(var parts:java.util.List.of(java.util.List.of(Part.fromText("No media")),
                    java.util.List.of(Part.fromBytes(new byte[]{9},"video/mp4")),
                    java.util.List.of(Part.fromBytes(GoogleInlineMedia.placeholder(),"video/mp4"),Part.fromBytes(GoogleInlineMedia.placeholder(),"video/mp4"))))
                assertThatThrownBy(()->inline.models.generateContent("gemini-3.5-flash-lite",Content.builder().parts(parts).build(),null))
                    .isInstanceOf(DerivationFailure.class);
        }
        assertThat(requests).isZero();
    }
    @Test void largestInlineVideoFitsHostedHeapWithEightyMiBOfOtherLiveData()throws Exception {
        var javaExecutable=java.nio.file.Path.of(System.getProperty("java.home"),"bin","java").toString();
        var cp=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        var builder=new ProcessBuilder(javaExecutable,"-Xmx160m","-XX:+UseSerialGC","-XX:TieredStopAtLevel=1",
            "-XX:ActiveProcessorCount=1","-XX:MaxDirectMemorySize=16m","-cp",cp,MemoryProbe.class.getName());
        builder.environment().clear();builder.environment().put("LANG","C.UTF-8");
        var process=builder.redirectErrorStream(true).start();
        boolean finished=process.waitFor(45,java.util.concurrent.TimeUnit.SECONDS);
        if(!finished)process.destroyForcibly().waitFor();
        assertThat(finished).isTrue();
        assertThat(process.exitValue()).isZero();
        assertThat(new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8).strip()).isEqualTo("INLINE_MEMORY_OK");
    }
    /** Separate constrained process: loopback parser drains Base64 without a whole-body copy. */
    public static final class MemoryProbe {
        private static volatile byte[] retained;
        public static void main(String[] args)throws Exception {
            byte[] original=new byte[13_400_000];java.util.Arrays.fill(original,(byte)2);
            retained=new byte[80*1024*1024];java.util.Arrays.fill(retained,(byte)1);
            var digest=java.security.MessageDigest.getInstance("SHA-256").digest(original);
            var checked=new java.util.concurrent.atomic.AtomicBoolean();
            var json=new ObjectMapper();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/",exchange->{
                try(var parser=json.createParser(exchange.getRequestBody())) {
                    int found=0;
                    while(parser.nextToken()!=null)if(parser.currentToken()==tools.jackson.core.JsonToken.VALUE_STRING&&"data".equals(parser.currentName())) {
                        var received=java.security.MessageDigest.getInstance("SHA-256");
                        parser.readBinaryValue(new java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(),received));
                        if(!java.security.MessageDigest.isEqual(digest,received.digest()))throw new AssertionError("Different inline media");found++;
                    }
                    if(found!=1)throw new AssertionError("Missing inline media");checked.set(true);
                    byte[] response="{\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":[{\"text\":\"{}\"}]}}]}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);
                }catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
                finally{exchange.close();}
            });server.start();
            var options=HttpOptions.builder().baseUrl("http://127.0.0.1:"+server.getAddress().getPort()).timeout(20000)
                .retryOptions(HttpRetryOptions.builder().attempts(1).build()).build();
            try(var configured=Client.builder().apiKey("synthetic-not-a-provider-key").httpOptions(options).build();
                    var inline=new GoogleInlineMedia(options).open(configured,original,"video/mp4",json,()->{})) {
                var result=inline.models.generateContent("gemini-3.5-flash-lite",Content.fromParts(Part.fromBytes(GoogleInlineMedia.placeholder(),"video/mp4")),null);
                if(!checked.get()||!result.candidates().orElseThrow().getFirst().finishReason().orElseThrow().toString().equals("STOP")||retained[0]!=1)
                    throw new AssertionError("Constrained inline request failed");
                System.out.println("INLINE_MEMORY_OK");
            }finally{server.stop(0);retained=null;}
        }
    }
}
