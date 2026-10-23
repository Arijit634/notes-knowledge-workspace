package org.notesknowledge.knowledge;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

/** Synthetic deployment snapshots, not an integrity bypass: every lookup invokes the real resolver. */
public final class ProcessingDisclosureFixtures {
    private final Map<UUID,ProcessingDisclosureResolver> snapshots=new HashMap<>();
    private final AiDerivationProperties configuration;
    ProcessingDisclosureFixtures(ProcessingDisclosureResolver replacement,AiDerivationProperties configuration) {
        this.configuration=configuration;
        doAnswer(call->{ProcessingPolicyRepository.Policy p=call.getArgument(0);var resolver=snapshots.get(p.id());
            if(resolver==null)throw ProcessingDisclosure.unavailable();return resolver.require(p);}).when(replacement).require(any());
    }
    UUID insert(JdbcTemplate jdbc,String code,long version,boolean alignProviderFingerprint) {
        UUID id=jdbc.queryForObject("select uuidv7()",UUID.class);
        Instant at=jdbc.queryForObject("select clock_timestamp()-interval '1 minute'",java.sql.Timestamp.class).toInstant();
        String provider=configuration.provider()==null?"synthetic":configuration.provider();
        String tier=configuration.tier()==null?"synthetic":configuration.tier();
        String region=configuration.region()==null?"synthetic":configuration.region();
        var d=ProcessingDisclosure.decode(bundle(id,code,version,"synthetic-1",at,provider,tier,region));
        jdbc.update("insert into knowledge.processing_policy(processing_policy_id,policy_code,policy_version,policy_fingerprint,disclosure_revision,effective_at) values(?,?,?,?,?,?)",
            id,code,version,d.fingerprint(),d.revision(),java.sql.Timestamp.from(at));
        snapshots.put(id,new ProcessingDisclosureResolver(properties(d),configuration,d));
        if(alignProviderFingerprint)when(configuration.approvedPolicyFingerprint()).thenReturn(d.fingerprint());
        return id;
    }
    static ProcessingDisclosureProperties properties(ProcessingDisclosure d){return new ProcessingDisclosureProperties(null,d.fingerprint(),d.provider(),d.tier(),d.region(),d.categories());}
    void remove(UUID id){snapshots.remove(id);}
    /** Test-only no-network deployment fixture; never called by application code. */
    public static UUID install(JdbcTemplate jdbc,java.nio.file.Path file,String code,String provider,String tier,String region,
            Map<String,String> settings)throws java.io.IOException {
        var d=file(jdbc,file,code,provider,tier,region);deployment(d,file,settings);
        jdbc.update("insert into knowledge.processing_policy(processing_policy_id,policy_code,policy_version,policy_fingerprint,disclosure_revision,effective_at) values(?,?,?,?,?,?)",
            d.id(),d.code(),d.version(),d.fingerprint(),d.revision(),java.sql.Timestamp.from(d.effectiveAt()));
        return d.id();
    }
    static ProcessingDisclosure file(JdbcTemplate jdbc,java.nio.file.Path file,String code,String provider,String tier,String region)throws java.io.IOException {
        if(!java.nio.file.Files.exists(file)) {
            UUID id=jdbc.queryForObject("select uuidv7()",UUID.class);
            Instant at=jdbc.queryForObject("select clock_timestamp()-interval '1 minute'",java.sql.Timestamp.class).toInstant();
            java.nio.file.Files.write(file,bundle(id,code,1,"synthetic-1",at,provider,tier,region),java.nio.file.StandardOpenOption.CREATE_NEW);
        }
        return ProcessingDisclosure.decode(java.nio.file.Files.readAllBytes(file));
    }
    static void deployment(ProcessingDisclosure d,java.nio.file.Path file,Map<String,String> settings) {
        settings.put("knowledge.processing-disclosure.path",file.toString());
        settings.put("knowledge.processing-disclosure.approved-fingerprint",d.fingerprint());
        settings.put("knowledge.processing-disclosure.provider",d.provider());settings.put("knowledge.processing-disclosure.tier",d.tier());
        settings.put("knowledge.processing-disclosure.region",d.region());settings.put("knowledge.processing-disclosure.categories",String.join(",",d.categories()));
    }
    static byte[] bundle(UUID id,String code,long version,String revision,Instant at,String provider,String tier,String region) {
        Map<String,Object> fields=new LinkedHashMap<>();
        fields.put("schemaVersion",1);fields.put("processingPolicyId",id.toString());fields.put("policyCode",code);
        fields.put("policyVersion",version);fields.put("disclosureRevision",revision);fields.put("effectiveAt",at.toString());
        fields.put("provider",provider);fields.put("tier",tier);fields.put("region",region);
        fields.put("providerProcessingCategories",ProcessingDisclosure.TASKS);
        fields.put("disclosureContent","SYNTHETIC TEST FIXTURE ONLY. No approved real provider disclosure.\nNo network processing in this test.");
        return JsonMapper.builder().build().writeValueAsString(fields).getBytes(StandardCharsets.UTF_8);
    }
}
