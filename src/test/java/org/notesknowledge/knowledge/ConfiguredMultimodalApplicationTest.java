package org.notesknowledge.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.notesknowledge.identity.IdentitySessionPrincipal;
import org.notesknowledge.knowledge.spi.PrivateAiSourceCurrentness;
import org.notesknowledge.notes.ConfiguredMediaStorageFixtures;
import org.notesknowledge.notes.MeaningfulMediaFixtures;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/** No mocked configuration or gates. Persisted uploads survive a full application
 * restart before approved derivation. Optional native roots are required by the
 * native CI job; ordinary mandatory CI proves the image/WAV application path. */
@Tag("DATABASE") @Tag("API") @Tag("SECURITY") @Tag("RETRIEVAL") @Tag("EVALUATION")
@Testcontainers
class ConfiguredMultimodalApplicationTest {
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18-trixie")
            .withDatabaseName("configured_multimodal").withUsername("synthetic_migrator").withPassword("synthetic-multimodal-password");
    @org.junit.jupiter.api.io.TempDir Path store;
    private static final String POLICY = "a".repeat(64);
    record Source(String kind, UUID attachment, byte[] bytes, String type, PrivateAiSourceCurrentness.Expected expected) { }

    @Test void configuredApplicationStoresDerivesAndRetrievesWithoutCrossUserOrAiOffLeakage() throws Exception {
        var settings = new LinkedHashMap<String,String>();
        settings.put("spring.datasource.url", postgres.getJdbcUrl());
        settings.put("spring.datasource.username", postgres.getUsername()); settings.put("spring.datasource.password", postgres.getPassword());
        settings.put("server.port", "0"); settings.put("spring.main.banner-mode", "off");
        settings.put("identity.rate.key-base64", "AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=");
        settings.put("synthetic.media-store", store.toString()); settings.put("knowledge.processing-policy.code", "configured-synthetic");
        settings.put("knowledge.derivation.enabled", "false");
        settings.put("knowledge.derivation.scheduling-enabled", "false");
        var sources = new ArrayList<Source>();
        var approvals = new ArrayList<String>();
        UUID owner, note, policy;
        String pdfTransform = null, videoTransform = null;
        boolean nativeMedia = System.getProperty("nkw.test.media.pdfRoot") != null;
        if (nativeMedia) {
            select(settings, "pdf", Path.of(System.getProperty("nkw.test.media.pdfRoot")));
            select(settings, "video", Path.of(System.getProperty("nkw.test.media.videoRoot")));
        }
        try (var application = start(settings)) {
            var jdbc = application.getBean(JdbcTemplate.class); var mvc = mvc(application);
            owner = account(jdbc); var browser = browser(application, mvc, owner);
            var created = mvc.perform(post("/api/notes").cookie(browser.cookie()).header("X-CSRF-TOKEN", browser.csrf())
                    .contentType("application/json").content("{\"title\":\"Synthetic application journey\",\"markdown\":\"Fictional content only\",\"aiEnabled\":true}"))
                    .andExpect(status().isCreated()).andReturn().getResponse();
            note = UUID.fromString(application.getBean(ObjectMapper.class).readTree(created.getContentAsString()).get("id").asText());
            policy = jdbc.queryForObject("insert into knowledge.processing_policy(policy_code,policy_version,policy_fingerprint,disclosure_revision,effective_at) values('configured-synthetic',1,?,'synthetic-1',clock_timestamp()-interval '1 minute') returning processing_policy_id", UUID.class, POLICY);
            jdbc.update("insert into knowledge.processing_policy_acknowledgement(user_id,processing_policy_id,disclosure_revision) values(?,?,'synthetic-1')",owner,policy);
            for (String kind : nativeMedia ? List.of("image","audio","pdf","video") : List.of("image","audio")) {
                var file = MeaningfulMediaFixtures.media(kind.equals("pdf") ? "mixed" : kind);
                byte[] bytes = file.getBytes();
                if (kind.equals("video")) {
                    // Real long-GOP VFR samples plus inert padding force the
                    // >inline-size path without changing accepted video samples.
                    byte[] actual = Files.readAllBytes(Path.of(System.getProperty("nkw.test.media.videoFixture")));
                    bytes = Arrays.copyOf(actual, 18_000_000);
                    java.nio.ByteBuffer.wrap(bytes).position(actual.length).putInt(bytes.length-actual.length).put(new byte[]{'f','r','e','e'});
                    file = new org.springframework.mock.web.MockMultipartFile("file", "fictional.mp4", "video/mp4", bytes);
                }
                var response = mvc.perform(multipart("/api/notes/"+note+"/attachments").file(file).cookie(browser.cookie()).header("X-CSRF-TOKEN",browser.csrf()))
                        .andExpect(status().isCreated()).andReturn().getResponse();
                UUID attachment = UUID.fromString(application.getBean(ObjectMapper.class).readTree(response.getContentAsString()).get("id").asText());
                var accepted = mvc.perform(get("/api/notes/"+note+"/attachments/"+attachment+"/content").cookie(browser.cookie()))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
                if (kind.equals("image")) {
                    // Upload intentionally canonicalizes image encoding. Compare
                    // every pixel, then retain the accepted representation for
                    // the restart/byte-custody assertion, not the upload envelope.
                    var before = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes));
                    var after = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(accepted));
                    assertThat(after.getWidth()).isEqualTo(before.getWidth());assertThat(after.getHeight()).isEqualTo(before.getHeight());
                    assertThat(Arrays.equals(before.getRGB(0,0,before.getWidth(),before.getHeight(),null,0,before.getWidth()),
                            after.getRGB(0,0,after.getWidth(),after.getHeight(),null,0,after.getWidth()))).as("all canonical image pixels preserved").isTrue();
                    before.flush();after.flush();
                } else assertThat(Arrays.equals(accepted,bytes)).as("accepted original media bytes preserved").isTrue();
                bytes = accepted;
                sources.add(new Source(kind,attachment,bytes,file.getContentType(),new PrivateAiSourceCurrentness.Expected(owner,note,attachment,1,1,1L)));
            }
            if (nativeMedia) {
                for (var source : sources) {
                    if (source.kind().equals("pdf")) {
                        try (var child = ConfiguredMediaStorageFixtures.page(application,source.bytes(),1)) { pdfTransform = child.transformFingerprint(); }
                        var config = configuration(settings,true,pdfTransform,"0".repeat(64));
                        var lineage = lineage(config,policy,"pdf");
                        for (int page=1;page<=3;page++) try (var child = ConfiguredMediaStorageFixtures.page(application,source.bytes(),page)) {
                            byte[] payload = child.bytes();
                            approvals.add(new TransformedMediaIdentity(source.expected(),TransformedMediaIdentity.digest(source.bytes()),source.bytes().length,source.type(),
                                    child.profile(),child.implementationVersion(),child.artifactSha256(),child.configurationSha256(),TransformedMediaIdentity.digest(payload),payload.length,"image/png",
                                    new TransformedMediaIdentity.Scope("pdf_pages",page,page,0,0,0),lineage.id(),"gemini","controlled-media","media-understanding",POLICY).approvalFingerprint());
                            Arrays.fill(payload,(byte)0);
                        }
                    }
                    if (source.kind().equals("video")) {
                        var plan=ConfiguredMediaStorageFixtures.plan(application,source.bytes());
                        assertThat((double)plan.tick(plan.durations().size())/plan.timescale()).isGreaterThan(30);
                        assertThat(plan.durations().stream().distinct().count()).isGreaterThan(1);
                        try(var child=ConfiguredMediaStorageFixtures.clip(application,source.bytes(),plan,0,plan.next(0))) { videoTransform=child.transformFingerprint(); }
                        var lineage=lineage(configuration(settings,true,"0".repeat(64),videoTransform),policy,"video");
                        for(int first=0;first<plan.durations().size();) {
                            int last=plan.next(first);
                            try(var child=ConfiguredMediaStorageFixtures.clip(application,source.bytes(),plan,first,last)) {
                                byte[] payload=child.bytes();
                                approvals.add(new TransformedMediaIdentity(source.expected(),TransformedMediaIdentity.digest(source.bytes()),source.bytes().length,source.type(),
                                        child.profile(),child.implementationVersion(),child.artifactSha256(),child.configurationSha256(),TransformedMediaIdentity.digest(payload),payload.length,"video/mp4",
                                        new TransformedMediaIdentity.Scope("video_samples",first,last,plan.timescale(),plan.tick(first),plan.tick(last)),lineage.id(),"gemini","controlled-media","media-understanding",POLICY).approvalFingerprint());
                                Arrays.fill(payload,(byte)0);
                            }
                            first=last;
                        }
                    }
                }
            }
            jdbc.execute("create role synthetic_media_runtime login password 'synthetic-runtime-password' nosuperuser nocreatedb nocreaterole noreplication");
            jdbc.execute("grant connect on database configured_multimodal to synthetic_media_runtime");
            jdbc.execute("grant usage on schema public,identity,profile,notes,publishing,discovery,knowledge,moderation to synthetic_media_runtime");
            jdbc.execute("grant select,insert,update,delete on all tables in schema public,identity,profile,notes,publishing,discovery,knowledge,moderation to synthetic_media_runtime");
            jdbc.execute("grant usage,select on all sequences in schema public,identity,profile,notes,publishing,discovery,knowledge,moderation to synthetic_media_runtime");
        }
        settings.put("spring.datasource.username","synthetic_media_runtime");settings.put("spring.datasource.password","synthetic-runtime-password");
        settings.put("spring.flyway.enabled","false");
        configure(settings,nativeMedia,pdfTransform,videoTransform);
        settings.put("knowledge.derivation.dispatch-policy","unpaid-synthetic-demo");
        settings.put("knowledge.derivation.approved-source-fingerprints",String.join(",",sources.stream().map(s->ProviderDispatchPolicy.fingerprint(s.expected())).toList()));
        settings.put("knowledge.derivation.approved-child-fingerprints",String.join(",",approvals));
        try(var application=start(settings)) {
            var jdbc=application.getBean(JdbcTemplate.class);var mvc=mvc(application);var browser=browser(application,mvc,owner);
            assertThat(jdbc.queryForObject("select rolsuper or rolcreatedb or rolcreaterole or rolreplication from pg_roles where rolname=current_user",Boolean.class)).isFalse();
            org.assertj.core.api.Assertions.assertThatThrownBy(()->jdbc.execute("create table notes.forbidden_runtime_ddl(value integer)"))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            var config=application.getBean(AiDerivationProperties.class);assertThat(config.configured()).isTrue();
            var tx=new TransactionTemplate(application.getBean(org.springframework.transaction.PlatformTransactionManager.class));
            var repository=application.getBean(KnowledgeWorkRepository.class);var work=application.getBean(KnowledgeWorkService.class);
            for(var source:sources) {
                // These are original stored bytes, read through the owner API
                // after restart, not transient fixture state from the first app.
                assertThat(Arrays.equals(mvc.perform(get("/api/notes/"+note+"/attachments/"+source.attachment()+"/content").cookie(browser.cookie()))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray(),source.bytes())).as("stored representation survives application restart").isTrue();
                var lineage=lineage(config,policy,source.kind());
                tx.execute(s->repository.enqueue(KnowledgeWork.Kind.ATTACHMENT,source.expected(),lineage.id()));
                var claim=work.claim(new org.notesknowledge.LeaseOwner("configured-synthetic"),10).stream()
                        .filter(c->c.intent().expected().equals(source.expected())).findFirst().orElseThrow();
                application.getBean(DerivationExecutor.class).execute(claim);
                assertThat(jdbc.queryForObject("select state from knowledge.knowledge_work_intent where knowledge_work_intent_id=?",String.class,claim.intent().id())).isEqualTo("completed");
                activate(owner);
                var request=request(browser);
                var results=application.getBean(ExactPrivateVectorSearch.class).search(request,source.kind(),new float[]{1,0,0,0,0,0,0,0},100);
                assertThat(results).isNotEmpty().allMatch(c->c.expected().owner().equals(owner)&&c.expected().attachmentId().equals(source.attachment()));
                if(source.kind().equals("audio"))assertThat(results.stream().map(c->c.segment().timeStart()).toList()).contains(0d,2d);
                if(source.kind().equals("video"))assertThat(results.stream().map(c->c.segment().timeStart()).max(Double::compareTo).orElseThrow()).isGreaterThan(30d);
            }
            UUID other=account(jdbc);jdbc.update("insert into knowledge.processing_policy_acknowledgement(user_id,processing_policy_id,disclosure_revision) values(?,?,'synthetic-1')",other,policy);
            var otherBrowser=browser(application,mvc,other);activate(other);
            for(var source:sources)assertThat(application.getBean(ExactPrivateVectorSearch.class).search(request(otherBrowser),source.kind(),new float[]{1,0,0,0,0,0,0,0},100)).isEmpty();
            var etag=mvc.perform(get("/api/notes/"+note).cookie(browser.cookie())).andExpect(status().isOk()).andReturn().getResponse().getHeader("ETag");
            mvc.perform(put("/api/notes/"+note+"/ai-access").cookie(browser.cookie()).header("X-CSRF-TOKEN",browser.csrf()).header("If-Match",etag)
                    .contentType("application/json").content("{\"aiEnabled\":false}")).andExpect(status().isOk());
            activate(owner);
            for(var source:sources)assertThat(application.getBean(ExactPrivateVectorSearch.class).search(request(browser),source.kind(),new float[]{1,0,0,0,0,0,0,0},100)).isEmpty();
            for(var source:sources)assertThat(Arrays.equals(mvc.perform(get("/api/notes/"+note+"/attachments/"+source.attachment()+"/content").cookie(browser.cookie()))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray(),source.bytes())).as("AI off preserves owner byte access").isTrue();
            assertThat(application.getBean(ControlledProviders.class).childCalls).isEqualTo(nativeMedia?6:0);
        } finally { SecurityContextHolder.clearContext(); }
    }
    private ConfigurableApplicationContext start(Map<String,String> settings) {
        return new SpringApplicationBuilder(org.notesknowledge.Application.class,ConfiguredMediaStorageFixtures.class,ControlledProviders.class)
                .profiles("test").run(settings.entrySet().stream().map(e->"--"+e.getKey()+"="+e.getValue()).toArray(String[]::new));
    }
    private void select(Map<String,String> settings,String kind,Path root)throws Exception {
        String prefix="notes.media-runtime."+kind+".";
        settings.put(prefix+"enabled","true");settings.put(prefix+"profile","linux-confined-v1");settings.put(prefix+"root",root.toString());
        var records=Files.createDirectory(store.resolve(kind+"-records"));Files.setPosixFilePermissions(records,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        settings.put(prefix+"recovery-directory",records.toString());
        settings.put(prefix+"artifact-identity",kind.equals("pdf")?ConfiguredMediaStorageFixtures.pdfIdentity(root):ConfiguredMediaStorageFixtures.videoIdentity(root));
        settings.put(prefix+"version",kind.equals("pdf")?"native-pdf-v1":"ffmpeg-9.0.2-profile-v1");
        if(kind.equals("video")){var scratch=Files.createDirectory(store.resolve("video-scratch"));Files.setPosixFilePermissions(scratch,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));settings.put(prefix+"scratch-directory",scratch.toString());}
    }
    private static void configure(Map<String,String> settings,boolean nativeMedia,String pdf,String video) {
        String prefix="knowledge.derivation.";
        Map.ofEntries(Map.entry("enabled","true"),Map.entry("provider","gemini"),Map.entry("embedding-model","controlled-embedding"),Map.entry("media-model","controlled-media"),
                Map.entry("model-revision","v1"),Map.entry("region","global"),Map.entry("tier","unpaid"),Map.entry("configuration-id","controlled-v1"),
                Map.entry("adapter-version","controlled-v1"),Map.entry("dimension","8"),Map.entry("operator","cosine"),Map.entry("normalization","none"),
                Map.entry("approved-policy-fingerprint",POLICY),Map.entry("pdf-page-children",Boolean.toString(nativeMedia)),Map.entry("video-children",Boolean.toString(nativeMedia)))
                .forEach((key,value)->settings.put(prefix+key,value));
        if(nativeMedia){settings.put(prefix+"pdf-transform-fingerprint",pdf);settings.put(prefix+"video-transform-fingerprint",video);}
    }
    private static AiDerivationProperties configuration(Map<String,String> ignored,boolean nativeMedia,String pdf,String video) {
        return new AiDerivationProperties(true,false,"gemini","controlled-embedding","controlled-media","v1","global","unpaid","controlled-v1","controlled-v1",8,"cosine","none",POLICY,nativeMedia,pdf,nativeMedia,video);
    }
    private static EmbeddingLineage lineage(AiDerivationProperties configuration,UUID policy,String kind) {return EmbeddingLineage.create(configuration,new ProcessingPolicyService.AcknowledgedProcessingPolicy(policy,1,POLICY),kind);}
    private static MockMvc mvc(ConfigurableApplicationContext app) {return org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup((WebApplicationContext)app)
            .addFilters(app.getBean("springSessionRepositoryFilter",jakarta.servlet.Filter.class))
            .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();}
    private static UUID account(JdbcTemplate jdbc) {UUID id=jdbc.queryForObject("select uuidv7()",UUID.class);String email="synthetic-"+id+"@example.test";jdbc.update("insert into identity.account(user_id,canonical_email,display_email,email_verified_at,account_state,created_at,updated_at) values(?,?,?,now(),'active',now(),now())",id,email,email);return id;}
    record Browser(String session,Cookie cookie,String csrf) { }
    @SuppressWarnings({"rawtypes","unchecked"}) private static Browser browser(ConfigurableApplicationContext app,MockMvc mvc,UUID owner)throws Exception {
        var repository=app.getBean(SessionRepository.class);Session session=repository.createSession();activate(owner);session.setAttribute("SPRING_SECURITY_CONTEXT",SecurityContextHolder.getContext());repository.save(session);SecurityContextHolder.clearContext();
        var cookie=new Cookie("SESSION",Base64.getEncoder().encodeToString(session.getId().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var response=mvc.perform(get("/api/auth/csrf").cookie(cookie)).andExpect(status().isOk()).andReturn().getResponse();
        return new Browser(session.getId(),response.getCookie("SESSION")==null?cookie:response.getCookie("SESSION"),app.getBean(ObjectMapper.class).readTree(response.getContentAsString()).get("csrfToken").asText());
    }
    private static void activate(UUID owner){var context=SecurityContextHolder.createEmptyContext();context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(new IdentitySessionPrincipal(owner),null,List.of(new SimpleGrantedAuthority("ROLE_USER"))));SecurityContextHolder.setContext(context);}
    private static MockHttpServletRequest request(Browser browser){var request=new MockHttpServletRequest();request.setSession(new MockHttpSession(null,browser.session()));return request;}
    @TestConfiguration(proxyBeanMethods=false)
    static class ControlledProviders {
        int childCalls;
        // Deterministic external rate-store adapter for this media qualification;
        // production still fails closed when its configured rate store is absent.
        @Bean @Primary org.notesknowledge.security.RateLimitPort configuredRateStore() {
            return request -> new org.notesknowledge.security.RateLimitPort.Allowed();
        }
        @Bean @Primary TextEmbeddingPort configuredEmbeddings() {return new TextEmbeddingPort(){
            public boolean available(){return true;}
            public List<float[]> embed(AiProcessingGate.SourceAiPermit permit,List<String> text){permit.requireGoogleDispatch();return text.stream().map(t->new float[]{1,0,0,0,0,0,0,0}).toList();}
        };}
        @Bean @Primary MediaUnderstandingPort configuredUnderstanding(){return new MediaUnderstandingPort(){
            public boolean available(){return true;}
            public List<DerivedSegment> describe(AiProcessingGate.SourceAiPermit permit,byte[] bytes,MediaUnderstandingRequest request){
                permit.requireGoogleDispatch();
                if(request.mediaType().equals("image/png"))return List.of(new DerivedSegment("ASTER label; blue square left of red circle","whole_image","",null,null,null,null,null,null,null,null,null));
                if(request.mediaType().equals("audio/wav"))return List.of(new DerivedSegment("Fictional observatory on planet Aster","transcript","",null,null,null,0d,2d,null,null,null,null),new DerivedSegment("Silver dome","transcript","",null,null,null,2d,4d,null,null,null,null));
                throw new AssertionError("Unexpected original provider payload");
            }
            public List<DerivedSegment> describeChild(AiProcessingGate.SourceAiPermit permit,TransformedMediaIdentity child,byte[] original,byte[] bytes,MediaUnderstandingRequest request){
                permit.requireGoogleChildDispatch(child,original,bytes);GoogleDerivationAdapters.requireChildScope(child,request);childCalls++;
                if(request.mediaType().equals("image/png"))return List.of(new DerivedSegment("Synthetic selected-page visual evidence","pdf_text","",null,null,request.firstPage(),null,null,null,null,null,null));
                return List.of(new DerivedSegment("Synthetic visible scene interval","video_scene","",null,null,null,0d,1d,null,null,null,null));
            }
        };}
    }
}
