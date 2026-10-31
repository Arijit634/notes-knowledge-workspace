package org.notesknowledge.knowledge;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

@Tag("FAST")
class DirectAudioQualificationCommandTest {
    @Test void videoPlanSuppliesExplicitPrimitiveScopeFields() {
        var json=new ObjectMapper();String plan="{\"owner\":\"00000000-0000-7000-8000-000000000001\",\"note\":\"00000000-0000-7000-8000-000000000002\",\"attachment\":\"00000000-0000-7000-8000-000000000003\",\"queries\":[\"synthetic\"],\"expectedFingerprint\":null,\"verificationOnly\":false,\"priorRankings\":[],\"videoOnly\":true}";
        assertThat(json.readValue(plan,DirectAudioQualificationCommand.Plan.class).videoOnly()).isTrue();
        assertThatThrownBy(()->json.readValue(plan.replace("\"verificationOnly\":false,",""),DirectAudioQualificationCommand.Plan.class)).isInstanceOf(tools.jackson.core.JacksonException.class);
    }
    @Test void supportedNeedsACompleteBoundedDecisionWithoutCoercingOrClamping() {
        var json=new ObjectMapper();String valid="{\"decision\":\"supported\",\"description\":\"Synthetic chattering-like sounds\",\"startMs\":0,\"endMs\":1000}";
        assertThat(DirectAudioQualificationCommand.decode(valid,1,json).decision()).isEqualTo("supported");
        for(String invalid:new String[]{valid.replace("1000","1001"),valid.replace("1000","1.5"),valid.replace("1000","\"1000\""),valid.replace("supported","maybe"),valid.replace("\"startMs\":0,",""),valid.replace("}",",\"extra\":true}")})
            assertThatThrownBy(()->DirectAudioQualificationCommand.decode(invalid,1,json)).isInstanceOf(DerivationFailure.class);
    }
    @Test void unsupportedAndUncertainRemainNonAffirmative() {
        var json=new ObjectMapper();
        for(String decision:new String[]{"contradicted","uncertain"})assertThat(DirectAudioQualificationCommand.decode("{\"decision\":\""+decision+"\",\"description\":\"No supported match\",\"startMs\":0,\"endMs\":0}",1,json).decision()).isEqualTo(decision);
    }
    @Test void millisecondBoundaryUsesDecimalSourceDuration() {
        var json=new ObjectMapper();var value=DirectAudioQualificationCommand.decode("{\"decision\":\"uncertain\",\"description\":\"Synthetic interval\",\"startMs\":0,\"endMs\":9088}",9.088,json);
        assertThat(value.endMs()).isEqualTo(9088);
    }
    @Test void controlsAreDeterministicUnrelatedPcmAndCosineIsOnlyRanking() {
        assertThat(DirectAudioQualificationCommand.control(false)).hasSize(32044).isEqualTo(DirectAudioQualificationCommand.control(false));
        assertThat(DirectAudioQualificationCommand.control(true)).hasSize(32044).isNotEqualTo(DirectAudioQualificationCommand.control(false));
        assertThat(DirectAudioQualificationCommand.cosine(new float[]{1,0},new float[]{0,1})).isZero();
        assertThat(DirectAudioQualificationCommand.cosine(new float[]{1,0},new float[]{1,0})).isEqualTo(1);
    }
}
