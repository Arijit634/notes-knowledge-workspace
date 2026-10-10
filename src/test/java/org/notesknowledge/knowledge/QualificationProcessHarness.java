package org.notesknowledge.knowledge;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.notesknowledge.Application;
import org.notesknowledge.LeaseOwner;
import org.notesknowledge.knowledge.spi.PrivateAiSourceCurrentness.Expected;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import static org.mockito.Mockito.*;

/** Offline process-death simulation. ALL provider ports are replaced; no live mode exists. */
public final class QualificationProcessHarness {
    private static final ObjectMapper JSON=new ObjectMapper();
    public static void main(String[] args) {
        ((ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger("ROOT")).setLevel(ch.qos.logback.classic.Level.OFF);
        int exit=0;
        try {
            Path directory=Path.of(args[1]);Files.createDirectories(directory);
            var approvals=new ArrayList<String>();var now=new AtomicReference<>(Instant.now());var calls=new AtomicInteger();
            var ordinal=new AtomicInteger();var label=new AtomicReference<String>();var journal=new AtomicReference<QualificationJournal>();
            var properties=new LinkedHashMap<String,Object>();
            properties.put("spring.datasource.url",args[0]);properties.put("spring.datasource.username","synthetic_migrator");properties.put("spring.datasource.password","synthetic-restart-password");
            properties.put("server.port","0");properties.put("server.address","127.0.0.1");properties.put("spring.main.banner-mode","off");properties.put("logging.level.root","OFF");
            properties.put("identity.rate.key-base64","AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=");
            properties.put("notes.cursor.active.version","synthetic1");properties.put("notes.cursor.active.key-base64","AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=");
            properties.put("knowledge.derivation.enabled","true");properties.put("knowledge.derivation.scheduling-enabled","false");properties.put("knowledge.operations.scheduling-enabled","false");
            properties.put("knowledge.processing-policy.code","offline-restart");
            properties.put("knowledge.derivation.provider","gemini");properties.put("knowledge.derivation.tier","unpaid");properties.put("knowledge.derivation.region","global");
            properties.put("knowledge.derivation.embedding-model","gemini-embedding-2");properties.put("knowledge.derivation.media-model","gemini-3.8-flash");
            properties.put("knowledge.derivation.model-revision","offline-v1");properties.put("knowledge.derivation.configuration-id","offline-v1");properties.put("knowledge.derivation.adapter-version","google-v2");
            properties.put("knowledge.derivation.dimension","768");properties.put("knowledge.derivation.operator","cosine");properties.put("knowledge.derivation.normalization","unit");properties.put("knowledge.derivation.approved-policy-fingerprint","a".repeat(64));
            BeanPostProcessor fake=new BeanPostProcessor(){public Object postProcessAfterInitialization(Object bean,String name){
                if(bean instanceof ProviderDispatchProperties) {
                    var p=mock(ProviderDispatchProperties.class);when(p.dispatchPolicy()).thenReturn("unpaid-synthetic-demo");when(p.approvedSourceFingerprints()).thenAnswer(c->List.copyOf(approvals));return p;
                }
                if(bean instanceof TextEmbeddingPort)return new TextEmbeddingPort(){public boolean available(){return true;}
                    public List<float[]> embed(AiProcessingGate.SourceAiPermit permit,List<String> texts) {
                        permit.requireGoogleDispatch();if(texts.size()!=1)throw new AssertionError("One request per chunk required");
                        String key=QualificationJournal.hash(ProviderDispatchPolicy.fingerprint(permit.source().expected())+permit.lineage().id()+"/"+ordinal.getAndIncrement()+"/"+texts.getFirst());
                        try {
                            float[] result=journal.get().completed(key,float[].class);if(result!=null)return List.of(result);
                            var reservation=journal.get().reserve(key,label.get(),"embedding");result=new float[768];result[0]=1;calls.incrementAndGet();
                            journal.get().complete(reservation,result);now.set(now.get().plusSeconds(6));
                            if(args[2].equals("interrupt")&&label.get().equals("n76"))Runtime.getRuntime().halt(77);
                            return List.of(result);
                        }catch(java.io.IOException failure){throw new IllegalStateException("Offline journal failure",failure);}
                    }};
                if(bean instanceof StructuredKnowledgePort)return new StructuredKnowledgePort(){public boolean available(){return false;}
                    public float[] embedQuery(KnowledgeQueryGate.QueryPermit p,String q){throw new AssertionError("No network");}
                    public Output generate(KnowledgeQueryGate.EvidencePermit p,String q,String t,List<Evidence> e){throw new AssertionError("No network");}};
                if(bean instanceof MediaUnderstandingPort)return new MediaUnderstandingPort(){public boolean available(){return false;}
                    public List<DerivedSegment> describe(AiProcessingGate.SourceAiPermit p,byte[] b,String t){throw new AssertionError("No network");}};
                return bean;
            }};
            try(var context=new SpringApplicationBuilder(Application.class).properties(properties).initializers(c->c.getBeanFactory().addBeanPostProcessor(fake)).run()) {
                var jdbc=context.getBean(JdbcTemplate.class);Path map=directory.resolve("sources.json");UUID owner;var ids=new LinkedHashMap<String,UUID>();
                if(!Files.exists(map)) {
                    if(jdbc.queryForObject("select count(*) from identity.account",Integer.class)!=0)throw new AssertionError("Unrecognized database");
                    owner=jdbc.queryForObject("insert into identity.account(user_id,canonical_email,display_email,email_verified_at,account_state,created_at,updated_at) values(uuidv7(),'restart@example.test','restart@example.test',now(),'active',now(),now()) returning user_id",UUID.class);
                    for(var n:FrozenQualityCorpus.notes()) {
                        UUID id=jdbc.queryForObject("select uuidv7()",UUID.class);ids.put(n.id(),id);
                        jdbc.update("insert into notes.note(note_id,owner_user_id,title,markdown,lifecycle_state,ai_enabled,revision,ai_generation,created_at,updated_at) values(?,?,?,?,'active',?,1,1,now(),now())",id,owner,n.title(),n.body(),n.aiEnabled());
                    }
                    UUID policy=jdbc.queryForObject("insert into knowledge.processing_policy(policy_code,policy_version,policy_fingerprint,disclosure_revision,effective_at) values('offline-restart',1,?,'synthetic-1',clock_timestamp()-interval '1 minute') returning processing_policy_id",UUID.class,"a".repeat(64));
                    jdbc.update("insert into knowledge.processing_policy_acknowledgement(user_id,processing_policy_id,disclosure_revision) values(?,?,'synthetic-1')",owner,policy);
                    QualificationJournal.forceCreate(map,JSON.writeValueAsBytes(Map.of("owner",owner,"notes",ids)));
                }else {
                    var state=JSON.readTree(Files.readAllBytes(map));owner=UUID.fromString(state.get("owner").asText());
                    for(var n:FrozenQualityCorpus.notes())ids.put(n.id(),UUID.fromString(state.get("notes").get(n.id()).asText()));
                }
                UUID policy=jdbc.queryForObject("select processing_policy_id from knowledge.processing_policy where policy_code='offline-restart'",UUID.class);
                var lineage=EmbeddingLineage.create(context.getBean(AiDerivationProperties.class),new ProcessingPolicyService.AcknowledgedProcessingPolicy(policy,1,"a".repeat(64)),"note");
                var work=context.getBean(KnowledgeWorkService.class);var repository=context.getBean(PrivateRepresentationRepository.class);var tx=new TransactionTemplate(context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
                try(var state=new QualificationJournal(directory.resolve("journal"),"offline-process/"+policy+"/"+lineage.id());var clock=mockStatic(Instant.class,CALLS_REAL_METHODS)) {
                    journal.set(state);clock.when(Instant::now).thenAnswer(c->now.get());
                    for(var n:FrozenQualityCorpus.notes())if(n.aiEnabled())approvals.add(ProviderDispatchPolicy.fingerprint(new Expected(owner,ids.get(n.id()),null,1,1,null)));
                    int reused=0;
                    for(var n:FrozenQualityCorpus.notes())if(n.aiEnabled()) {
                        label.set(n.id());ordinal.set(0);var expected=new Expected(owner,ids.get(n.id()),null,1,1,null);
                        if(repository.ready(expected,lineage)){reused++;continue;}
                        if(args[2].equals("verify"))throw new AssertionError("Ready representation lost");
                        tx.execute(s->context.getBean(KnowledgeWorkRepository.class).enqueue(KnowledgeWork.Kind.NOTE,expected,lineage.id()));
                        var claims=work.claim(new LeaseOwner("synthetic-process-worker"),10);
                        if(claims.isEmpty())claims=work.reclaim(new LeaseOwner("synthetic-process-worker"),10);
                        var claim=claims.stream().filter(c->c.intent().expected().equals(expected)).findFirst().orElseThrow();
                        context.getBean(DerivationExecutor.class).execute(claim);
                        if(!repository.ready(expected,lineage))throw new AssertionError("Derivation not ready");
                    }
                    float[] query=new float[768];query[0]=1;
                    var candidates=context.getBean(ExactPrivateVectorSearch.class).search(owner,"note",query,100);
                    if(candidates.isEmpty())throw new AssertionError("Persisted vector scoring failed");
                    int expected=FrozenQualityCorpus.notes().stream().filter(FrozenQualityCorpus.Note::aiEnabled).mapToInt(n->new MarkdownChunker().chunk(n.body()).size()).sum();
                    if(state.reservations("embedding")!=expected)throw new AssertionError("Lost or duplicated request accounting");
                    Files.writeString(directory.resolve(args[2]+".json"),JSON.writeValueAsString(Map.of("readyRoots",80,"segments",expected,"newFakeCalls",calls.get(),"reusedReadyRoots",reused,"reservations",state.reservations("embedding"),"liveCalls",0,"persistedVectorCandidates",candidates.size())));
                }
            }
        }catch(Throwable failure){System.out.println("Offline process probe failed: "+failure.getClass().getSimpleName());exit=1;}
        System.exit(exit);
    }
    private QualificationProcessHarness(){ }
}
