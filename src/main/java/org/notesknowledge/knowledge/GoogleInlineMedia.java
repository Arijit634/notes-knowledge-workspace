package org.notesknowledge.knowledge;

import com.google.genai.Client;
import com.google.genai.types.ClientOptions;
import com.google.genai.types.HttpOptions;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSink;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** One SDK call with a bounded, streaming inline body; no Files API or alternate transport. */
final class GoogleInlineMedia {
    private static final long MAX_RESPONSE_BYTES=1_048_576;
    private final HttpOptions options;
    GoogleInlineMedia(HttpOptions options){this.options=options;}
    // The SDK transforms this one-byte value. The call-local interceptor replaces
    // it before dispatch; real bytes never enter its repeated BinaryNode copies.
    static byte[] placeholder(){return new byte[]{0};}

    Client open(Client configured,byte[] bytes,String type,ObjectMapper json,Runnable authorize) {
        if(configured==null||configured.vertexAI()||bytes==null||bytes.length==0
                ||!List.of("image/png","image/jpeg","audio/wav","video/mp4","application/pdf").contains(type))
            throw new DerivationFailure(KnowledgeWork.Failure.INVALID_SOURCE);
        var dispatched=new AtomicBoolean();
        var http=new OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
            .connectTimeout(java.time.Duration.ZERO).readTimeout(java.time.Duration.ZERO).writeTimeout(java.time.Duration.ZERO)
            .callTimeout(java.time.Duration.ofMillis(options.timeout().orElseThrow()))
            .addInterceptor(chain->{
                var request=chain.request();var original=request.body();
                if(!request.method().equals("POST")||request.url().query()!=null||original==null
                        ||original.contentLength()<0||original.contentLength()>MediaUnderstandingRequest.ENVELOPE_RESERVE_BYTES)
                    throw invalid();
                var buffer=new Buffer();original.writeTo(buffer);
                var root=json.readTree(buffer.readByteArray());
                var items=root.path(request.url().encodedPath().endsWith(":generateContent")?"contents":"requests");
                if(!items.isArray()||items.size()!=1)throw invalid();
                var parts=request.url().encodedPath().endsWith(":generateContent")
                    ?items.path(0).path("parts")
                    :request.url().encodedPath().endsWith(":batchEmbedContents")
                        ?items.path(0).path("content").path("parts"):null;
                if(parts==null||!parts.isArray()||parts.isEmpty()||parts.size()>2)throw invalid();
                ObjectNode inline=null;
                for(var part:parts) {
                    if(part.has("inlineData")) {
                        if(inline!=null||!(part.get("inlineData") instanceof ObjectNode value)
                                ||!value.path("mimeType").asString().equals(type)||!value.path("data").asString().equals("AA=="))throw invalid();
                        inline=value;
                    }else if(part.size()!=1||!part.path("text").isString())throw invalid();
                }
                if(inline==null)throw invalid();
                inline.put("data","");
                long length=json.writeValueAsBytes(root).length+4L*((bytes.length+2L)/3);
                if(length>=MediaUnderstandingRequest.MAX_INLINE_REQUEST_BYTES)throw new DerivationFailure(KnowledgeWork.Failure.BUDGET_EXCEEDED);
                inline.set("data",json.getNodeFactory().binaryNode(bytes));
                var written=new AtomicBoolean();
                RequestBody body=new RequestBody() {
                    @Override public MediaType contentType(){return MediaType.get("application/json");}
                    @Override public long contentLength(){return length;}
                    @Override public boolean isOneShot(){return true;}
                    @Override public void writeTo(BufferedSink sink)throws IOException {
                        if(!written.compareAndSet(false,true))throw new IOException("Inline request cannot be replayed");
                        // Jackson writes binary values incrementally into OkHttp's
                        // bounded sink; no full base64 String or byte[] is built.
                        json.writeValue(new java.io.FilterOutputStream(sink.outputStream()) {
                            @Override public void close()throws IOException{flush();}
                        },root);
                    }
                    @Override public String toString(){return "InlineMediaBody[REDACTED]";}
                };
                authorize.run();
                if(!dispatched.compareAndSet(false,true))throw new IOException("Inline request cannot be replayed");
                var response=chain.proceed(request.newBuilder().method("POST",body).build());
                var received=response.body();
                if(received==null)return response;
                if(received.contentLength()>MAX_RESPONSE_BYTES){response.close();throw invalid();}
                var bounded=okio.Okio.buffer(new okio.ForwardingSource(received.source()) {
                    private long count;
                    @Override public long read(Buffer sink,long requested)throws IOException {
                        long read=super.read(sink,Math.min(requested,MAX_RESPONSE_BYTES-count+1));
                        if(read>0&& (count+=read)>MAX_RESPONSE_BYTES)throw invalid();
                        return read;
                    }
                });
                return response.newBuilder().body(new ResponseBody() {
                    @Override public MediaType contentType(){return received.contentType();}
                    @Override public long contentLength(){return received.contentLength();}
                    @Override public okio.BufferedSource source(){return bounded;}
                }).build();
            }).build();
        return Client.builder().apiKey(configured.apiKey()).vertexAI(false).httpOptions(options)
            .clientOptions(ClientOptions.builder().customHttpClient(http).build()).build();
    }
    private static DerivationFailure invalid(){return new DerivationFailure(KnowledgeWork.Failure.INVALID_OUTPUT);}
}
