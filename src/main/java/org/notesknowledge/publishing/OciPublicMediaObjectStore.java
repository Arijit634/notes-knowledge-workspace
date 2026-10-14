package org.notesknowledge.publishing;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
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
final class OciPublicMediaObjectStore implements PublicMediaObjectStore, AutoCloseable {
    static final long MAX_BYTES = 50L * 1024 * 1024;
    // Preserve byte custody for historically accepted source media.
    private static final long MAX_READ_BYTES = 50L * 1024 * 1024;
    private static final String PREFIX = "public-publication-media/";
    private final S3Client client;
    private final String bucket;
    private final Semaphore admission = new Semaphore(4);

    OciPublicMediaObjectStore(S3Client client, String bucket) { this.client=client; this.bucket=bucket; }

    static S3Client client(PublicMediaStorageProperties properties, AwsCredentialsProvider credentials, SdkHttpClient transport) {
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

    @Override public void write(String reference, InputStream source, long size) {
        require(reference); if(source==null||size<1||size>MAX_BYTES)throw unavailable(); enter();
        java.nio.file.Path directory=null;
        try {
            directory=privateDirectory();var file=directory.resolve("object.bin");
            var md5=java.security.MessageDigest.getInstance("MD5");var sha=java.security.MessageDigest.getInstance("SHA-256");
            // The narrow port accepts streams, not paths/reset promises. Bounded
            // private spooling permits a precomputed wire checksum without heap
            // buffering or remote multipart state. Caller retains stream ownership.
            byte[] buffer=new byte[16*1024];
            try(var output=Files.newOutputStream(file,java.nio.file.StandardOpenOption.CREATE_NEW)) {
                long count=0;
                while(count<size){int n=source.read(buffer,0,(int)Math.min(buffer.length,size-count));
                    if(n<=0)throw unavailable();output.write(buffer,0,n);md5.update(buffer,0,n);sha.update(buffer,0,n);count+=n;}
                if(source.read()!=-1)throw unavailable();
            }finally{java.util.Arrays.fill(buffer,(byte)0);}
            String checksum=Base64.getEncoder().encodeToString(md5.digest());
            var response=client.putObject(PutObjectRequest.builder().bucket(bucket).key(reference).ifNoneMatch("*")
                    .contentLength(size).contentType("application/octet-stream").contentMD5(checksum)
                    .metadata(Map.of("nkw-sha256",java.util.HexFormat.of().formatHex(sha.digest()))).build(),RequestBody.fromFile(file));
            if(response.sdkHttpResponse().statusCode()!=200)throw unavailable();
        }catch(Exception failure){throw unavailable();}
        finally{try{if(directory!=null)removeDirectory(directory);}finally{admission.release();}}
        // An ambiguous response is failure, never permission to overwrite/retry.
        // The owning service's DB-aware cleanup/reconciliation handles orphans.
    }

    @Override public InputStream openRange(String reference,long offset,long length) {
        require(reference);if(offset<0||length<1||offset>MAX_READ_BYTES-length||length>MAX_READ_BYTES)throw unavailable();enter();
        ResponseInputStream<GetObjectResponse> input=null;
        try {
            long end=Math.addExact(offset,length-1);
            input=client.getObject(GetObjectRequest.builder().bucket(bucket).key(reference).range("bytes="+offset+"-"+end).build());
            var response=input.response();var match=java.util.regex.Pattern.compile("bytes ([0-9]{1,8})-([0-9]{1,8})/([0-9]{1,8})")
                    .matcher(response.contentRange()==null?"":response.contentRange());
            if(response.sdkHttpResponse().statusCode()!=206||response.contentLength()==null||response.contentLength()!=length
                    ||!match.matches()||Long.parseLong(match.group(1))!=offset||Long.parseLong(match.group(2))!=end
                    ||Long.parseLong(match.group(3))<=end||Long.parseLong(match.group(3))>MAX_READ_BYTES)throw unavailable();
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

    @Override public void delete(String reference) {
        require(reference);enter();
        try{var response=client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(reference).build());
            if(response.sdkHttpResponse().statusCode()!=204)throw unavailable();}
        catch(NoSuchKeyException missing){ /* Idempotent absence; no other provider error is success. */ }
        catch(RuntimeException failed){throw unavailable();}finally{admission.release();}
    }

    @Override public java.util.List<StoredObject> inventoryBefore(Instant cutoff,String after,int limit) {
        return scanBefore(cutoff,after,limit).objects();
    }
    @Override public InventoryPage scanBefore(Instant cutoff,String after,int limit) {
        outside();if(cutoff==null||limit<1||limit>100)throw unavailable();if(after!=null)require(after);enter();
        try {
            var response=client.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).prefix(PREFIX).startAfter(after).maxKeys(limit).build());
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

    private static java.nio.file.Path privateDirectory() throws IOException {
        var directory=Files.createTempDirectory("nkw-public-copy-");
        try {
            if(Files.getFileStore(directory).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(directory,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
            } else {
                var acl=Files.getFileAttributeView(directory,java.nio.file.attribute.AclFileAttributeView.class);
                if(acl==null)throw new IOException("Private custody unavailable");
                acl.setAcl(java.util.List.of(java.nio.file.attribute.AclEntry.newBuilder()
                    .setType(java.nio.file.attribute.AclEntryType.ALLOW).setPrincipal(Files.getOwner(directory))
                    .setFlags(java.nio.file.attribute.AclEntryFlag.FILE_INHERIT,java.nio.file.attribute.AclEntryFlag.DIRECTORY_INHERIT)
                    .setPermissions(java.util.EnumSet.allOf(java.nio.file.attribute.AclEntryPermission.class)).build()));
            }
            return directory;
        } catch(IOException failure){removeDirectory(directory);throw failure;}
    }
    private static void removeDirectory(java.nio.file.Path directory) {
        // Only this adapter's newly created spool directory; never a caller path.
        try(var entries=Files.walk(directory)){
            for(var entry:entries.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(entry);
        } catch(IOException ignored){/* Unreachable local garbage; no private diagnostics. */}
    }

    private void enter(){outside();if(!admission.tryAcquire())throw unavailable();}
    private static void require(String reference){outside();if(reference==null||!reference.matches("public-publication-media/[0-9a-f]{64}"))throw unavailable();}
    private static void outside(){if(TransactionSynchronizationManager.isActualTransactionActive())throw unavailable();}
    private static ApiFailureException unavailable(){return ApiFailureException.of(ApiFailureException.Kind.SERVICE_UNAVAILABLE);}
    private static void abort(ResponseInputStream<?> input){try{input.abort();}catch(RuntimeException ignored){/* No provider diagnostics escape cleanup. */}}
    @Override public void close(){try{client.close();}catch(RuntimeException failed){throw unavailable();}}
    @Override public String toString(){return "OciPublicMediaObjectStore[REDACTED]";}
}
