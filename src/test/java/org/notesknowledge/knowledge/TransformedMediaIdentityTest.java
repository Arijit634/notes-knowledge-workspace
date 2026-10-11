package org.notesknowledge.knowledge;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.notesknowledge.knowledge.spi.PrivateAiSourceCurrentness;
import static org.assertj.core.api.Assertions.*;

@Tag("FAST") @Tag("SECURITY")
class TransformedMediaIdentityTest {
    static final String HASH="a".repeat(64);
    private final PrivateAiSourceCurrentness.Expected source=new PrivateAiSourceCurrentness.Expected(
        UUID.fromString("01999000-0000-7000-8000-000000000001"),UUID.fromString("01999000-0000-7000-8000-000000000002"),
        UUID.fromString("01999000-0000-7000-8000-000000000003"),1,2,3L);
    private TransformedMediaIdentity identity(String profile,TransformedMediaIdentity.Scope scope,byte[] bytes) {
        return new TransformedMediaIdentity(source,HASH,100,"video/mp4",profile,"9.0.2",HASH,HASH,
            TransformedMediaIdentity.digest(bytes),bytes.length,"video/mp4",scope,HASH,"gemini","gemini-3.8-flash","media-understanding",HASH);
    }
    private TransformedMediaIdentity child(){return identity("video-v1",new TransformedMediaIdentity.Scope("video_samples",0,20,1000,0,1000),new byte[]{1,2});}
    private AiDerivationProperties config(){return new AiDerivationProperties(true,false,"gemini","gemini-embedding-2","gemini-3.8-flash",
        "test-v1","global","unpaid","test-v1","test-v1",768,"cosine","none",HASH);}
    @Test void exactChildIdentityIsDeterministicAndDomainSeparatedFromSourceApproval() {
        assertThat(child().approvalFingerprint()).isEqualTo(child().approvalFingerprint()).matches("[0-9a-f]{64}")
            .isNotEqualTo(ProviderDispatchPolicy.fingerprint(source));
    }
    @Test void canonicalSerializationMatchesIndependentlyEncodedGoldenVector() {
        // Independently encoded with UTF-8 and unsigned four-byte big-endian lengths.
        assertThat(child().approvalFingerprint()).isEqualTo("6b666cf5c111851ebd104cc4e9be9ca05addb6775cffbaca600f826c60c43e34");
    }
    @Test void nullChildTypeFailsWithTheSafeBoundaryFailure() {
        var c=child();
        assertThatThrownBy(()->new TransformedMediaIdentity(source,HASH,100,"application/pdf","pdf-v1","v1",HASH,HASH,
            c.childSha256(),c.childBytes(),null,new TransformedMediaIdentity.Scope("pdf_pages",1,1,0,0,0),HASH,"gemini","model","media-understanding",HASH))
            .isInstanceOf(DerivationFailure.class).hasMessage("Derivation unavailable");
    }
    @Test void mutableVideoProfileFieldsContributeToTheApproval() {
        var c=child();var baseline=c.approvalFingerprint();
        // Fixed positional test data mirrors the documented serialization, not reflection.
        for(int field=0;field<17;field++) {
            var original=field==0?new PrivateAiSourceCurrentness.Expected(source.owner(),source.noteId(),source.attachmentId(),2,2,3L):source;
            var different=new TransformedMediaIdentity(original,field==1?"b".repeat(64):c.originalSha256(),
                field==2?101:c.originalBytes(),c.originalType(),field==3?"video-v2":c.profile(),
                field==4?"9.0.3":c.implementationVersion(),field==5?"b".repeat(64):c.binarySha256(),
                field==6?"b".repeat(64):c.configurationSha256(),field==7?"b".repeat(64):c.childSha256(),
                field==8?3:c.childBytes(),c.childType(),
                field==9?new TransformedMediaIdentity.Scope("video_samples",0,21,1000,0,1000):
                field==10?new TransformedMediaIdentity.Scope("video_samples",0,20,2000,0,1000):
                field==11?new TransformedMediaIdentity.Scope("video_samples",0,20,1000,1,1000):
                field==12?new TransformedMediaIdentity.Scope("video_samples",0,20,1000,0,1001):c.scope(),
                field==13?"b".repeat(64):c.lineage(),field==14?"other":c.provider(),field==15?"other":c.model(),c.task(),
                field==16?"b".repeat(64):c.policySha256());
            assertThat(different.approvalFingerprint()).as("canonical field %s",field).isNotEqualTo(baseline);
        }
    }
    @Test void eachOriginalIdentityAndGenerationIsBoundIndependently() {
        UUID different=UUID.fromString("01999000-0000-7000-8000-000000000004");
        var alternatives=List.of(
            new PrivateAiSourceCurrentness.Expected(different,source.noteId(),source.attachmentId(),1,2,3L),
            new PrivateAiSourceCurrentness.Expected(source.owner(),different,source.attachmentId(),1,2,3L),
            new PrivateAiSourceCurrentness.Expected(source.owner(),source.noteId(),different,1,2,3L),
            new PrivateAiSourceCurrentness.Expected(source.owner(),source.noteId(),source.attachmentId(),2,2,3L),
            new PrivateAiSourceCurrentness.Expected(source.owner(),source.noteId(),source.attachmentId(),1,3,3L),
            new PrivateAiSourceCurrentness.Expected(source.owner(),source.noteId(),source.attachmentId(),1,2,4L));
        var c=child();
        for(var original:alternatives) {
            var changed=new TransformedMediaIdentity(original,c.originalSha256(),c.originalBytes(),c.originalType(),c.profile(),
                c.implementationVersion(),c.binarySha256(),c.configurationSha256(),c.childSha256(),c.childBytes(),c.childType(),
                c.scope(),c.lineage(),c.provider(),c.model(),c.task(),c.policySha256());
            assertThat(changed.approvalFingerprint()).isNotEqualTo(c.approvalFingerprint());
        }
    }
    @Test void profileAndExactIntervalsChangeIdentity() {
        var changedProfile=identity("video-v2",child().scope(),new byte[]{1,2});
        var changedInterval=identity("video-v1",new TransformedMediaIdentity.Scope("video_samples",20,40,1000,1000,2000),new byte[]{1,2});
        assertThat(child().approvalFingerprint()).isNotEqualTo(changedProfile.approvalFingerprint()).isNotEqualTo(changedInterval.approvalFingerprint());
    }
    @Test void exactBytesAndLengthAreMandatory() {
        child().requireBytes(new byte[]{1,2});
        for(byte[] wrong:new byte[][]{new byte[]{1,3},new byte[]{1},new byte[]{}})
            assertThatThrownBy(()->child().requireBytes(wrong)).isInstanceOf(DerivationFailure.class);
        assertThatThrownBy(()->child().requireBytes(null)).isInstanceOf(DerivationFailure.class);
    }
    @Test void originalApprovalDoesNotApproveTheChild() {
        var policy=new ProviderDispatchPolicy(new ProviderDispatchProperties("unpaid-synthetic-demo",List.of(ProviderDispatchPolicy.fingerprint(source))));
        assertThat(policy.permitsUnpaidGemini(config(),source)).isTrue();
        assertThat(policy.permitsUnpaidGeminiChild(config(),child())).isFalse();
    }
    @Test void childApprovalDoesNotApproveTheOriginal() {
        var policy=new ProviderDispatchPolicy(new ProviderDispatchProperties("unpaid-synthetic-demo",List.of(),List.of(),List.of(child().approvalFingerprint())));
        assertThat(policy.permitsUnpaidGeminiChild(config(),child())).isFalse();
    }
    @Test void bothApprovalsAreRequiredAndRevokedConfigurationDeniesNextCheck() {
        var policy=new ProviderDispatchPolicy(new ProviderDispatchProperties("unpaid-synthetic-demo",List.of(ProviderDispatchPolicy.fingerprint(source)),List.of(),List.of(child().approvalFingerprint())));
        assertThat(policy.permitsUnpaidGeminiChild(config(),child())).isTrue();
        // Replacing configuration is permitted only after global coordination/quiescence.
        var revoked=new ProviderDispatchPolicy(new ProviderDispatchProperties("unpaid-synthetic-demo",List.of(ProviderDispatchPolicy.fingerprint(source))));
        assertThat(revoked.permitsUnpaidGeminiChild(config(),child())).isFalse();
    }
    @Test void unknownOrOutOfBoundsScopesAreRejected() {
        assertThatThrownBy(()->new TransformedMediaIdentity.Scope("arbitrary",0,1,1,0,1)).isInstanceOf(DerivationFailure.class);
        assertThatThrownBy(()->new TransformedMediaIdentity.Scope("video_samples",1,1,1,0,1)).isInstanceOf(DerivationFailure.class);
        assertThatThrownBy(()->new TransformedMediaIdentity.Scope("video_samples",0,1,1,0,301)).isInstanceOf(DerivationFailure.class);
        assertThatThrownBy(()->new TransformedMediaIdentity.Scope("pdf_pages",1,101,0,0,0)).isInstanceOf(DerivationFailure.class);
    }
    @Test void pdfOrdinalsDoNotAliasVideoTicks() {
        var pdf=new TransformedMediaIdentity.Scope("pdf_pages",1,1,0,0,0);
        assertThat(pdf).isNotEqualTo(child().scope());
    }
    @Test void childAllowlistHasTheSameStrictBoundAndHashFormat() {
        assertThatThrownBy(()->new ProviderDispatchPolicy(new ProviderDispatchProperties("unpaid-synthetic-demo",List.of(),List.of(),List.of("invalid"))))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid provider dispatch policy configuration");
        assertThatThrownBy(()->new ProviderDispatchPolicy(new ProviderDispatchProperties("unpaid-synthetic-demo",List.of(),List.of(),java.util.Collections.nCopies(257,HASH))))
            .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void diagnosticsNeverExposeIdentitiesDigestsOrScopes() {
        assertThat(child().toString()).isEqualTo("TransformedMediaIdentity[REDACTED]");
        assertThat(child().scope().toString()).isEqualTo("TransformedMediaScope[REDACTED]");
    }
}
