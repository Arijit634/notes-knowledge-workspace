package org.notesknowledge.notes;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.http.*;

/** External HTTP boundary only. The production adapter, SDK, signing and XML
 * codecs remain real. Durable synthetic bytes are isolated in the test directory.
 * Never retains request headers, authorization or SDK exceptions. No network. */
public final class ControlledObjectStorageTransport implements SdkHttpClient {
    final Path directory;
    final AtomicInteger calls=new AtomicInteger();
    boolean ambiguousPut, failDelete, wrongRange, shortRange, redirect, checksumMismatch, ignoredRange, malformedInventory;
    boolean createOnly, checksumVerified, closedBody;
    int largestRead;

    public ControlledObjectStorageTransport(Path directory){this.directory=directory;}
    static PrivateStorageProperties properties(){return new PrivateStorageProperties(true,"oci-s3-private-v1",
            "https://syntheticnamespace.compat.objectstorage.ap-mumbai-1.oraclecloud.com","ap-mumbai-1",
            "synthetic-private-bucket","injected",true,false);}
    static OciPrivateAttachmentObjectStore store(ControlledObjectStorageTransport transport){
        var properties=properties();
        return new OciPrivateAttachmentObjectStore(OciPrivateAttachmentObjectStore.client(properties,
                StaticCredentialsProvider.create(AwsBasicCredentials.create("synthetic-access-key","synthetic-secret-key")),transport),properties.bucket());
    }
    @Override public ExecutableHttpRequest prepareRequest(HttpExecuteRequest request){
        return new ExecutableHttpRequest(){
            public HttpExecuteResponse call()throws IOException {return execute(request);}
            public void abort(){ /* No external socket. Response ownership is still tested. */ }
        };
    }
    private synchronized HttpExecuteResponse execute(HttpExecuteRequest request)throws IOException {
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new AssertionError("Object transport invoked in database transaction");
        calls.incrementAndGet();var http=request.httpRequest();
        if(!http.getUri().getHost().equals("syntheticnamespace.compat.objectstorage.ap-mumbai-1.oraclecloud.com"))
            throw new AssertionError("Unexpected synthetic endpoint");
        if(http.firstMatchingHeader("Authorization").filter(v->v.startsWith("AWS4-HMAC-SHA256 ")).isEmpty())
            throw new AssertionError("Missing request signature");
        if(redirect)return response(307,Map.of("Location","https://untrusted.invalid/object"),new byte[0]);
        String path=http.encodedPath(),prefix="/synthetic-private-bucket/";
        if(http.method()==SdkHttpMethod.GET&&http.rawQueryParameters().containsKey("list-type"))return listing(http);
        if(!path.startsWith(prefix))throw new AssertionError("Unexpected synthetic path");
        String key=path.substring(prefix.length());
        if(!key.matches("private-attachment/[0-9a-f]{64}"))throw new AssertionError("Unexpected synthetic locator");
        Path file=directory.resolve(key.substring("private-attachment/".length()));
        if(http.method()==SdkHttpMethod.PUT){
            createOnly=http.firstMatchingHeader("If-None-Match").orElse("").equals("*");
            if(!createOnly)throw new AssertionError("Write is not create-only");
            if(Files.exists(file))return error(412,"PreconditionFailed");
            try(var input=request.contentStreamProvider().orElseThrow().newStream();
                    var output=Files.newOutputStream(file,StandardOpenOption.CREATE_NEW)){
                var digest=MessageDigest.getInstance("MD5");byte[] buffer=new byte[8192];long size=0;int n;
                while((n=input.read(buffer))!=-1){largestRead=Math.max(largestRead,n);size+=n;digest.update(buffer,0,n);output.write(buffer,0,n);}
                checksumVerified=Base64.getEncoder().encodeToString(digest.digest()).equals(http.firstMatchingHeader("Content-MD5").orElse(""));
                if(!checksumVerified||!Long.toString(size).equals(http.firstMatchingHeader("Content-Length").orElse("")))
                    throw new AssertionError("Synthetic upload checksum/length mismatch");
            }catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
            if(ambiguousPut){ambiguousPut=false;throw new IOException("Synthetic lost upload response");}
            return response(200,Map.of("ETag","\"synthetic-etag\""),new byte[0]);
        }
        if(http.method()==SdkHttpMethod.DELETE){
            if(failDelete)return error(503,"ServiceUnavailable");
            Files.deleteIfExists(file);return response(204,Map.of(),new byte[0]);
        }
        if(http.method()!=SdkHttpMethod.GET)throw new AssertionError("Unexpected synthetic method");
        if(!Files.exists(file))return error(404,"NoSuchKey");
        var match=java.util.regex.Pattern.compile("bytes=([0-9]+)-([0-9]+)").matcher(http.firstMatchingHeader("Range").orElse(""));
        if(!match.matches())throw new AssertionError("Missing bounded Range");
        long first=Long.parseLong(match.group(1)),last=Long.parseLong(match.group(2)),size=Files.size(file),length=last-first+1;
        if(last>=size)return error(416,"InvalidRange");
        var input=Files.newInputStream(file);input.skipNBytes(first);long sent=shortRange?length-1:length;
        var body=new FilterInputStream(input){
            long remaining=sent;
            @Override public int read()throws IOException{byte[] one=new byte[1];return read(one,0,1)<0?-1:Byte.toUnsignedInt(one[0]);}
            @Override public int read(byte[] b,int off,int len)throws IOException{if(remaining==0)return -1;int n=super.read(b,off,(int)Math.min(remaining,len));if(n>0)remaining-=n;return n;}
            @Override public void close()throws IOException{closedBody=true;super.close();}
        };
        var headers=new HashMap<String,String>();headers.put("Content-Length",Long.toString(length));
        headers.put("Content-Range","bytes "+(wrongRange?first+1:first)+"-"+last+"/"+size);
        if(checksumMismatch)headers.put("x-amz-checksum-sha256",Base64.getEncoder().encodeToString(new byte[32]));
        return response(ignoredRange?200:206,headers,body);
    }
    private HttpExecuteResponse listing(SdkHttpRequest request)throws IOException {
        int limit=Integer.parseInt(request.firstMatchingRawQueryParameter("max-keys").orElseThrow());
        String after=request.firstMatchingRawQueryParameter("start-after").orElse("");
        if(!request.firstMatchingRawQueryParameter("prefix").orElseThrow().equals("private-attachment/"))throw new AssertionError("Unbounded inventory");
        if(malformedInventory)return response(200,Map.of("Content-Type","application/xml"),
                ("<ListBucketResult><IsTruncated>true</IsTruncated><Contents><Key>public-media/"+"a".repeat(64)+
                "</Key><LastModified>2026-01-01T00:00:00Z</LastModified></Contents></ListBucketResult>").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        List<Path> files;
        try(var stream=Files.list(directory)){files=stream.filter(p->p.getFileName().toString().matches("[0-9a-f]{64}"))
                .filter(p->("private-attachment/"+p.getFileName()).compareTo(after)>0).sorted().limit(limit+1).toList();}
        var xml=new StringBuilder("<ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><IsTruncated>")
                .append(files.size()>limit).append("</IsTruncated>");
        for(var file:files.stream().limit(limit).toList())xml.append("<Contents><Key>private-attachment/").append(file.getFileName())
                .append("</Key><LastModified>").append(Files.getLastModifiedTime(file).toInstant()).append("</LastModified><Size>")
                .append(Files.size(file)).append("</Size></Contents>");
        return response(200,Map.of("Content-Type","application/xml"),xml.append("</ListBucketResult>").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    private HttpExecuteResponse error(int status,String code){return response(status,Map.of("Content-Type","application/xml"),
            ("<Error><Code>"+code+"</Code><Message>Synthetic storage unavailable</Message></Error>").getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    private HttpExecuteResponse response(int status,Map<String,String> headers,byte[] bytes){return response(status,headers,new ByteArrayInputStream(bytes));}
    private HttpExecuteResponse response(int status,Map<String,String> headers,InputStream input){
        var response=SdkHttpResponse.builder().statusCode(status);headers.forEach(response::putHeader);
        return HttpExecuteResponse.builder().response(response.build()).responseBody(AbortableInputStream.create(input)).build();
    }
    public void close(){ /* Restart leaves external durable synthetic objects intact. */ }
    @Override public String toString(){return "ControlledObjectStorageTransport[REDACTED]";}
}
