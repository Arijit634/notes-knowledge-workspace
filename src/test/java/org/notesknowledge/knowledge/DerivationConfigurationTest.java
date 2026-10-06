package org.notesknowledge.knowledge;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.ObjectMapper;

@Tag("FAST") @Tag("SECURITY") @Tag("EVALUATION")
class DerivationConfigurationTest {
    @Test void unconfiguredEnabledDeploymentHasNoImplicitProviderOrModels() {
        var c=new AiDerivationProperties(true,false,null,null,null,null,null,null,null,null,null,null,null,null);
        assertThat(c.configured()).isFalse();var adapters=new GoogleDerivationAdapters();
        assertThat(adapters.derivationGoogleClient(c,new MockEnvironment())).isNull();
        @SuppressWarnings("unchecked") ObjectProvider<com.google.genai.Client> client=mock(ObjectProvider.class);
        assertThat(adapters.googleTextEmbeddingPort(c,client).available()).isFalse();
        assertThat(adapters.googleMediaUnderstandingPort(c,client,new ObjectMapper()).available()).isFalse();
    }
    @Test void explicitLineageIsStableAndCompatibilityChangesCreateAnotherIdentity() {
        var policy=new ProcessingPolicyService.AcknowledgedProcessingPolicy(new UUID(0,1),1,"a".repeat(64));
        var c=config("none","synthetic-v1");assertThat(c.configured()).isTrue();
        var original=EmbeddingLineage.create(c,policy,"note");
        assertThat(EmbeddingLineage.create(c,policy,"note")).isEqualTo(original);
        assertThat(EmbeddingLineage.create(config("none","synthetic-v2"),policy,"note").id()).isNotEqualTo(original.id());
        assertThat(EmbeddingLineage.create(c,policy,"image").id()).isNotEqualTo(original.id());
        assertThat(EmbeddingLineage.create(c,new ProcessingPolicyService.AcknowledgedProcessingPolicy(new UUID(0,2),2,"a".repeat(64)),"note").id()).isNotEqualTo(original.id());
        assertThat(original.configuration()).contains("float32","markdown-weighted","media-typed","synthetic-embedding","synthetic-media");
    }
    @Test void normalizationUsesFreshFiniteNonzeroVectorsAndNeverRelabelsThem() {
        var p=new ProcessingPolicyService.AcknowledgedProcessingPolicy(new UUID(0,1),1,"a".repeat(64));
        var lineage=EmbeddingLineage.create(config("unit","synthetic-v1"),p,"note");
        float[] original={3,4};assertThat(lineage.prepare(original)).containsExactly(0.6f,0.8f);assertThat(original).containsExactly(3,4);
        for(float[] bad:new float[][]{{0,0},{Float.NaN,1},{Float.POSITIVE_INFINITY,1},{1}})
            assertThatThrownBy(()->lineage.prepare(bad)).isInstanceOf(DerivationFailure.class);
    }
    @Test void malformedRegionAndOutOfBudgetOffsetsFailBeforePersistence() {
        assertThatThrownBy(()->new DerivedSegment("synthetic","image_region","",null,null,1,null,null,0d,0d,0.5d,0.5d))
                .isInstanceOf(DerivationFailure.class);
        assertThatThrownBy(()->new DerivedSegment("synthetic","image_region","",null,null,null,0d,1d,0d,0d,0.5d,0.5d))
                .isInstanceOf(DerivationFailure.class);
        assertThatThrownBy(()->DerivedSegment.note("synthetic","",0,1000001))
                .isInstanceOf(DerivationFailure.class);
        assertThat(new DerivedSegment("synthetic","image_region","",null,null,null,null,null,0d,0d,0.5d,0.5d).kind())
                .isEqualTo("image_region");
    }
    private AiDerivationProperties config(String normalization,String identity) {
        return new AiDerivationProperties(true,false,"synthetic","synthetic-embedding","synthetic-media","v1","synthetic","synthetic",identity,"v1",2,"cosine",normalization,"a".repeat(64));
    }
}
