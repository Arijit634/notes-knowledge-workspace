package org.notesknowledge.knowledge;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.notesknowledge.*;
import org.notesknowledge.identity.IdentitySessionPrincipal;
import org.notesknowledge.knowledge.spi.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.session.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/** Opt-in test-classpath command. Prepare uploads ONLY frozen synthetic media to an
 * explicitly confirmed fresh loopback PostgreSQL database. Execute requires separate
 * approval of the exact plan hash, fresh quota consent and ordinary production gates.
 * No user-file input, automatic child approval, mocked permit, Files API or retry. */
public final class MediaQualityQualificationCommand {
    record Source(String fixture,PrivateDerivationSource.Metadata metadata,String sha256) { }
    record Plan(UUID owner,UUID policy,AiDerivationProperties configuration,List<Source> sources,
            List<String> children,List<String> queries) { }
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final String POLICY="synthetic-media-qualification";

    public static void main(String[] args) {
        if(args.length<2||!Set.of("prepare","execute").contains(args[0]))throw new IllegalArgumentException("prepare|execute and local run directory required");
        Path directory=Path.of(args[1]).toAbsolutePath().normalize();boolean execute=args[0].equals("execute");
        try {
            require("true".equals(System.getenv("NKW_MEDIA_SYNTHETIC_DATABASE_CONFIRMED")));
            Plan plan=execute?JSON.readValue(Files.readAllBytes(directory.resolve("plan.json")),Plan.class):null;
            if(execute)requireExecutionConsent(System.getenv(),Files.readAllBytes(directory.resolve("plan.json")));
            else Files.createDirectory(directory);
            try(var context=start(directory,plan,Arrays.copyOfRange(args,2,args.length))) {
                if(execute)execute(context,directory,plan,limits());
                else prepare(context,directory,fixtureSelection(System.getenv("NKW_MEDIA_FIXTURES")));
            }
            System.out.println(execute?"Media qualification executed; inspect bounded evidence, not a blanket quality claim":"Synthetic plan prepared; inspect and explicitly approve plan.json before execute");
        } catch(Exception failure) {
            System.err.println("media_qualification_failed; no automatic replay authorized");System.exit(1);
        } finally {SecurityContextHolder.clearContext();}
    }
    static void requireExecutionConsent(Map<String,String> environment,byte[] plan){
        require("true".equals(environment.get("NKW_GEMINI_QUALIFICATION_APPROVED"))&&"true".equals(environment.get("NKW_GEMINI_FREE_TIER_CONFIRMED"))
            &&QualificationJournal.hash(plan).equals(environment.get("NKW_GEMINI_APPROVED_PLAN_SHA256")));
    }
    static List<String> fixtureSelection(String value)throws Exception {
        require(value!=null&&!value.isBlank());var selected=List.of(value.split(",",-1));
        var allowed=MediaQualityQualificationHarness.frozenCorpus().stream().map(MediaQualityQualificationHarness.Fixture::id).toList();
        require(selected.size()<=10&&new HashSet<>(selected).size()==selected.size()&&allowed.containsAll(selected));return selected;
    }
    private static QualificationRequestBudget.Limits limits() {
        var result=new QualificationRequestBudget.Limits(Integer.parseInt(System.getenv("NKW_GEMINI_MAX_REQUESTS")),
            Long.parseLong(System.getenv("NKW_GEMINI_MAX_ESTIMATED_BYTES")),Long.parseLong(System.getenv("NKW_GEMINI_MAX_OUTPUT_TOKENS")),15);
        result.require();return result;
    }
    static ConfigurableApplicationContext start(Path directory,Plan plan,String[] args,Class<?>... controlledTestConfiguration) {
        var configurations=new ArrayList<Class<?>>(List.of(Application.class,org.notesknowledge.notes.PersistentQualificationMediaFixtures.class));
        configurations.addAll(List.of(controlledTestConfiguration));
        return new SpringApplicationBuilder(configurations.toArray(Class<?>[]::new))
            .profiles("test").initializers(context->{
                String url=context.getEnvironment().getProperty("spring.datasource.url","");
                // No remote DB, URL credentials, unrelated development DB or silent default.
                require(url.matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost|host\\.docker\\.internal):[0-9]{1,5}/nkw_media_qualification(\\?loggerLevel=OFF)?"));
                var fixed=new HashMap<String,Object>();
                fixed.put("server.address","127.0.0.1");fixed.put("server.port",0);
                fixed.put("synthetic.qualification.object-directory",directory.resolve("objects").toString());
                fixed.put("knowledge.derivation.enabled",plan!=null);
                for(String property:List.of("knowledge.derivation.scheduling-enabled","knowledge.operations.scheduling-enabled",
                    "identity.delivery.polling-enabled","identity.delivery.smtp-enabled","identity.oidc.google.enabled",
                    "notes.private-storage.enabled","profile.avatar-storage.enabled","profile.public-avatar-storage.enabled","publishing.media-storage.enabled"))fixed.put(property,false);
                fixed.put("identity.rate.key-base64",Base64.getEncoder().encodeToString(new byte[32]));
                fixed.put("knowledge.processing-policy.code",POLICY);
                if(plan!=null){
                    var c=plan.configuration();
                    fixed.put("knowledge.derivation.provider",c.provider());fixed.put("knowledge.derivation.embedding-model",c.embeddingModel());
                    fixed.put("knowledge.derivation.media-model",c.mediaModel());fixed.put("knowledge.derivation.model-revision",c.modelRevision());
                    fixed.put("knowledge.derivation.region",c.region());fixed.put("knowledge.derivation.tier",c.tier());
                    fixed.put("knowledge.derivation.configuration-id",c.configurationId());fixed.put("knowledge.derivation.adapter-version",c.adapterVersion());
                    fixed.put("knowledge.derivation.dimension",c.dimension());fixed.put("knowledge.derivation.operator",c.operator());
                    fixed.put("knowledge.derivation.normalization",c.normalization());fixed.put("knowledge.derivation.approved-policy-fingerprint",c.approvedPolicyFingerprint());
                    fixed.put("knowledge.derivation.pdf-page-children",c.pdfPageChildren());fixed.put("knowledge.derivation.video-children",c.videoChildren());
                    if(c.pdfTransformFingerprint()!=null)fixed.put("knowledge.derivation.pdf-transform-fingerprint",c.pdfTransformFingerprint());
                    if(c.videoTransformFingerprint()!=null)fixed.put("knowledge.derivation.video-transform-fingerprint",c.videoTransformFingerprint());
                    fixed.put("knowledge.derivation.dispatch-policy","unpaid-synthetic-demo");
                    var sources=new ArrayList<String>();
                    for(var source:plan.sources()){
                        sources.add(ProviderDispatchPolicy.fingerprint(source.metadata().expected()));
                        var e=source.metadata().expected();sources.add(ProviderDispatchPolicy.fingerprint(new PrivateAiSourceCurrentness.Expected(e.owner(),e.noteId(),null,e.revision(),e.aiGeneration(),null)));
                    }
                    fixed.put("knowledge.derivation.approved-source-fingerprints",sources.stream().distinct().toList());
                    fixed.put("knowledge.derivation.approved-child-fingerprints",plan.children());
                    fixed.put("knowledge.derivation.approved-query-fingerprints",plan.queries().stream().map(ProviderDispatchPolicy::queryFingerprint).toList());
                }
                context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("isolated-synthetic-qualification",fixed));
            }).run(args);
    }
    static void prepare(ConfigurableApplicationContext context,Path directory,List<String> selection)throws Exception {
        var jdbc=context.getBean(JdbcTemplate.class);
        require(jdbc.queryForObject("select count(*) from identity.account",Integer.class)==0);
        require(jdbc.queryForObject("select count(*) from knowledge.processing_policy",Integer.class)==0);
        var c=context.getBean(AiDerivationProperties.class);
        c=new AiDerivationProperties(true,false,c.provider(),c.embeddingModel(),c.mediaModel(),c.modelRevision(),c.region(),c.tier(),c.configurationId(),c.adapterVersion(),c.dimension(),c.operator(),c.normalization(),c.approvedPolicyFingerprint(),c.pdfPageChildren(),c.pdfTransformFingerprint(),c.videoChildren(),c.videoTransformFingerprint());
        require(c.configured()&&"gemini".equals(c.provider())&&"unpaid".equals(c.tier())&&"global".equals(c.region())&&GeminiTextEmbeddings.embedding2(c));
        if(selection.contains("long-video"))require(c.videoChildren());
        UUID owner=jdbc.queryForObject("select uuidv7()",UUID.class);String email="synthetic-qualification-"+owner+"@example.test";
        jdbc.update("insert into identity.account(user_id,canonical_email,display_email,email_verified_at,account_state,created_at,updated_at) values(?,?,?,now(),'active',now(),now())",owner,email,email);
        // Even this opt-in harness needs an explicitly supplied approved deployment
        // bundle. It must not fabricate provider disclosure or silently approve it.
        var disclosureProperties=context.getBean(ProcessingDisclosureProperties.class);
        require(disclosureProperties.path()!=null);
        var disclosure=ProcessingDisclosure.decode(Files.readAllBytes(Path.of(disclosureProperties.path())));
        require(disclosure.code().equals(POLICY)&&disclosure.version()==1&&disclosure.revision().equals("synthetic-1")
            &&disclosure.fingerprint().equals(c.approvedPolicyFingerprint()));
        context.getBean(ProcessingDisclosureResolver.class).require(new ProcessingPolicyRepository.Policy(disclosure.id(),disclosure.code(),disclosure.version(),disclosure.fingerprint(),disclosure.revision(),disclosure.effectiveAt()));
        UUID policy=disclosure.id();
        jdbc.update("insert into knowledge.processing_policy(processing_policy_id,policy_code,policy_version,policy_fingerprint,disclosure_revision,effective_at) values(?,?,1,?,'synthetic-1',?)",policy,POLICY,c.approvedPolicyFingerprint(),java.sql.Timestamp.from(disclosure.effectiveAt()));
        jdbc.update("insert into knowledge.processing_policy_acknowledgement(user_id,processing_policy_id,disclosure_revision) values(?,?,'synthetic-1')",owner,policy);
        var mvc=MockMvcBuilders.webAppContextSetup((org.springframework.web.context.WebApplicationContext)context)
            .addFilters(context.getBean(org.springframework.session.web.http.SessionRepositoryFilter.class)).apply(springSecurity()).build();
        var browser=browser(context,mvc,owner);var sources=context.getBean(PrivateDerivationSource.class);
        var prepared=new ArrayList<Source>();var children=new ArrayList<String>();
        for(var fixture:MediaQualityQualificationHarness.frozenCorpus())if(selection.contains(fixture.id())) {
            UUID note=jdbc.queryForObject("insert into notes.note(note_id,owner_user_id,title,markdown,lifecycle_state,ai_enabled,revision,ai_generation,created_at,updated_at) values(uuidv7(),?,'Synthetic qualification','Synthetic qualification','active',true,1,1,clock_timestamp(),clock_timestamp()) returning note_id",UUID.class,owner);
            var response=mvc.perform(multipart("/api/notes/"+note+"/attachments").file(new org.springframework.mock.web.MockMultipartFile("file","synthetic"+extension(fixture.mediaType()),fixture.mediaType(),fixture.bytes()))
                .cookie(browser.cookie()).header("X-CSRF-TOKEN",browser.csrf())).andReturn().getResponse();
            if(response.getStatus()!=201)throw new IllegalStateException("Synthetic upload rejected: "+response.getStatus()+" "+fixture.id());
            UUID attachment=UUID.fromString(JSON.readTree(response.getContentAsString()).get("id").asText());
            var metadata=tx(context,()->sources.noteSources(owner,note)).stream().filter(s->attachment.equals(s.expected().attachmentId())).findFirst().orElseThrow();
            var source=new Source(fixture.id(),metadata,TransformedMediaIdentity.digest(fixture.bytes()));
            var material=tx(context,()->sources.acquire(metadata.expected()));
            byte[] original;try(var input=material.open()){original=input.readNBytes((int)metadata.sizeBytes()+1);}
            require(original.length==metadata.sizeBytes()&&TransformedMediaIdentity.digest(original).equals(source.sha256()));
            children.addAll(childApprovals(c,new ProcessingPolicyService.AcknowledgedProcessingPolicy(policy,1,c.approvedPolicyFingerprint()),metadata,material,original));
            prepared.add(source);Arrays.fill(original,(byte)0);
        }
        var queries=prepared.stream().map(s->query(s.fixture())).distinct().toList();
        QualificationJournal.forceCreate(directory.resolve("plan.json"),JSON.writeValueAsBytes(new Plan(owner,policy,c,List.copyOf(prepared),List.copyOf(children),queries)));
        QualificationJournal.forceCreate(directory.resolve("plan.sha256"),QualificationJournal.hash(Files.readAllBytes(directory.resolve("plan.json"))).getBytes(StandardCharsets.US_ASCII));
    }
    private static List<String> childApprovals(AiDerivationProperties c,ProcessingPolicyService.AcknowledgedProcessingPolicy policy,
            PrivateDerivationSource.Metadata metadata,PrivateDerivationSource.Material material,byte[] original) {
        var result=new ArrayList<String>();String lineage=EmbeddingLineage.create(c,policy,metadata.modality()).id();
        if(metadata.modality().equals("pdf")&&c.pdfPageChildren())for(var page:material.pdfPages(original)) {
            if(page.provenEmpty())continue;
            try(var child=material.pdfChild(original,page.page())){
                require(child.transformFingerprint().equals(c.pdfTransformFingerprint()));byte[] bytes=child.bytes();
                result.add(identity(c,metadata,lineage,original,bytes,child.profile(),child.implementationVersion(),child.artifactSha256(),child.configurationSha256(),"image/png",
                    new TransformedMediaIdentity.Scope("pdf_pages",page.page(),page.page(),0,0,0)).approvalFingerprint());Arrays.fill(bytes,(byte)0);
            }
        }
        if(metadata.modality().equals("video")&&c.videoChildren()){
            var timeline=material.videoPlan(original);
            for(int first=0;first<timeline.durations().size();){int last=timeline.next(first);
                try(var child=material.videoChild(original,timeline,first,last)){
                    require(child.transformFingerprint().equals(c.videoTransformFingerprint()));byte[] bytes=child.bytes();
                    result.add(identity(c,metadata,lineage,original,bytes,child.profile(),child.implementationVersion(),child.artifactSha256(),child.configurationSha256(),"video/mp4",
                        new TransformedMediaIdentity.Scope("video_samples",first,last,timeline.timescale(),timeline.tick(first),timeline.tick(last))).approvalFingerprint());Arrays.fill(bytes,(byte)0);
                }first=last;
            }
        }
        return result;
    }
    private static TransformedMediaIdentity identity(AiDerivationProperties c,PrivateDerivationSource.Metadata m,String lineage,byte[] original,byte[] child,
            String profile,String version,String artifact,String configuration,String type,TransformedMediaIdentity.Scope scope){
        return new TransformedMediaIdentity(m.expected(),TransformedMediaIdentity.digest(original),original.length,m.mediaType(),profile,version,artifact,configuration,
            TransformedMediaIdentity.digest(child),child.length,type,scope,lineage,c.provider(),c.mediaModel(),"media-understanding",c.approvedPolicyFingerprint());
    }
    private static String extension(String type){return switch(type){case "image/png"->".png";case "audio/wav"->".wav";case "application/pdf"->".pdf";case "video/mp4"->".mp4";default->throw new IllegalStateException("Unknown frozen type");};}
    static String query(String fixture){return switch(fixture){
        case "audio"->"Where is the fictional observatory with a silver dome?";
        case "tones"->"Where is the recording of high tones changing to lower tones?";
        case "diagram-image","diagram-pdf"->"Which source records equipment reserve of 740 credits?";
        case "text-pdf"->"Which source says the fictional archive opens at dawn and has a violet door?";
        default->"Where is the blue square beside a red circle and ASTER?";};}
    private static <T>T tx(ConfigurableApplicationContext c,java.util.function.Supplier<T> work){var t=new TransactionTemplate(c.getBean(PlatformTransactionManager.class));t.setTimeout(3);return t.execute(s->work.get());}
    private static void require(boolean valid){if(!valid)throw new IllegalStateException("Synthetic qualification prerequisite failed");}
    private record Browser(jakarta.servlet.http.Cookie cookie,String csrf,String session) { }
    @SuppressWarnings({"unchecked","rawtypes"})
    private static Browser browser(ConfigurableApplicationContext context,MockMvc mvc,UUID owner)throws Exception {
        SessionRepository repository=context.getBean(SessionRepository.class);Session session=repository.createSession();
        var security=SecurityContextHolder.createEmptyContext();security.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(new IdentitySessionPrincipal(owner),null,List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        session.setAttribute("SPRING_SECURITY_CONTEXT",security);repository.save(session);
        var cookie=new jakarta.servlet.http.Cookie("SESSION",Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8)));
        var response=mvc.perform(get("/api/auth/csrf").cookie(cookie)).andReturn().getResponse();require(response.getStatus()==200);
        return new Browser(response.getCookie("SESSION")==null?cookie:response.getCookie("SESSION"),JSON.readTree(response.getContentAsString()).get("csrfToken").asText(),session.getId());
    }
    private static void execute(ConfigurableApplicationContext c,Path directory,Plan plan,QualificationRequestBudget.Limits limits)throws Exception {
        execute(c,directory,plan,limits,Clock.systemUTC(),duration->{try{Thread.sleep(duration);}catch(InterruptedException stopped){Thread.currentThread().interrupt();throw new IllegalStateException("Qualification cancelled");}},"live-gemini");
    }
    static void execute(ConfigurableApplicationContext c,Path directory,Plan plan,QualificationRequestBudget.Limits limits,Clock clock,java.util.function.Consumer<Duration> pause,String mode)throws Exception {
        require(Set.of("controlled-sdk","live-gemini").contains(mode));
        var jdbc=c.getBean(JdbcTemplate.class);require(jdbc.queryForObject("select count(*) from identity.account",Integer.class)==1);
        require(jdbc.queryForObject("select count(*) from identity.account where user_id=? and canonical_email=?",Integer.class,plan.owner(),"synthetic-qualification-"+plan.owner()+"@example.test")==1);
        var configuration=c.getBean(AiDerivationProperties.class);require(configuration.equals(plan.configuration()));
        var budget=new QualificationRequestBudget(directory.resolve("requests"),limits,clock);
        var work=c.getBean(KnowledgeWorkService.class);var current=new AtomicReference<KnowledgeWork.Claim>();
        var paced=new PacedCoordinator(c,budget,()->{if(current.get()!=null)require(work.heartbeat(current.get()));},pause);
        var realEmbeddings=c.getBean(TextEmbeddingPort.class);var realMedia=c.getBean(MediaUnderstandingPort.class);var realStructured=c.getBean(StructuredKnowledgePort.class);
        TextEmbeddingPort embeddings=new TextEmbeddingPort(){
            public boolean available(){return realEmbeddings.available();}
            public List<float[]> embed(AiProcessingGate.SourceAiPermit permit,List<String> text){require(text.size()==1);
                return budget.call("document-embedding",estimate(text),0,()->realEmbeddings.embed(permit,text));}
        };
        var consent=new MediaQualityQualificationHarness.Consent(true,true,Math.min(12,limits.requests()),limits.estimatedBytes(),Math.min(12L*8192,limits.outputTokens()),15);
        try(var media=new MediaQualityQualificationHarness(directory.resolve("media"),consent,realMedia,clock)) {
            MediaUnderstandingPort meteredMedia=new MediaUnderstandingPort(){
                public boolean available(){return media.available();}
                public List<DerivedSegment> describe(AiProcessingGate.SourceAiPermit p,byte[] bytes,MediaUnderstandingRequest r){return budget.call("media",mediaEstimate(bytes,r),8192,()->media.describe(p,bytes,r));}
                public List<DerivedSegment> describeChild(AiProcessingGate.SourceAiPermit p,TransformedMediaIdentity child,byte[] original,byte[] bytes,MediaUnderstandingRequest r){return budget.call("media",mediaEstimate(bytes,r),8192,()->media.describeChild(p,child,original,bytes,r));}
            };
            var executor=new DerivationExecutor(c.getBean(AiProcessingGate.class),c.getBean(DerivationTransactions.class),work,provider(embeddings),provider(meteredMedia),c.getBean(io.micrometer.core.instrument.MeterRegistry.class),paced,configuration);
            var sources=c.getBean(PrivateDerivationSource.class);var policies=c.getBean(ProcessingPolicyService.class);
            for(var source:plan.sources()){
                var material=tx(c,()->sources.acquire(source.metadata().expected()));byte[] bytes;
                try(var input=material.open()){bytes=input.readNBytes((int)source.metadata().sizeBytes()+1);}
                require(TransformedMediaIdentity.digest(bytes).equals(source.sha256()));Arrays.fill(bytes,(byte)0);
                var lineage=EmbeddingLineage.create(configuration,policies.policyPrerequisite(plan.owner()).orElseThrow(),source.metadata().modality());
                tx(c,()->c.getBean(KnowledgeWorkRepository.class).enqueue(KnowledgeWork.Kind.ATTACHMENT,source.metadata().expected(),lineage.id()));
                var claim=work.claim(new LeaseOwner("synthetic-qualification"),10).stream().filter(v->v.intent().expected().equals(source.metadata().expected())).findFirst().orElseThrow();
                current.set(claim);executor.execute(claim);current.set(null);
                require("completed".equals(jdbc.queryForObject("select state from knowledge.knowledge_work_intent where knowledge_work_intent_id=?",String.class,claim.intent().id())));
            }
            StructuredKnowledgePort structured=new StructuredKnowledgePort(){
                public boolean available(){return realStructured.available();}
                public float[] embedQuery(KnowledgeQueryGate.QueryPermit p,String q){return budget.call("query-embedding",estimate(List.of(q)),0,()->realStructured.embedQuery(p,q));}
                public Output generate(KnowledgeQueryGate.EvidencePermit p,String q,String task,List<Evidence> evidence){return budget.call("generation",estimate(evidence.stream().map(Evidence::text).toList())+estimate(List.of(q)),8192,()->realStructured.generate(p,q,task,evidence));}
            };
            var engine=new KnowledgeQueryEngine(c.getBean(PrivateLexicalSearch.class),c.getBean(PrivateQuerySource.class),c.getBean(ExactPrivateVectorSearch.class),c.getBean(QueryEvidenceRepository.class),c.getBean(KnowledgeQueryGate.class),
                new ObjectProvider<StructuredKnowledgePort>(){public StructuredKnowledgePort getObject(){paced.waitBeforePermit();return structured;}},paced,c.getBeanProvider(PlatformTransactionManager.class));
            var controller=new KnowledgeQueryController(c.getBean(KnowledgeOperationService.class),engine,c.getBean(NoteKnowledgeService.class),
                c.getBean(io.micrometer.core.instrument.MeterRegistry.class),c.getBean(org.notesknowledge.security.RateControlService.class),
                c.getBean(org.notesknowledge.security.RateKeyDeriver.class),c.getBean(ObjectMapper.class),c.getBean(org.notesknowledge.websupport.ApiProblemWriter.class));
            var mvc=MockMvcBuilders.standaloneSetup(controller).addFilters(c.getBean(org.springframework.session.web.http.SessionRepositoryFilter.class))
                .apply(springSecurity(c.getBean("springSecurityFilterChain",jakarta.servlet.Filter.class))).build();
            var bootstrap=MockMvcBuilders.webAppContextSetup((org.springframework.web.context.WebApplicationContext)c)
                .addFilters(c.getBean(org.springframework.session.web.http.SessionRepositoryFilter.class)).apply(springSecurity()).build();
            var browser=browser(c,bootstrap,plan.owner());
            var results=new ArrayList<Map<String,Object>>();
            for(String query:plan.queries()){
                paced.waitBeforePermit();var response=mvc.perform(post("/api/knowledge/query").cookie(browser.cookie()).header("X-CSRF-TOKEN",browser.csrf())
                    .contentType("application/json").content(JSON.writeValueAsString(Map.of("query",query)))).andReturn().getResponse();
                require(response.getStatus()==200);var result=JSON.readValue(response.getContentAsByteArray(),KnowledgeQueryResult.class);
                var expected=plan.sources().stream().filter(s->query(s.fixture()).equals(query)).map(s->s.metadata().expected().attachmentId()).toList();
                long cited=expected.stream().filter(id->result.citations().stream().anyMatch(citation->id.equals(citation.attachmentId()))).count();
                results.add(Map.of("expectedSources",expected.size(),"expectedSourcesCited",cited,"validatedCitations",result.citations().size(),
                    "claims",result.aiAnswer()==null?0:result.aiAnswer().claims().size(),"degraded",Objects.toString(result.degraded(),"none"),"insufficientEvidence",result.insufficientEvidence(),
                    "claimFaithfulness","independent annotation required; citation validity is not entailment"));
            }
            QualificationJournal.forceCreate(directory.resolve("result.json"),JSON.writeValueAsBytes(Map.of("mode",mode,"budget",budget.totals(),"queries",results,
                "media",media.ledger(),"models",List.of(configuration.embeddingModel(),configuration.mediaModel()),"qualityQualified",false)));
            // A recorded provider failure is not successful execution merely because
            // the application truthfully returned a degraded 200 projection.
            require(!Boolean.TRUE.equals(budget.totals().get("failed")));
        } finally {current.set(null);SecurityContextHolder.clearContext();}
    }
    private static long estimate(List<String> text){return MediaUnderstandingRequest.ENVELOPE_RESERVE_BYTES+text.stream().mapToLong(t->6L*t.getBytes(StandardCharsets.UTF_8).length).sum();}
    private static long mediaEstimate(byte[] bytes,MediaUnderstandingRequest r){return 4L*((bytes.length+2L)/3)+estimate(List.of(GoogleDerivationAdapters.mediaInstruction(r),GoogleDerivationAdapters.mediaSchema()));}
    private static <T>ObjectProvider<T> provider(T value){return new ObjectProvider<>(){public T getObject(){return value;}};}
    /** No authority is mocked: wait before the real lock and subsequent production gate.
     * A lease is refreshed outside coordination; the actual gate still rejects lost authority. */
    private static final class PacedCoordinator extends DispatchCoordinator {
        private final DispatchCoordinator delegate;private final QualificationRequestBudget budget;private final Runnable heartbeat;private final java.util.function.Consumer<Duration> pause;
        PacedCoordinator(ConfigurableApplicationContext c,QualificationRequestBudget budget,Runnable heartbeat,java.util.function.Consumer<Duration> pause){super(c.getBeanProvider(javax.sql.DataSource.class));delegate=c.getBean(DispatchCoordinator.class);this.budget=budget;this.heartbeat=heartbeat;this.pause=pause;}
        void waitBeforePermit(){heartbeat.run();pause.accept(budget.delay());heartbeat.run();}
        @Override public Handle dispatch(UUID owner,UUID note){waitBeforePermit();return delegate.dispatch(owner,note);}
        @Override public Handle dispatchMany(UUID owner,Collection<UUID> notes){waitBeforePermit();return delegate.dispatchMany(owner,notes);}
    }
}
