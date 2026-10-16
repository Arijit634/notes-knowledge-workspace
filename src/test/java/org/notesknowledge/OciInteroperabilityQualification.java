package org.notesknowledge;

import java.io.*;
import java.nio.file.*;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.http.SdkHttpClient;
import tools.jackson.databind.ObjectMapper;

/** Test-classpath operator command, never an application bean. No ambient AWS chain,
 * provisioning, bucket-wide deletion, request/body logging or automatic replay. */
public final class OciInteroperabilityQualification {
    public record Configuration(String endpoint,String region,String bucket) {
        @Override public String toString(){return "OciQualificationConfiguration[REDACTED]";}
    }
    public interface Store extends AutoCloseable {
        void write(String key,byte[] bytes);
        InputStream read(String key,long offset,long length);
        void delete(String key);
        @Override void close();
    }
    @FunctionalInterface public interface Factory {Store open();}

    public static Map<String,Factory> factories(Configuration c,AwsCredentialsProvider credentials,SdkHttpClient transport) {
        var result=new LinkedHashMap<String,Factory>();
        result.put("private-attachment/",()->org.notesknowledge.notes.OciQualificationAdapter.open(c,credentials,transport));
        result.put("private-avatar/",()->org.notesknowledge.profile.OciQualificationAdapter.open(c,credentials,transport,"private-avatar/"));
        result.put("public-profile-avatar/",()->org.notesknowledge.profile.OciQualificationAdapter.open(c,credentials,transport,"public-profile-avatar/"));
        result.put("public-publication-media/",()->org.notesknowledge.publishing.OciQualificationAdapter.open(c,credentials,transport));
        return Collections.unmodifiableMap(result);
    }
    /** Common real-adapter scenario. The caller owns consent and an exclusive journal.
     * Only this invocation's four random locators can be deleted, including ambiguous PUTs. */
    public static Map<String,Object> exercise(Map<String,Factory> factories) throws IOException {
        return exercise(factories,(prefix,key)->{});
    }
    @FunctionalInterface public interface Reservation {void beforeWrite(String prefix,String key)throws IOException;}
    static Map<String,Object> exercise(Map<String,Factory> factories,Reservation reservation) throws IOException {
        byte[] bytes=new byte[262144];new Random(741).nextBytes(bytes);
        var evidence=new LinkedHashMap<String,Object>();var random=new SecureRandom();
        for(var entry:factories.entrySet()) {
            byte[] locator=new byte[32];random.nextBytes(locator);
            String key=entry.getKey()+HexFormat.of().formatHex(locator);
            long started=System.nanoTime();boolean complete=false,cleanup=false;
            try {
                try(var store=entry.getValue().open()) {
                    // Production endpoint validation precedes even journal hashing;
                    // an invalid URI must not turn embedded credentials into metadata.
                    reservation.beforeWrite(entry.getKey(),key);
                    store.write(key,bytes);
                    boolean rejected=false;
                    try{store.write(key,new byte[]{9});}catch(org.notesknowledge.websupport.ApiFailureException expected){rejected=true;}
                    require(rejected);
                    try(var input=store.read(key,0,bytes.length)){require(input!=null&&Arrays.equals(input.readAllBytes(),bytes));}
                    // Both Profile adapters intentionally expose full-object delivery only.
                    if(entry.getKey().equals("private-attachment/")||entry.getKey().equals("public-publication-media/"))
                        try(var input=store.read(key,31,1000)){require(input!=null&&Arrays.equals(input.readAllBytes(),Arrays.copyOfRange(bytes,31,1031)));}
                }
                try(var restarted=entry.getValue().open();var input=restarted.read(key,0,bytes.length)) {
                    require(input!=null&&Arrays.equals(input.readAllBytes(),bytes));
                }
                complete=true;
            } finally {
                // A lost response may still have created an object. Never retry its PUT.
                try(var store=entry.getValue().open()) {
                    store.delete(key);store.delete(key);
                    cleanup=true;
                } finally {
                    evidence.put(entry.getKey(),Map.of("completed",complete,"deleteAcknowledged",cleanup,
                        "physicalAbsenceVerified",false,"elapsedMillis",(System.nanoTime()-started)/1000000,"syntheticBytes",bytes.length));
                }
            }
        }
        return Collections.unmodifiableMap(evidence);
    }
    private static void require(boolean valid){if(!valid)throw new IllegalStateException("Storage qualification invariant failed");}
    static void requireConsent(Map<String,String> environment) {
        for(String key:List.of("NKW_OCI_QUALIFICATION_APPROVED","NKW_OCI_DEDICATED_TEST_BUCKET_CONFIRMED","NKW_OCI_NO_SPEND_CONFIRMED"))
            if(!"true".equals(environment.get(key)))throw new IllegalStateException("Live OCI qualification not authorized");
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=2||!Set.of("run","cleanup").contains(args[0]))throw new IllegalArgumentException("run|cleanup and local evidence directory required");
        requireConsent(System.getenv());
        Path directory=Path.of(args[1]).toAbsolutePath().normalize();boolean cleanupOnly=args[0].equals("cleanup");
        if(!cleanupOnly)Files.createDirectory(directory); // Never replay an interrupted PUT.
        var json=new ObjectMapper();
        if(!cleanupOnly)forceCreate(directory.resolve("reservation.json"),json.writeValueAsBytes(Map.of(
            "startedUtc",Instant.now().toString(),"maxObjects",4,"maxAttemptedUploadBytes",1048580,"maxRequests",26,
            "automaticReplay",false,"mode","live-oci")));
        boolean passed=false;
        try {
            var c=new Configuration(required("NKW_OCI_ENDPOINT"),required("NKW_OCI_REGION"),required("NKW_OCI_TEST_BUCKET"));
            var credentials=StaticCredentialsProvider.create(AwsBasicCredentials.create(required("NKW_OCI_ACCESS_KEY"),required("NKW_OCI_SECRET_KEY")));
            var stores=factories(c,credentials,null);
            if(cleanupOnly){cleanup(directory,c,stores);forceCreate(directory.resolve("cleanup-"+System.nanoTime()+".json"),json.writeValueAsBytes(Map.of("mode","live-oci","deleteAcknowledged",true,"physicalAbsenceVerified",false)));
                System.out.println("Exact reserved synthetic locators received delete acknowledgements");return;}
            var evidence=exercise(stores,(prefix,key)->reserve(directory,c,prefix,key));
            Files.writeString(directory.resolve("result.json"),json.writeValueAsString(Map.of("mode","live-oci","adapters",evidence,
                "notEstablished",List.of("IAM-policy-denial","credential-rotation","process-crash-orphan-recovery","database-reference-reconciliation"))),StandardOpenOption.CREATE_NEW);
            passed=true;
        } catch(Exception failure) {
            // No external exception, endpoint, bucket, key, signature or credential in diagnostics.
            Files.writeString(directory.resolve(cleanupOnly?"cleanup-failed-"+System.nanoTime()+".json":"result.json"),"{\"mode\":\"live-oci\",\"passed\":false,\"code\":\"storage_qualification_failed\"}",StandardOpenOption.CREATE_NEW);
        }
        System.out.println(passed?"OCI qualification scenario passed; inspect bounded result.json":"OCI qualification failed; no automatic replay authorized");
        if(!passed)System.exit(1);
    }
    record ReservedObject(String configuration,String prefix,String key) {
        @Override public String toString(){return "ReservedSyntheticObject[REDACTED]";}
    }
    static void reserve(Path directory,Configuration c,String prefix,String key)throws IOException {
        forceCreate(directory.resolve(prefix.replace("/","")+"-object.json"),new ObjectMapper().writeValueAsBytes(new ReservedObject(configurationId(c),prefix,key)));
    }
    static void cleanup(Path directory,Configuration c,Map<String,Factory> factories)throws IOException {
        var json=new ObjectMapper();
        // Fixed four journal names, never bucket listing or caller-supplied deletion keys.
        for(var entry:factories.entrySet()) {
            Path path=directory.resolve(entry.getKey().replace("/","")+"-object.json");
            if(!Files.exists(path))continue;
            require(!Files.isSymbolicLink(path)&&Files.size(path)<1024);
            var reserved=json.readValue(Files.readAllBytes(path),ReservedObject.class);
            try(var store=entry.getValue().open()){
                require(reserved.configuration().equals(configurationId(c))&&reserved.prefix().equals(entry.getKey())
                    &&reserved.key().matches(java.util.regex.Pattern.quote(entry.getKey())+"[0-9a-f]{64}"));
                store.delete(reserved.key());
            }
        }
    }
    private static String configurationId(Configuration c){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
        .digest((c.endpoint()+"\n"+c.region()+"\n"+c.bucket()).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException("Configuration identity unavailable");}}
    private static void forceCreate(Path path,byte[] bytes)throws IOException {
        try(var channel=java.nio.channels.FileChannel.open(path,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){
            var buffer=java.nio.ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);
        }
    }
    private static String required(String name){String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalStateException("Qualification configuration unavailable");return value;}
}
