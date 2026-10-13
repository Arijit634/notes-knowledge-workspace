package org.notesknowledge.profile;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.Semaphore;
import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.StandardRetryStrategy;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.*;

/** OCI's S3 compatibility transport. Locators grant no application authority.
 * Single-part, create-only, exact-length writes; no mutation retry or promotion.
 * The production client never consults an ambient AWS credential/endpoint chain. */
final class OciAvatarStorage implements AutoCloseable {
    static final long MAX_BYTES = 5L * 1024 * 1024;
    // Canonical avatars are bounded by Profile's established validation limit.
    private static final long MAX_READ_BYTES = MAX_BYTES;
    private final String prefix;
    private final S3Client client;
    private final String bucket;
    private final Semaphore admission = new Semaphore(4);

    OciAvatarStorage(S3Client client, String bucket, String prefix) {
        if(!java.util.Set.of("private-avatar/","public-profile-avatar/").contains(prefix))throw unavailable();
        this.client=client; this.bucket=bucket; this.prefix=prefix;
    }

    static S3Client client(AvatarStorageProperties properties, AwsCredentialsProvider credentials, SdkHttpClient transport) {
        properties.requireConfigured();
        var builder=S3Client.builder().endpointOverride(java.net.URI.create(properties.endpoint()))
                .region(Region.of(properties.region())).credentialsProvider(credentials)
                .crossRegionAccessEnabled(false)
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).chunkedEncodingEnabled(false)
                        .useArnRegionEnabled(false).multiRegionEnabled(false).build())
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .overrideConfiguration(c->c.apiCallTimeout(Duration.ofSeconds(10)).apiCallAttemptTimeout(Duration.ofSeconds(8))
                        .retryStrategy(StandardRetryStrategy.builder().maxAttempts(1).build()));
        if(transport==null)builder.httpClientBuilder(UrlConnectionHttpClient.builder()
                .connectionTimeout(Duration.ofSeconds(2)).socketTimeout(Duration.ofSeconds(5)));
        else builder.httpClient(transport); // Test-only controlled transport, never selected by configuration.
        return builder.build();
    }

    public void write(String reference, byte[] bytes) {
        require(reference);if(bytes==null||bytes.length<1||bytes.length>MAX_BYTES)throw unavailable();enter();
        byte[] copy=bytes.clone();
        try {
            var md5=java.security.MessageDigest.getInstance("MD5").digest(copy);
            var sha=java.security.MessageDigest.getInstance("SHA-256").digest(copy);
            var response=client.putObject(PutObjectRequest.builder().bucket(bucket).key(reference).ifNoneMatch("*")
                .contentLength((long)copy.length).contentType("application/octet-stream")
                .contentMD5(Base64.getEncoder().encodeToString(md5))
                .metadata(Map.of("nkw-sha256",java.util.HexFormat.of().formatHex(sha))).build(),RequestBody.fromBytes(copy));
            if(response.sdkHttpResponse().statusCode()!=200)throw unavailable();
        } catch(Exception failure){throw unavailable();}
        finally{java.util.Arrays.fill(copy,(byte)0);admission.release();}
    }

    public InputStream open(String reference,long length) {
        require(reference);if(length<1||length>MAX_READ_BYTES)throw unavailable();enter();
        long offset=0;
        ResponseInputStream<GetObjectResponse> input=null;
        try {
            long end=Math.addExact(offset,length-1);
            input=client.getObject(GetObjectRequest.builder().bucket(bucket).key(reference).range("bytes="+offset+"-"+end).build());
            var response=input.response();var match=java.util.regex.Pattern.compile("bytes ([0-9]{1,8})-([0-9]{1,8})/([0-9]{1,8})")
                    .matcher(response.contentRange()==null?"":response.contentRange());
            if(response.sdkHttpResponse().statusCode()!=206||response.contentLength()==null||response.contentLength()!=length
                    ||!match.matches()||Long.parseLong(match.group(1))!=offset||Long.parseLong(match.group(2))!=end
                    ||Long.parseLong(match.group(3))<=end||Long.parseLong(match.group(3))!=length)throw unavailable();
            var accepted=input;
            return new FilterInputStream(accepted) {
                long remaining=length;boolean closed;final long deadline=System.nanoTime()+Duration.ofSeconds(30).toNanos();
                @Override public int read()throws IOException {byte[] one=new byte[1];int n=read(one,0,1);return n<0?-1:Byte.toUnsignedInt(one[0]);}
                @Override public int read(byte[] bytes,int start,int count)throws IOException {
                    java.util.Objects.checkFromIndexSize(start,count,bytes.length);if(count==0)return 0;
                    if(closed||System.nanoTime()-deadline>=0)throw new IOException("Stored bytes unavailable");if(remaining==0)return -1;
                    try{int n=accepted.read(bytes,start,(int)Math.min(count,remaining));
                        if(n<=0||System.nanoTime()-deadline>=0)throw new IOException();remaining-=n;return n;}
                    catch(IOException|RuntimeException failed){throw new IOException("Stored bytes unavailable");}
                }
                @Override public long skip(long count)throws IOException {
                    byte[] buffer=new byte[4096];long skipped=0;
                    while(skipped<count){int n=read(buffer,0,(int)Math.min(buffer.length,count-skipped));if(n<0)break;skipped+=n;}return skipped;
                }
                @Override public int available(){return 0;}
                @Override public boolean markSupported(){return false;}
                @Override public void mark(int limit){ /* Rewinding authorized bytes is unsupported. */ }
                @Override public void reset()throws IOException{throw new IOException("Stored bytes unavailable");}
                @Override public void close(){if(!closed){closed=true;try{abort(accepted);}finally{admission.release();}}}
            };
        }catch(NoSuchKeyException missing){admission.release();return null;}
        catch(Exception failed){try{if(input!=null)abort(input);}finally{admission.release();}throw unavailable();}
    }

    public void delete(String reference) {
        require(reference);enter();
        try{var response=client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(reference).build());
            if(response.sdkHttpResponse().statusCode()!=204)throw unavailable();}
        catch(NoSuchKeyException missing){ /* Idempotent absence; no other provider error is success. */ }
        catch(RuntimeException failed){throw unavailable();}finally{admission.release();}
    }

    public java.util.List<StoredObject> inventoryBefore(Instant cutoff,String after,int limit) {
        return scanBefore(cutoff,after,limit).objects();
    }
    public InventoryPage scanBefore(Instant cutoff,String after,int limit) {
        outside();if(cutoff==null||limit<1||limit>100)throw unavailable();if(after!=null)require(after);enter();
        try {
            var response=client.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).startAfter(after).maxKeys(limit).build());
            if(response.sdkHttpResponse().statusCode()!=200||response.contents().size()>limit)throw unavailable();
            var objects=new ArrayList<StoredObject>();String last=after;
            for(var item:response.contents()) {
                require(item.key());if(last!=null&&item.key().compareTo(last)<=0||item.lastModified()==null)throw unavailable();
                last=item.key();if(item.lastModified().isBefore(cutoff))objects.add(new StoredObject(item.key(),item.lastModified()));
            }
            if(Boolean.TRUE.equals(response.isTruncated())&&(last==null||last.equals(after)))throw unavailable();
            return new InventoryPage(objects,Boolean.TRUE.equals(response.isTruncated())?last:null);
        }catch(RuntimeException failed){throw unavailable();}finally{admission.release();}
    }

    record StoredObject(String reference,Instant createdAt) {
        @Override public String toString(){return "StoredAvatar[REDACTED]";}
    }
    record InventoryPage(java.util.List<StoredObject> objects,String continueAfter) {
        InventoryPage{objects=java.util.List.copyOf(objects);}
        @Override public String toString(){return "AvatarInventory[REDACTED]";}
    }
    private void enter(){outside();if(!admission.tryAcquire())throw unavailable();}
    private void require(String reference){outside();if(reference==null||!reference.matches(java.util.regex.Pattern.quote(prefix)+"[0-9a-f]{64}"))throw unavailable();}
    private static void outside(){if(TransactionSynchronizationManager.isActualTransactionActive())throw unavailable();}
    private static ApiFailureException unavailable(){return ApiFailureException.of(ApiFailureException.Kind.SERVICE_UNAVAILABLE);}
    private static void abort(ResponseInputStream<?> input){try{input.abort();}catch(RuntimeException ignored){/* No provider diagnostics escape cleanup. */}}
    @Override public void close(){try{client.close();}catch(RuntimeException failed){throw unavailable();}}
    @Override public String toString(){return "OciAvatarStorage[REDACTED]";}
}
