package org.notesknowledge.knowledge;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.notesknowledge.knowledge.spi.PrivateAiSourceCurrentness.Expected;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

@Tag("FAST") @Tag("SECURITY")
class ProviderDispatchPolicyTest {
    private final UUID owner=new UUID(0,1),note=new UUID(0,2),attachment=new UUID(0,3);
    private final Expected source=new Expected(owner,note,null,1,1,null);
    @Test void canonicalFingerprintHasVersionedUnambiguousFieldsAndChangesWithEveryAuthorityField() throws Exception {
        String canonical="nkw-private-source-v1\n00000000-0000-0000-0000-000000000001\n00000000-0000-0000-0000-000000000002\n-\n1\n1\n-";
        assertThat(ProviderDispatchPolicy.fingerprint(source)).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8))));
        var media=new Expected(owner,note,attachment,1,1,1L);
        var changes=List.of(new Expected(new UUID(0,4),note,attachment,1,1,1L),new Expected(owner,new UUID(0,4),attachment,1,1,1L),
            new Expected(owner,note,new UUID(0,4),1,1,1L),new Expected(owner,note,attachment,2,1,1L),
            new Expected(owner,note,attachment,1,2,1L),new Expected(owner,note,attachment,1,1,2L));
        assertThat(ProviderDispatchPolicy.fingerprint(media)).isNotEqualTo(ProviderDispatchPolicy.fingerprint(source));
        assertThat(changes).allSatisfy(e->assertThat(ProviderDispatchPolicy.fingerprint(e)).isNotEqualTo(ProviderDispatchPolicy.fingerprint(media)));
    }
    @Test void operatorPropertiesBindWithoutChangingAiConfigurationAndRedactTheirRepresentation() {
        String hash=ProviderDispatchPolicy.fingerprint(source);
        var environment=new MockEnvironment().withProperty("knowledge.derivation.dispatch-policy","unpaid-synthetic-demo")
            .withProperty("knowledge.derivation.approved-source-fingerprints[0]",hash)
            .withProperty("knowledge.derivation.approved-query-fingerprints[0]",ProviderDispatchPolicy.queryFingerprint("Synthetic query"));
        var p=Binder.get(environment).bind("knowledge.derivation",Bindable.of(ProviderDispatchProperties.class)).get();
        assertThat(new ProviderDispatchPolicy(p).permits(config("gemini","unpaid","global"),source,"note")).isTrue();
        assertThat(new ProviderDispatchPolicy(p).permitsUnpaidGeminiQuery(config("gemini","unpaid","global"),"Synthetic query")).isTrue();
        assertThat(p.toString()).doesNotContain(hash,owner.toString(),note.toString());
    }
    @Test void missingPolicyOrEmptyCorpusDeniesAndOwnerHashIsNotAnExactSourceApproval() {
        for(var p:List.of(new ProviderDispatchProperties(null,null),new ProviderDispatchProperties("unpaid-synthetic-demo",List.of()),
                new ProviderDispatchProperties(null,List.of(ProviderDispatchPolicy.fingerprint(source))),
                new ProviderDispatchProperties("unpaid-synthetic-demo",List.of("a".repeat(64)))))
            assertThat(new ProviderDispatchPolicy(p).permits(config("gemini","unpaid","global"),source,"note")).isFalse();
    }
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={"gemini","groq","synthetic"})
    void providerTierRegionAndTaskCannotBeUpgradedBySourceApproval(String provider) {
        var policy=approved(source);
        assertThat(policy.permits(config(provider,"paid","global"),source,"note")).isFalse();
        assertThat(policy.permits(config("gemini","unpaid","other"),source,"note")).isFalse();
        assertThat(policy.permits(config("gemini","unpaid","global"),source,"query")).isFalse();
    }
    @ParameterizedTest @ValueSource(strings={"bad","Aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","", "a"})
    void malformedFingerprintsAreRejectedWithoutEchoingTheRejectedValue(String value) {
        assertThatThrownBy(()->new ProviderDispatchPolicy(new ProviderDispatchProperties("unpaid-synthetic-demo",List.of(value))))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid provider dispatch policy configuration");
    }
    @Test void boundedCorpusRejectsOverflowAndUnknownPolicy() {
        assertThatThrownBy(()->new ProviderDispatchPolicy(new ProviderDispatchProperties("unpaid-synthetic-demo",Collections.nCopies(257,"a".repeat(64)))))
            .hasMessage("Invalid provider dispatch policy configuration");
        assertThatThrownBy(()->new ProviderDispatchPolicy(new ProviderDispatchProperties("allow-all",List.of())))
            .hasMessage("Invalid provider dispatch policy configuration");
        assertThat(new ProviderDispatchPolicy(new ProviderDispatchProperties("unpaid-synthetic-demo",Collections.nCopies(256,ProviderDispatchPolicy.fingerprint(source))))
            .permits(config("gemini","unpaid","global"),source,"note")).isTrue();
    }
    @Test void syntheticProviderExceptionNeverProvesGoogleDispatch() {
        var policy=new ProviderDispatchPolicy(new ProviderDispatchProperties(null,null));
        assertThat(policy.permits(config("synthetic","synthetic","synthetic"),source,"note")).isTrue();
        assertThat(policy.permitsUnpaidGemini(config("synthetic","synthetic","synthetic"),source)).isFalse();
        assertThat(policy.permits(config("gemini","synthetic","global"),source,"note")).isFalse();
    }
    @Test void exactApprovalDoesNotApproveAnotherSourceForTheSameOwner() {
        assertThat(approved(source).permits(config("gemini","unpaid","global"),new Expected(owner,new UUID(0,4),null,1,1,null),"note")).isFalse();
    }
    private ProviderDispatchPolicy approved(Expected e){return new ProviderDispatchPolicy(new ProviderDispatchProperties("unpaid-synthetic-demo",List.of(ProviderDispatchPolicy.fingerprint(e))));}
    private AiDerivationProperties config(String provider,String tier,String region){
        var c=mock(AiDerivationProperties.class);when(c.provider()).thenReturn(provider);when(c.tier()).thenReturn(tier);when(c.region()).thenReturn(region);return c;
    }
}
