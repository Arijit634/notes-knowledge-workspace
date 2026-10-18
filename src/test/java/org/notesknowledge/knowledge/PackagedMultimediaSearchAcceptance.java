package org.notesknowledge.knowledge;

import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.notesknowledge.Application;
import org.notesknowledge.identity.ControlledRegistrationDelivery;
import org.notesknowledge.knowledge.spi.PrivateDerivationSource;
import org.notesknowledge.notes.ConfiguredMediaStorageFixtures;
import org.notesknowledge.notes.MeaningfulMediaFixtures;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Explicit, offline acceptance command loaded BESIDE the production JAR by
 * PropertiesLauncher. Only external SDK/mail/rate transports are controlled.
 * No controller, gate, scheduler, persistence, source or retrieval replacement.
 * Not a live-Gemini quality score and never included in the application JAR. */
public final class PackagedMultimediaSearchAcceptance {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final String POLICY="a".repeat(64), PASSWORD="Synthetic-Media-Password-731!";
    private static final String KEY=Base64.getEncoder().encodeToString(new byte[32]);
    private static ControlledGemini provider;
    private static String phase="setup";
    private static final List<String> LOG_CANARIES=new java.util.concurrent.CopyOnWriteArrayList<>(List.of(PASSWORD,
        "synthetic-media-owner@example.test","synthetic-media-other@example.test","Fictional context only.","Aster project deadline is 18 November."));
    private static final List<String> QUERIES=List.of(
        "Find the image with a blue square and red circle", "What is written in my scanned PDF?",
        "What does the archive PDF say about its door?", "Which recording mentions the observatory?",
        "Find the recording with high then low tones", "What is visible around the 25 second mark in my video?",
        "Find Aster observations", "Find everything about Aster", "Show every URL I saved", "What is written on page 7?");
    private record Uploaded(String fixture,UUID note,UUID attachment,byte[] bytes,String type) {
        @Override public String toString(){return "SyntheticUpload[REDACTED]";}
    }
    private final Map<String,String> settings=new LinkedHashMap<>();
    private final List<Uploaded> uploads=new ArrayList<>();
    private final List<Map<String,Object>> results=new ArrayList<>();
    private UUID owner;
    private UUID policy;
    private UUID textNote;
    private Browser browser,other;
    private ConfigurableApplicationContext app;
    private static javax.net.ssl.SSLContext browserTls;

