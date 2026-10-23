package org.notesknowledge.knowledge;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.notesknowledge.websupport.ApiFailureException;

@Tag("FAST") @Tag("SECURITY") @Tag("API")
class ProcessingDisclosureTest {
    @TempDir Path temp;
    static final UUID ID=UUID.fromString("01990a30-e254-70b3-a1d7-0763c4de2201");
    static final Instant AT=Instant.parse("2026-10-23T00:00:00Z");
    private byte[] valid(){return ProcessingDisclosureFixtures.bundle(ID,"synthetic-test",1,"synthetic-1",AT,"synthetic","synthetic","synthetic");}
    private ProcessingPolicyRepository.Policy policy(ProcessingDisclosure d){return new ProcessingPolicyRepository.Policy(d.id(),d.code(),d.version(),d.fingerprint(),d.revision(),d.effectiveAt());}
    private void denied(Runnable work){assertThatThrownBy(work::run).isInstanceOfSatisfying(ApiFailureException.class,e->assertThat(e.kind()).isEqualTo(ApiFailureException.Kind.SERVICE_UNAVAILABLE));}

    @Test void exactFramedDigestAndJsonEscapingAreStable() {
        var d=ProcessingDisclosure.decode(valid());
        // Independently framed with PowerShell/.NET UTF-8 + SHA-256, not this encoder.
        assertThat(d.fingerprint()).isEqualTo("25ad3e180b76cd302e71d746d9ca2b9f75cef2d5fdfd78bdbb407d86b183263b");
        String escaped=new String(valid(),StandardCharsets.UTF_8).replace("TEST","\\u0054EST");
        assertThat(ProcessingDisclosure.decode(escaped.getBytes(StandardCharsets.UTF_8)).fingerprint()).isEqualTo(d.fingerprint());
        assertThat(d.toString()).doesNotContain(d.content(),ID.toString());
    }
    @ParameterizedTest @ValueSource(strings={"missing","tampered","identity","version","revision","effective","provider","tier","region","categories","approval"})
    void unapprovedOrMisboundDisclosureFailsClosed(String change)throws Exception {
        var d=ProcessingDisclosure.decode(valid());var p=policy(d);var config=mock(AiDerivationProperties.class);
        var props=ProcessingDisclosureFixtures.properties(d);
        switch(change) {
            case "missing"->{var initialProps=props;var initialPolicy=p;denied(()->new ProcessingDisclosureResolver(initialProps,config,null).require(initialPolicy));return;}
            case "identity"->p=new ProcessingPolicyRepository.Policy(new UUID(0,1),p.code(),p.version(),p.fingerprint(),p.disclosureRevision(),p.effectiveAt());
            case "version"->p=new ProcessingPolicyRepository.Policy(p.id(),p.code(),2,p.fingerprint(),p.disclosureRevision(),p.effectiveAt());
            case "revision"->p=new ProcessingPolicyRepository.Policy(p.id(),p.code(),1,p.fingerprint(),"other",p.effectiveAt());
            case "effective"->p=new ProcessingPolicyRepository.Policy(p.id(),p.code(),1,p.fingerprint(),p.disclosureRevision(),AT.plusSeconds(1));
            case "tampered"->d=ProcessingDisclosure.decode(new String(valid(),StandardCharsets.UTF_8).replace("TEST FIXTURE","CHANGED FIXTURE").getBytes(StandardCharsets.UTF_8));
            case "provider"->props=new ProcessingDisclosureProperties(null,props.approvedFingerprint(),"other",props.tier(),props.region(),props.categories());
            case "tier"->props=new ProcessingDisclosureProperties(null,props.approvedFingerprint(),props.provider(),"other",props.region(),props.categories());
            case "region"->props=new ProcessingDisclosureProperties(null,props.approvedFingerprint(),props.provider(),props.tier(),"other",props.categories());
            case "categories"->props=new ProcessingDisclosureProperties(null,props.approvedFingerprint(),props.provider(),props.tier(),props.region(),java.util.List.of("queryEmbedding"));
            case "approval"->props=new ProcessingDisclosureProperties(null,null,props.provider(),props.tier(),props.region(),props.categories());
            default->throw new AssertionError();
        }
        var resolver=new ProcessingDisclosureResolver(props,config,d);var expected=p;denied(()->resolver.require(expected));
    }
    @Test void contentLimitCountsUtf8BytesRatherThanUtf16CodeUnits(){
        var json=tools.jackson.databind.json.JsonMapper.builder().build();
        var root=(tools.jackson.databind.node.ObjectNode)json.readTree(valid());
        root.put("disclosureContent","é".repeat(16384));
        assertThat(ProcessingDisclosure.decode(json.writeValueAsBytes(root)).content()).hasSize(16384);
        root.put("disclosureContent","é".repeat(16385));denied(()->ProcessingDisclosure.decode(json.writeValueAsBytes(root)));
    }
    @ParameterizedTest @ValueSource(strings={"duplicate","unknown","trailing","schema","schemaOverflow","fraction","revision","uuid","time","empty","control","surrogate","categories","duplicateCategory","unsorted","largeContent","largeFile","bom","utf8"})
    void malformedAndAmbiguousBundlesAreRejected(String mode) {
        String text=new String(valid(),StandardCharsets.UTF_8);
        text=switch(mode) {
            case "duplicate"->text.replace("\"schemaVersion\":1","\"schemaVersion\":1,\"schemaVersion\":1");
            case "unknown"->text.replace("\"schemaVersion\":1","\"schemaVersion\":1,\"unknown\":true");
            case "trailing"->text+"{}";
            case "schema"->text.replace("\"schemaVersion\":1","\"schemaVersion\":2");
            case "schemaOverflow"->text.replace("\"schemaVersion\":1","\"schemaVersion\":4294967297");
            case "fraction"->text.replace("\"policyVersion\":1","\"policyVersion\":1.0");
            case "revision"->text.replace("synthetic-1","bad revision");
            case "uuid"->text.replace(ID.toString(),"01990A30-e254-70b3-a1d7-0763c4de2201");
            case "time"->text.replace("2026-10-23T00:00:00Z","2026-10-23T00:00:00+00:00");
            case "empty"->text.replaceAll("\"disclosureContent\":\"[^\"]*\"","\"disclosureContent\":\" \"");
            case "control"->text.replace("SYNTHETIC TEST","SYNTHETIC\\rTEST");
            case "surrogate"->text.replace("SYNTHETIC TEST","\\uD800");
            case "categories"->text.replace("queryEmbedding","unknownTask");
            case "duplicateCategory"->text.replace("queryEmbedding","sourceEmbedding");
            case "unsorted"->text.replace("groundedGeneration","zzz").replace("structuredExtraction","groundedGeneration").replace("zzz","structuredExtraction");
            case "largeContent"->text.replace("SYNTHETIC TEST", "x".repeat(32769));
            case "largeFile"->" ".repeat(65537);
            case "bom"->"\ufeff"+text;
            default->text;
        };
        byte[] bytes=mode.equals("utf8")?new byte[]{(byte)0xc3,(byte)0x28}:text.getBytes(StandardCharsets.UTF_8);
        denied(()->ProcessingDisclosure.decode(bytes));
    }
    @Test void fileIsBoundedLocalAndSnapshotIsImmutable()throws Exception {
        var d=ProcessingDisclosure.decode(valid());var config=mock(AiDerivationProperties.class);
        Path file=temp.resolve("synthetic.json");Files.write(file,valid());
        var props=new ProcessingDisclosureProperties(file.toString(),d.fingerprint(),d.provider(),d.tier(),d.region(),d.categories());
        var resolver=new ProcessingDisclosureResolver(props,config);
        assertThat(resolver.require(policy(d))).isEqualTo(d);
        Files.writeString(file,"invalid modified file");
        assertThat(resolver.require(policy(d))).isEqualTo(d);
        denied(()->new ProcessingDisclosureResolver(props,config).require(policy(d)));
        var missing=new ProcessingDisclosureProperties(temp.resolve("absent").toString(),d.fingerprint(),d.provider(),d.tier(),d.region(),d.categories());
        denied(()->new ProcessingDisclosureResolver(missing,config).require(policy(d)));
    }
}