    public static void main(String[] args)throws Exception {
        require(args.length==3,"explicit_native_roots_and_report_directory_required");
        for(var type:List.of(Application.class,KnowledgeQueryController.class,KnowledgeQueryEngine.class,DerivationExecutor.class,
                DerivationSchedulingConfiguration.class,GoogleDerivationAdapters.class,ExactPrivateVectorSearch.class))
            require(type.getProtectionDomain().getCodeSource().getLocation().toString().contains(".jar"),"production_jar_required");
        var report=Path.of(args[2]).toAbsolutePath();Files.createDirectory(report);
        var console=System.out;var errors=System.err;var logs=new BoundedLogs();boolean passed=false;String failureCode="acceptance_failed",failureType="none";
        var failureClasses=new ArrayList<String>();
        var failureFrames=new ArrayList<String>();var nativeLibraries=new ArrayList<String>();
        System.setOut(new java.io.PrintStream(logs,true,StandardCharsets.UTF_8));System.setErr(System.out);
        try(var database=new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18-trixie")
                .withDatabaseName("multimedia_acceptance").withUsername("synthetic_migrator").withPassword("synthetic-migration-password")) {
            database.start();
            try(var controlled=new ControlledGemini()) {
                provider=controlled;var journey=new PackagedMultimediaSearchAcceptance();
                try {journey.execute(database,Path.of(args[0]),Path.of(args[1]),report);}
                finally {if(journey.app!=null)journey.app.close();provider=null;}
            }
            require(!logs.truncated,"log_inspection_bound");String captured=logs.toString(StandardCharsets.UTF_8);
            require(LOG_CANARIES.stream().noneMatch(value->!value.isEmpty()&&captured.contains(value)),"log_privacy_failure");passed=true;
        }catch(Throwable failure){if(failure instanceof AssertionError&&failure.getMessage()!=null&&failure.getMessage().matches("[A-Za-z_0-9-]+"))failureCode=failure.getMessage();failureType=failure.getClass().getSimpleName();
            for(Throwable cause=failure;cause!=null&&failureClasses.size()<10;cause=cause.getCause())failureClasses.add(cause.getClass().getName());
            for(var frame:Arrays.stream(failure.getStackTrace()).limit(8).toList())failureFrames.add(frame.getClassName()+"."+frame.getMethodName());
            if(failure instanceof UnsatisfiedLinkError&&failure.getMessage()!=null){var match=java.util.regex.Pattern.compile("[A-Za-z0-9_]+\\.(?:so|dll|dylib)(?:\\.[0-9]+)*").matcher(failure.getMessage());while(match.find()&&nativeLibraries.size()<8)nativeLibraries.add(match.group());}}
        finally {System.setOut(console);System.setErr(errors);logs.reset();LOG_CANARIES.clear();}
        console.println(JSON.writeValueAsString(Map.of("packagedMultimediaSearch",true,"result",passed?"passed":failureCode,"phase",phase,"failureType",failureType,"failureClasses",failureClasses,"failureFrames",failureFrames,"nativeLibraries",nativeLibraries,"logPrivacyChecked",passed,"providerEvidence","controlled-real-sdk")));
        if(!passed)System.exit(1);
    }
    private void execute(PostgreSQLContainer database,Path pdfRoot,Path videoRoot,Path report)throws Exception {
        Path local=Files.createTempDirectory("multimedia-http-");
        try {
            localTls(local);
            settings.put("spring.datasource.url",database.getJdbcUrl());settings.put("spring.datasource.username",database.getUsername());settings.put("spring.datasource.password",database.getPassword());
            settings.put("server.address","127.0.0.1");settings.put("server.port","0");settings.put("spring.main.banner-mode","off");
            settings.put("identity.rate.key-base64",KEY);settings.put("identity.delivery.key-base64",KEY);settings.put("identity.delivery.public-origin","https://example.test");
            settings.put("notes.cursor.active.version","test1");settings.put("notes.cursor.active.key-base64",KEY);
            settings.put("knowledge.operations.handle.key-base64",KEY);settings.put("knowledge.operations.envelope.key-base64",KEY);
            settings.put("synthetic.media-store",local.resolve("objects").toString());settings.put("knowledge.processing-policy.code","packaged-synthetic");
            settings.put("knowledge.derivation.enabled","false");settings.put("knowledge.derivation.scheduling-enabled","false");
            settings.put("knowledge.operations.scheduling-enabled","false");settings.put("identity.delivery.polling-enabled","false");
            settings.put("identity.delivery.smtp-enabled","false");settings.put("identity.oidc.google.enabled","false");
            selectRuntime("pdf",pdfRoot,local);selectRuntime("video",videoRoot,local);
            phase="registration";start();browser=register("synthetic-media-owner@example.test");other=register("synthetic-media-other@example.test");
            var jdbc=app.getBean(JdbcTemplate.class);
            owner=jdbc.queryForObject("select user_id from identity.account where canonical_email='synthetic-media-owner@example.test'",UUID.class);
            policy=jdbc.queryForObject("insert into knowledge.processing_policy(policy_code,policy_version,policy_fingerprint,disclosure_revision,effective_at) values('packaged-synthetic',1,?,'synthetic-1',clock_timestamp()-interval '1 minute') returning processing_policy_id",UUID.class,POLICY);
            acknowledge(browser);acknowledge(other);
            phase="upload";textNote=note("Aster text", "Aster project deadline is 18 November. https://example.test/aster");
            for(String kind:List.of("image","pdf","scan","mixed","audio","tones","video-short","video-long","seven","jpeg"))upload(kind);
            phase="exact-synthetic-approvals";var approvals=new ArrayList<String>();var children=new ArrayList<String>();
            String pdfTransform,videoTransform;
            try(var child=ConfiguredMediaStorageFixtures.page(app,uploads.get(1).bytes(),1)){pdfTransform=child.transformFingerprint();}
            var longVideo=uploaded("video-long");var timeline=ConfiguredMediaStorageFixtures.plan(app,longVideo.bytes());
            try(var child=ConfiguredMediaStorageFixtures.clip(app,longVideo.bytes(),timeline,0,timeline.next(0))){videoTransform=child.transformFingerprint();}
            configure(pdfTransform,videoTransform);
            var config=configuration(pdfTransform,videoTransform);
            var sourcePort=app.getBean(PrivateDerivationSource.class);
            var tx=new TransactionTemplate(app.getBean(PlatformTransactionManager.class));
            PrivateDerivationSource.Cursor cursor=null;
            do {
                var after=cursor;var page=tx.execute(s->sourcePort.inventory(after,25));
                for(var metadata:page.sources()) {
                    approvals.add(ProviderDispatchPolicy.fingerprint(metadata.expected()));
                    if(metadata.expected().attachmentId()==null)continue;
                    var uploaded=uploads.stream().filter(u->u.attachment().equals(metadata.expected().attachmentId())).findFirst().orElseThrow();
                    var lineage=EmbeddingLineage.create(config,new ProcessingPolicyService.AcknowledgedProcessingPolicy(policy,1,POLICY),metadata.modality());
                    if(metadata.modality().equals("pdf"))for(int p=1;p<=metadata.pages();p++)try(var child=ConfiguredMediaStorageFixtures.page(app,uploaded.bytes(),p)){
                        byte[] bytes=child.bytes();
                        children.add(identity(config,metadata,lineage,uploaded,bytes,child.profile(),child.implementationVersion(),child.artifactSha256(),child.configurationSha256(),"image/png",new TransformedMediaIdentity.Scope("pdf_pages",p,p,0,0,0)).approvalFingerprint());
                        provider.media(bytes,List.of(pdfSegment(uploaded.fixture(),p)));Arrays.fill(bytes,(byte)0);
                    }
                    if(metadata.modality().equals("video")){
                        var plan=ConfiguredMediaStorageFixtures.plan(app,uploaded.bytes());
                        for(int first=0;first<plan.durations().size();){int last=plan.next(first);
                            try(var child=ConfiguredMediaStorageFixtures.clip(app,uploaded.bytes(),plan,first,last)){
                                byte[] bytes=child.bytes();children.add(identity(config,metadata,lineage,uploaded,bytes,child.profile(),child.implementationVersion(),child.artifactSha256(),child.configurationSha256(),"video/mp4",new TransformedMediaIdentity.Scope("video_samples",first,last,plan.timescale(),plan.tick(first),plan.tick(last))).approvalFingerprint());
                                double duration=(double)(plan.tick(last)-plan.tick(first))/plan.timescale();
                                provider.media(bytes,List.of(segment("Aster scene: a blue square left of a red circle","video_scene",null,0.,duration)));
                                Arrays.fill(bytes,(byte)0);
                            }first=last;
                        }
                    }
                }cursor=page.next();
            }while(cursor!=null);
            settings.put("knowledge.derivation.approved-source-fingerprints",String.join(",",approvals));
            settings.put("knowledge.derivation.approved-child-fingerprints",String.join(",",children));
            settings.put("knowledge.derivation.approved-query-fingerprints",String.join(",",QUERIES.stream().map(ProviderDispatchPolicy::queryFingerprint).toList()));
            // Ordinary schedulers, not executor invocation or direct work-row insertion.
            app.close();app=null;settings.put("knowledge.derivation.scheduling-enabled","true");settings.put("knowledge.derivation.poll-delay-ms","200");
            settings.put("knowledge.operations.scheduling-enabled","true");settings.put("knowledge.operations.poll-delay-ms","200");
            phase="scheduled-processing";start();browser.origin=origin();other.origin=origin();
            for(var uploaded:uploads)awaitReady(uploaded);
            phase="search";for(String query:QUERIES){JsonNode result=query(browser,query);verify(query,result);results.add(Map.of("query",query,"response",result));}
            var cited=new HashSet<String>();for(var result:results)for(var citation:((JsonNode)result.get("response")).get("citations"))if(!citation.get("attachmentId").isNull())cited.add(citation.get("attachmentId").asText());
            var matrix=new ArrayList<Map<String,Object>>();
            for(var uploaded:uploads){
                boolean indexed=app.getBean(JdbcTemplate.class).queryForObject("select count(*) from knowledge.private_derived_representation r join knowledge.private_derived_segment s on s.parent_id=r.derived_representation_id and s.owner_user_id=r.owner_user_id where r.owner_user_id=? and r.source_attachment_id=? and r.state='ready'",Integer.class,owner,uploaded.attachment())>0;
                boolean retrieved=cited.contains(uploaded.attachment().toString());require(indexed&&retrieved,"modality_not_demonstrated_"+uploaded.fixture());
                matrix.add(Map.of("media",uploaded.fixture(),"uploaded",true,"processed",true,"indexed",indexed,"retrieved",retrieved,"answerCited",retrieved,"result","pass"));
            }
            phase="negative-scenarios";providerFailureAndRecovery();processingChangeRace();isolationAndRevocation();
            Files.writeString(report.resolve("search-demonstration.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(Map.ofEntries(
                Map.entry("providerEvidence","controlled-real-sdk-not-live-gemini"),Map.entry("packagedApplication",true),Map.entry("scheduledProcessing",true),
                Map.entry("matrix",matrix),Map.entry("observationOrder","Positive observations precede deliberate negative revocation scenarios; reports are not retained authorization."),
                Map.entry("uploads",uploads.stream().map(u->Map.of("fixture",u.fixture(),"noteId",u.note(),"attachmentId",u.attachment())).toList()),
                Map.entry("queries",results),Map.entry("twoUserIsolation",true),Map.entry("aiOffRevocation",true),Map.entry("attachmentDeletion",true),Map.entry("sdkRequests",provider.requests.get()),
                Map.entry("transientMediaFailuresRecovered",provider.transientFailures.get()),Map.entry("invalidProviderOutputDenied",true),Map.entry("providerOutageAndRecovery",true),Map.entry("sourceChangedDuringProcessing",true))));
        }finally{
            if(app!=null){app.close();app=null;}
            // Only this command's fresh temporary directory, never repository/user media.
            try(var files=Files.walk(local)){for(var path:files.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}
        }
    }
    private void selectRuntime(String kind,Path root,Path local)throws Exception {
        String prefix="notes.media-runtime."+kind+".";settings.put(prefix+"enabled","true");settings.put(prefix+"profile","linux-confined-v1");settings.put(prefix+"root",root.toString());
        var journal=Files.createDirectory(local.resolve(kind+"-journal"));Files.setPosixFilePermissions(journal,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        settings.put(prefix+"recovery-directory",journal.toString());settings.put(prefix+"artifact-identity",kind.equals("pdf")?ConfiguredMediaStorageFixtures.pdfIdentity(root):ConfiguredMediaStorageFixtures.videoIdentity(root));
        settings.put(prefix+"version",kind.equals("pdf")?"native-pdf-v1":"ffmpeg-9.0.2-profile-v1");
        if(kind.equals("video")){var scratch=Files.createDirectory(local.resolve("video-scratch"));Files.setPosixFilePermissions(scratch,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));settings.put(prefix+"scratch-directory",scratch.toString());}
    }
    private void configure(String pdf,String video){
        var values=Map.ofEntries(Map.entry("enabled","true"),Map.entry("provider","gemini"),Map.entry("embedding-model","gemini-embedding-2"),Map.entry("media-model","gemini-3.8-flash"),Map.entry("model-revision","v1"),Map.entry("region","global"),Map.entry("tier","unpaid"),Map.entry("configuration-id","controlled-v1"),Map.entry("adapter-version","controlled-v1"),Map.entry("dimension","8"),Map.entry("operator","cosine"),Map.entry("normalization","none"),Map.entry("approved-policy-fingerprint",POLICY),Map.entry("pdf-page-children","true"),Map.entry("video-children","true"),Map.entry("pdf-transform-fingerprint",pdf),Map.entry("video-transform-fingerprint",video),Map.entry("dispatch-policy","unpaid-synthetic-demo"));
        values.forEach((k,v)->settings.put("knowledge.derivation."+k,v));
    }
    private static AiDerivationProperties configuration(String pdf,String video){return new AiDerivationProperties(true,true,"gemini","gemini-embedding-2","gemini-3.8-flash","v1","global","unpaid","controlled-v1","controlled-v1",8,"cosine","none",POLICY,true,pdf,true,video);}
    private void start(){app=new SpringApplicationBuilder(Application.class,ConfiguredMediaStorageFixtures.class,ControlledRegistrationDelivery.class,ExternalServices.class).run(settings.entrySet().stream().map(e->"--"+e.getKey()+"="+e.getValue()).toArray(String[]::new));}
    private String origin(){return "https://localhost:"+app.getEnvironment().getProperty("local.server.port");}
    private void localTls(Path local)throws Exception {
        // Ephemeral test-only TLS. No host trust store or TLS verification bypass.
        Path keyStore=local.resolve("localhost.p12");String password="synthetic-keystore-password";LOG_CANARIES.add(password);
        var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","keytool").toString(),"-genkeypair","-alias","localhost","-keyalg","EC","-groupname","secp256r1","-dname","CN=localhost","-ext","SAN=dns:localhost,ip:127.0.0.1","-validity","2","-storetype","PKCS12","-keystore",keyStore.toString(),"-storepass",password,"-noprompt")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        if(!process.waitFor(20,java.util.concurrent.TimeUnit.SECONDS)){process.destroyForcibly();throw new AssertionError("test_tls_deadline");}
        require(process.exitValue()==0,"test_tls_setup");var store=java.security.KeyStore.getInstance("PKCS12");
        try(var input=Files.newInputStream(keyStore)){store.load(input,password.toCharArray());}
        var trusts=javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());trusts.init(store);
        browserTls=javax.net.ssl.SSLContext.getInstance("TLS");browserTls.init(null,trusts.getTrustManagers(),null);
        settings.put("server.ssl.enabled","true");settings.put("server.ssl.key-store",keyStore.toUri().toString());settings.put("server.ssl.key-store-type","PKCS12");settings.put("server.ssl.key-store-password",password);
    }
    private Browser register(String email)throws Exception{
        var b=new Browser(origin());b.csrf();b.json("POST","/api/auth/registrations",Map.of("email",email,"password",PASSWORD),202);
        String token=ControlledRegistrationDelivery.verificationToken(app);
        LOG_CANARIES.add(token);
        b.json("POST","/api/auth/email-verification/confirmations",Map.of("token",token),204);
        String old=b.cookie;b.json("POST","/api/auth/login/password",Map.of("email",email,"password",PASSWORD),200);require(!old.equals(b.cookie),"session_rotation");b.csrf();return b;
    }
    private void acknowledge(Browser b)throws Exception{
        JsonNode p=b.get("/api/ai/processing-policy",200);var body=new LinkedHashMap<String,Object>();
        for(String field:List.of("processingPolicyId","policyCode","policyVersion","policyFingerprint","disclosureRevision"))body.put(field,field.equals("policyVersion")?p.get(field).asLong():p.get(field).asText());
        body.put("confirmAcknowledgement",true);
        b.json("POST","/api/ai/processing-policy/acknowledgements",body,204);
    }
    private UUID note(String title,String text)throws Exception{return UUID.fromString(browser.json("POST","/api/notes",Map.of("title",title,"markdown",text,"aiEnabled",true),201).get("id").asText());}
    private void upload(String kind)throws Exception{
        UUID note=note("Fictional media "+uploads.size(),"");
        var file=kind.startsWith("video-")?new org.springframework.mock.web.MockMultipartFile("file","fixture.mp4","video/mp4",MeaningfulMediaFixtures.videoSamples(kind.equals("video-long")?20:1,kind.equals("video-long")?2000:2000)):MeaningfulMediaFixtures.media(kind);
        var r=uploadBytes(note,file.getBytes(),file.getContentType(),file.getOriginalFilename());
        UUID attachment=UUID.fromString(JSON.readTree(r.body()).get("id").asText());
        byte[] stored=browser.send("GET","/api/notes/"+note+"/attachments/"+attachment+"/content",null,Map.of(),200).body();
        uploads.add(new Uploaded(kind,note,attachment,stored,file.getContentType()));
        if(kind.equals("image")||kind.equals("jpeg")){provider.media(stored,List.of(segment("Aster image: a blue square left of a red circle. The text ASTER is below.","whole_image",null,null,null)));if(kind.equals("image"))provider.failOnce.add(TransformedMediaIdentity.digest(stored));}
        if(kind.equals("audio"))provider.media(stored,List.of(segment("The fictional observatory is on planet Aster. Its dome is silver.","transcript",null,0.,(double)(stored.length-44)/java.nio.ByteBuffer.wrap(stored).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(28))));
        if(kind.equals("tones"))provider.media(stored,List.of(new DerivedSegment("A high tone followed by a low tone; no intelligible speech.","transcript","Acoustic description",null,null,null,0.,4.,null,null,null,null)));
    }
    private HttpResponse<byte[]> uploadBytes(UUID note,byte[] bytes,String type,String name)throws Exception {
        String boundary="SyntheticMediaBoundary731";var body=new ByteArrayOutputStream();
        body.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\""+name+"\"\r\nContent-Type: "+type+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));body.write(bytes);body.write(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.US_ASCII));
        return browser.send("POST","/api/notes/"+note+"/attachments",body.toByteArray(),Map.of("Content-Type","multipart/form-data; boundary="+boundary),201);
    }
    private void awaitReady(Uploaded u)throws Exception{
        long until=System.nanoTime()+Duration.ofMinutes(4).toNanos();
        while(System.nanoTime()<until){var status=browser.get("/api/notes/"+u.note()+"/ai-processing",200);var state=status.get("attachments").get(0).get("status").asText();if(state.equals("ready"))return;require(!state.equals("failed"),"processing_failed_"+u.fixture());Thread.sleep(200);}
        throw new AssertionError("processing_deadline_"+u.fixture());
    }
    private JsonNode query(Browser b,String query)throws Exception{
        var response=b.send("POST","/api/knowledge/query",JSON.writeValueAsBytes(Map.of("query",query)),Map.of("Content-Type","application/json"),null);
        if(response.statusCode()==200)return JSON.readTree(response.body());
        require(response.statusCode()==202,"query_acceptance");String location=response.headers().firstValue("Location").orElseThrow();require(location.startsWith("/api/knowledge/operations/"),"operation_location");
        long until=System.nanoTime()+Duration.ofMinutes(3).toNanos();while(System.nanoTime()<until){var view=b.get(location,200);String state=view.get("status").asText();if(state.equals("completed"))return view.get("result");require(!Set.of("failed","obsolete","cancelled").contains(state),"operation_"+state);Thread.sleep(200);}throw new AssertionError("operation_deadline");
    }
    private void verify(String query,JsonNode result){
        require(result.get("degraded").isNull(),"query_degraded");
        if(query.contains("URL")){require(result.get("coverage").get("completed").asBoolean(),"deterministic_coverage");require(result.get("deterministicResults").size()==1,"deterministic_occurrence");return;}
        require(!result.get("aiAnswer").isNull(),"missing_grounded_answer");require(!result.get("citations").isEmpty(),"missing_citations");
        var targets=new HashSet<UUID>();for(var citation:result.get("citations")){var media=citation.get("attachmentId");if(!media.isNull()){
            UUID id=UUID.fromString(media.asText());var original=uploads.stream().filter(u->u.attachment().equals(id)).findFirst().orElseThrow();require(citation.get("noteId").asText().equals(original.note().toString()),"citation_pair");targets.add(id);
            if(original.type().equals("application/pdf"))require(citation.get("location").get("page").asInt()>0,"pdf_page");
        }}
        if(query.equals(QUERIES.get(0)))require(targets.contains(uploads.get(0).attachment())&&targets.contains(uploaded("jpeg").attachment()),"image_retrieval");
        if(query.equals(QUERIES.get(1)))require(targets.contains(uploads.get(2).attachment()),"scan_retrieval");
        if(query.equals(QUERIES.get(2)))require(targets.contains(uploads.get(1).attachment()),"native_pdf_retrieval");
        if(query.equals(QUERIES.get(3)))require(targets.contains(uploads.get(4).attachment()),"speech_retrieval");
        if(query.equals(QUERIES.get(4)))require(targets.contains(uploads.get(5).attachment()),"acoustic_retrieval");
        if(query.equals(QUERIES.get(5)))require(result.get("citations").valueStream().anyMatch(c->c.get("attachmentId").asText().equals(uploaded("video-long").attachment().toString())&&c.get("location").get("timeStart").asDouble()<=25&&c.get("location").get("timeEnd").asDouble()>=25),"original_video_time");
        if(query.equals(QUERIES.get(9)))require(result.get("citations").valueStream().anyMatch(c->c.get("attachmentId").asText().equals(uploaded("seven").attachment().toString())&&c.get("location").get("page").asInt()==7),"page_seven_attribution");
        if(query.equals(QUERIES.get(6))||query.equals(QUERIES.get(7)))require(targets.size()>=4,"cross_media_evidence");
        if(query.equals(QUERIES.get(7)))require(result.get("coverage").get("completed").asBoolean()&&result.get("coverage").get("classificationUncertain").asBoolean(),"semantic_coverage");
        else require(!result.get("coverage").get("completed").asBoolean(),"ranked_not_exhaustive");
    }
    private void isolationAndRevocation()throws Exception{
        var isolated=query(other,QUERIES.get(0));require(isolated.get("citations").isEmpty()&&isolated.get("aiAnswer").isNull(),"other_owner_isolation");
        var target=uploads.get(0);String path="/api/notes/"+target.note()+"/attachments/"+target.attachment();
        other.get(path,404);var current=browser.send("GET",path,null,Map.of(),200);
        browser.send("DELETE",path,null,Map.of("If-Match",current.headers().firstValue("ETag").orElseThrow()),204);browser.get(path,404);
        require(query(browser,QUERIES.get(0)).get("citations").valueStream().noneMatch(c->c.get("attachmentId").asText().equals(target.attachment().toString())),"deleted_media_denied");
        var replacement=uploadBytes(target.note(),target.bytes(),target.type(),"replacement.png");
        String replacementId=JSON.readTree(replacement.body()).get("id").asText();require(!replacementId.equals(target.attachment().toString()),"replacement_identity");
        var replacementStatus=browser.get("/api/notes/"+target.note()+"/ai-processing",200);
        // Exact-source unpaid approval never transfers to a replacement, even
        // when its harmless bytes are identical. No convenient test bypass.
        require(replacementStatus.get("attachments").valueStream().anyMatch(a->a.get("attachmentId").asText().equals(replacementId)&&a.get("status").asText().equals("blocked")),"replacement_requires_own_approval");
        var audio=uploads.get(4);String notePath="/api/notes/"+audio.note();var note=browser.send("GET",notePath,null,Map.of(),200);
        browser.send("PUT",notePath+"/ai-access",JSON.writeValueAsBytes(Map.of("aiEnabled",false)),Map.of("Content-Type","application/json","If-Match",note.headers().firstValue("ETag").orElseThrow()),200);
        require(query(browser,QUERIES.get(3)).get("citations").valueStream().noneMatch(c->c.get("attachmentId").asText().equals(audio.attachment().toString())),"ai_off_denial");
        browser.send("GET",notePath+"/attachments/"+audio.attachment()+"/content",null,Map.of(),200);
        var mixed=uploads.get(3);String mixedPath="/api/notes/"+mixed.note();var before=browser.send("GET",mixedPath,null,Map.of(),200);
        browser.send("PUT",mixedPath,JSON.writeValueAsBytes(Map.of("title","Changed fictional context","markdown","Changed fictional source.")),Map.of("Content-Type","application/json","If-Match",before.headers().firstValue("ETag").orElseThrow()),200);
        require(query(browser,QUERIES.get(1)).get("citations").valueStream().anyMatch(c->c.get("attachmentId").asText().equals(mixed.attachment().toString())),"private_save_preserves_unchanged_attachment");
        String textPath="/api/notes/"+textNote;var oldText=browser.send("GET",textPath,null,Map.of(),200);
        browser.send("PUT",textPath,JSON.writeValueAsBytes(Map.of("title","Changed private text","markdown","Different fictional context.")),Map.of("Content-Type","application/json","If-Match",oldText.headers().firstValue("ETag").orElseThrow()),200);
        require(query(browser,QUERIES.get(6)).get("citations").valueStream().noneMatch(c->c.get("noteId").asText().equals(textNote.toString())&&c.get("attachmentId").isNull()),"source_change_invalidates_note_text");
    }
    private void providerFailureAndRecovery()throws Exception{
        require(provider.transientFailures.get()==1,"transient_media_retry_observed");
        provider.generationMode="invalid";
        var invalid=query(browser,QUERIES.get(0));require(invalid.get("aiAnswer").isNull()&&invalid.get("citations").isEmpty()&&invalid.get("degraded").asText().equals("invalidProviderOutput"),"invalid_output_denied");
        provider.generationMode="outage";
        var outage=query(browser,QUERIES.get(0));require(outage.get("aiAnswer").isNull()&&outage.get("degraded").asText().equals("providerUnavailable"),"provider_outage_truthful");
        browser.get("/api/notes/"+uploads.get(0).note(),200);
        provider.generationMode="normal";verify(QUERIES.get(0),query(browser,QUERIES.get(0)));
    }
    private void processingChangeRace()throws Exception {
        UUID note=note("Fictional processing race","Fictional context only.");var original=uploaded("image");
        var response=uploadBytes(note,original.bytes(),original.type(),"race.png");UUID attachment=UUID.fromString(JSON.readTree(response.body()).get("id").asText());
        var tx=new TransactionTemplate(app.getBean(PlatformTransactionManager.class));
        var source=tx.execute(s->app.getBean(PrivateDerivationSource.class).noteSources(owner,note)).stream().filter(s->attachment.equals(s.expected().attachmentId())).findFirst().orElseThrow();
        // Approve only the exact synthetic Attachment. Its parent Note root is
        // deliberately not approved, so no second derivation can masquerade as
        // the mutation waiter in the PostgreSQL coordination observation below.
        settings.put("knowledge.derivation.approved-source-fingerprints",settings.get("knowledge.derivation.approved-source-fingerprints")+","+ProviderDispatchPolicy.fingerprint(source.expected()));
        provider.blockDigest=TransformedMediaIdentity.digest(original.bytes());
        app.close();app=null;start();browser.origin=origin();other.origin=origin();
        try {
            require(provider.blockEntered.await(30,java.util.concurrent.TimeUnit.SECONDS),"processing_race_dispatch_entered");
            String path="/api/notes/"+note+"/attachments/"+attachment;
            var before=browser.send("GET",path,null,Map.of(),200);String etag=before.headers().firstValue("ETag").orElseThrow();
            var save=java.util.concurrent.CompletableFuture.runAsync(()->{try{
                browser.send("DELETE",path,null,Map.of("If-Match",etag),204);
            }catch(Exception failure){throw new java.util.concurrent.CompletionException(failure);}});
            var jdbc=app.getBean(JdbcTemplate.class);
            int key=java.nio.ByteBuffer.wrap(java.security.MessageDigest.getInstance("SHA-256").digest(note.toString().getBytes(StandardCharsets.UTF_8))).getInt();
            long until=System.nanoTime()+Duration.ofSeconds(3).toNanos();boolean waiting=false;
            do {
                waiting=Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_locks where locktype='advisory' and classid=17403 and objid=?::oid and not granted)",Boolean.class,Integer.toUnsignedLong(key)));
                if(waiting)break;Thread.sleep(20);
            }while(System.nanoTime()<until);
            require(waiting&&!save.isDone(),"processing_race_mutation_waits_for_dispatch");
            provider.blockRelease.countDown();save.get(15,java.util.concurrent.TimeUnit.SECONDS);
            long deadline=System.nanoTime()+Duration.ofSeconds(15).toNanos();String state;
            do {
                state=jdbc.queryForObject("select state from knowledge.knowledge_work_intent where owner_user_id=? and source_attachment_id=? order by created_at desc limit 1",String.class,owner,attachment);
                if(state.equals("obsolete"))break;Thread.sleep(100);
            }while(System.nanoTime()<deadline);
            require(state.equals("obsolete"),"processing_race_old_work_obsolete");
            require(jdbc.queryForObject("select count(*) from knowledge.private_derived_representation where owner_user_id=? and source_attachment_id=? and state='ready'",Integer.class,owner,attachment)==0,"processing_race_no_stale_activation");
            browser.get(path,404);browser.get(path+"/content",404);browser.get("/api/notes/"+note,200);
            require(query(browser,QUERIES.get(0)).get("citations").valueStream().noneMatch(c->c.get("attachmentId").asText().equals(attachment.toString())),"processing_race_no_stale_citation");
        }finally{provider.blockRelease.countDown();provider.blockDigest=null;}
    }
    private static TransformedMediaIdentity identity(AiDerivationProperties config,PrivateDerivationSource.Metadata metadata,EmbeddingLineage lineage,Uploaded original,byte[] bytes,String profile,String version,String artifact,String configuration,String type,TransformedMediaIdentity.Scope scope){return new TransformedMediaIdentity(metadata.expected(),TransformedMediaIdentity.digest(original.bytes()),original.bytes().length,original.type(),profile,version,artifact,configuration,TransformedMediaIdentity.digest(bytes),bytes.length,type,scope,lineage.id(),config.provider(),config.mediaModel(),"media-understanding",POLICY);}
    private Uploaded uploaded(String fixture){return uploads.stream().filter(u->u.fixture().equals(fixture)).findFirst().orElseThrow();}
    private static DerivedSegment pdfSegment(String fixture,int page){String text=fixture.equals("scan")||fixture.equals("mixed")&&page==3
        ?"Aster diagram: a blue square left of a red circle":page==7?"Page seven: the Aster invoice total is 730 credits. Payment is due 18 November.":page==3?"The fictional archive opens at dawn; its door is violet.":"Fictional archive ledger page "+page;
        return segment(text,"pdf_text",page,null,null);}
    private static DerivedSegment segment(String text,String kind,Integer page,Double from,Double until){return new DerivedSegment(text,kind,"",null,null,page,from,until,null,null,null,null);}
    private static void require(boolean valid,String code){if(!valid)throw new AssertionError(code);}

    @TestConfiguration(proxyBeanMethods=false)
    static class ExternalServices {
        @Bean @Primary Client controlledGoogleClient(){return Client.builder().apiKey("synthetic-not-a-provider-key").httpOptions(HttpOptions.builder().baseUrl("http://127.0.0.1:"+provider.server.getAddress().getPort()).timeout(10000).retryOptions(HttpRetryOptions.builder().attempts(1).build()).build()).build();}
        @Bean @Primary org.notesknowledge.security.RateLimitPort controlledExternalRateStore(){return request->new org.notesknowledge.security.RateLimitPort.Allowed();}
    }
    private static final class Browser {
        private final HttpClient http=HttpClient.newBuilder().sslContext(browserTls).connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
        String origin,cookie,csrf;
        Browser(String origin){this.origin=origin;}
        // Session/CSRF stay memory-only. The synthetic TLS certificate is trusted
        // only by this client and hostname verification remains enabled.
        HttpResponse<byte[]> send(String method,String path,byte[] data,Map<String,String> headers,Integer expected)throws Exception{
            require(path.startsWith("/api/")&&!path.contains("://"),"local_api_only");var request=HttpRequest.newBuilder(URI.create(origin+path)).timeout(Duration.ofSeconds(40));
            if(cookie!=null)request.header("Cookie",cookie);if(csrf!=null&&!method.equals("GET"))request.header("X-CSRF-TOKEN",csrf);headers.forEach(request::header);
            var response=http.send(request.method(method,data==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(data)).build(),HttpResponse.BodyHandlers.ofByteArray());
            response.headers().firstValue("Set-Cookie").ifPresent(v->{cookie=v.substring(0,v.indexOf(';'));LOG_CANARIES.add(cookie.substring(cookie.indexOf('=')+1));});
            if(expected!=null)require(response.statusCode()==expected,"http_"+method+"_expected_"+expected+"_actual_"+response.statusCode());return response;
        }
        JsonNode get(String path,int status)throws Exception{byte[] body=send("GET",path,null,Map.of(),status).body();return body.length==0?JSON.nullNode():JSON.readTree(body);}
        JsonNode json(String method,String path,Object data,int status)throws Exception{byte[] body=send(method,path,JSON.writeValueAsBytes(data),Map.of("Content-Type","application/json"),status).body();return body.length==0?JSON.nullNode():JSON.readTree(body);}
        void csrf()throws Exception{csrf=get("/api/auth/csrf",200).get("csrfToken").asText();LOG_CANARIES.add(csrf);}
        @Override public String toString(){return "SyntheticBrowser[REDACTED]";}
    }
    private static final class BoundedLogs extends ByteArrayOutputStream {
        boolean truncated;
        @Override public synchronized void write(byte[] b,int offset,int length){if(count+length>2*1024*1024){truncated=true;return;}super.write(b,offset,length);}
        @Override public synchronized void write(int b){if(count>=2*1024*1024){truncated=true;return;}super.write(b);}
    }
    /** Frozen, byte-bound media outputs and explicit semantic-vector oracle.
     * Real SDK JSON/HTTP paths; never claim these descriptions came from Gemini. */
    private static final class ControlledGemini implements AutoCloseable {
        final HttpServer server;final Map<String,List<DerivedSegment>> media=new ConcurrentHashMap<>();
        final Set<String> failOnce=ConcurrentHashMap.newKeySet();
        final java.util.concurrent.atomic.AtomicInteger requests=new java.util.concurrent.atomic.AtomicInteger(),transientFailures=new java.util.concurrent.atomic.AtomicInteger();
        volatile String generationMode="normal";
        volatile String blockDigest;
        final java.util.concurrent.CountDownLatch blockEntered=new java.util.concurrent.CountDownLatch(1),blockRelease=new java.util.concurrent.CountDownLatch(1);
        ControlledGemini()throws Exception{
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",exchange->{
                try{require(exchange.getRequestURI().getRawQuery()==null,"no_query_credentials");
                    byte[] input=exchange.getRequestBody().readNBytes(21*1024*1024);var payload=JSON.readTree(input);Arrays.fill(input,(byte)0);requests.incrementAndGet();
                    String path=exchange.getRequestURI().getPath();Object output;
                    if(path.endsWith(":embedContent")||path.endsWith(":batchEmbedContents")){
                        var rows=new ArrayList<Object>();var texts=payload.has("requests")?payload.get("requests"):JSON.valueToTree(List.of(payload));
                        for(var request:texts){String text=request.get("content").get("parts").get(0).get("text").asText();rows.add(Map.of("values",vector(text)));}
                        output=Map.of("embeddings",rows);
                    }else{
                        JsonNode parts=payload.get("contents").get(0).get("parts");JsonNode inline=parts.valueStream().filter(p->p.has("inlineData")).findFirst().orElse(null);Object structured;
                        if(inline!=null){byte[] bytes=Base64.getDecoder().decode(inline.get("inlineData").get("data").asText());
                            if(Objects.equals(blockDigest,TransformedMediaIdentity.digest(bytes))){blockEntered.countDown();require(blockRelease.await(8,java.util.concurrent.TimeUnit.SECONDS),"controlled_media_block_deadline");}
                            if(failOnce.remove(TransformedMediaIdentity.digest(bytes))){Arrays.fill(bytes,(byte)0);transientFailures.incrementAndGet();exchange.sendResponseHeaders(503,-1);return;}
                            var scope=java.util.regex.Pattern.compile("ONLY original PDF page ([0-9]+)").matcher(parts.get(0).get("text").asText());int page=scope.find()?Integer.parseInt(scope.group(1)):0;
                            var segments=media.get(TransformedMediaIdentity.digest(bytes)+"/"+page);Arrays.fill(bytes,(byte)0);require(segments!=null,"unknown_media_bytes");
                            var pages=segments.stream().map(DerivedSegment::page).filter(Objects::nonNull).distinct().toList();boolean noSpeech=segments.stream().allMatch(s->s.heading().equals("Acoustic description"));structured=Map.of("complete",true,"checkedPages",pages,"noSpeech",noSpeech,"segments",segments);
                        }else{
                            if(generationMode.equals("outage")){exchange.sendResponseHeaders(503,-1);return;}
                            String text=parts.get(0).get("text").asText();var task=JSON.readTree(text.substring(text.indexOf('{')));String query=task.get("query").asText();var claims=new ArrayList<Object>();
                            for(var e:task.get("evidence")){String fact=e.get("text").asText();if(match(query,fact,e.get("location")))claims.add(Map.of("text",fact,"evidenceIds",List.of(e.get("id").asText())));}
                            if(generationMode.equals("invalid")){claims.clear();claims.add(Map.of("text","Unsupported synthetic output","evidenceIds",List.of("foreign")));}
                            structured=Map.of("claims",claims,"conflicting",false);
                        }
                        output=Map.of("candidates",List.of(Map.of("finishReason","STOP","content",Map.of("role","model","parts",List.of(Map.of("text",JSON.writeValueAsString(structured)))))));
                    }
                    byte[] response=JSON.writeValueAsBytes(output);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);
                }catch(Exception|AssertionError invalid){exchange.sendResponseHeaders(503,-1);}finally{exchange.close();}
            });server.start();
        }
        void media(byte[] bytes,List<DerivedSegment> segments){media.put(TransformedMediaIdentity.digest(bytes)+"/"+segments.stream().map(DerivedSegment::page).filter(Objects::nonNull).findFirst().orElse(0),List.copyOf(segments));}
        static List<Float> vector(String text){String q=text.toLowerCase(Locale.ROOT);var v=new float[8];if(q.contains("aster")||q.contains("square")||q.contains("circle"))v[0]=1;if(q.contains("archive")||q.contains("door"))v[1]=1;if(q.contains("observatory")||q.contains("recording")&&!q.contains("tone"))v[2]=1;if(q.contains("tone"))v[3]=1;if(q.contains("video")||q.contains("scene"))v[4]=1;if(q.contains("scan")||q.contains("diagram"))v[5]=1;if(q.contains("page 7")||q.contains("page seven"))v[6]=1;if(Arrays.equals(v,new float[8]))v[7]=1;var values=new ArrayList<Float>();for(float f:v)values.add(f);return values;}
        static boolean match(String query,String fact,JsonNode location){String q=query.toLowerCase(Locale.ROOT),f=fact.toLowerCase(Locale.ROOT);
            if(q.contains("25 second"))return location!=null&&location.get("kind").asText().equals("video_scene")&&location.get("timeStart").asDouble()<=25&&location.get("timeEnd").asDouble()>=25;
            if(q.contains("page 7"))return location!=null&&!location.get("page").isNull()&&location.get("page").asInt()==7;
            if(q.contains("scan"))return location!=null&&!location.get("page").isNull()&&f.contains("aster");
            if(q.contains("door"))return f.contains("door");if(q.contains("archive"))return f.contains("archive");if(q.contains("observatory"))return f.contains("observatory");if(q.contains("tone"))return f.contains("tone");
            if(q.contains("image"))return location!=null&&Set.of("whole_image","image_region").contains(location.get("kind").asText())&&f.contains("square");
            return q.contains("aster")&&f.contains("aster");
        }
        public void close(){server.stop(0);media.clear();}
    }
}
