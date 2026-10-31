package org.notesknowledge.knowledge;

import static org.assertj.core.api.Assertions.*;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.notesknowledge.websupport.ApiFailureException;

@Tag("FAST") @Tag("SECURITY")
class KnowledgeEnvelopeConfigurationTest {
    private static String key(int value){byte[] bytes=new byte[32];java.util.Arrays.fill(bytes,(byte)value);return Base64.getEncoder().encodeToString(bytes);}
    private static KnowledgeOperationMaterialCipher.Context context(){return new KnowledgeOperationMaterialCipher.Context(UUID.fromString("01999000-0000-7000-8000-000000000001"),UUID.fromString("01999000-0000-7000-8000-000000000002"),"search_history",KnowledgeOperationMaterialCipher.Kind.INPUT,0);}
    private static void unavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable call){assertThatThrownBy(call).isInstanceOfSatisfying(ApiFailureException.class,e->assertThat(e.kind()).isEqualTo(ApiFailureException.Kind.SERVICE_UNAVAILABLE));}
    @Test void missingKeyIsAnUnavailableCapabilityNotPlaintextOrAFictitiousSuccess(){
        var missing=new KnowledgeOperationMaterialCipher("v1","","","");unavailable(()->missing.seal(context(),new byte[]{1}));
    }
    @Test void invalidLengthVersionAndEncodingFailStartupWithoutIncludingTheRejectedValue(){
        for(String invalid:new String[]{"not-base64",Base64.getEncoder().encodeToString(new byte[31]),Base64.getEncoder().encodeToString(new byte[33])})
            assertThatThrownBy(()->new KnowledgeOperationMaterialCipher("v1",invalid,"","")).isInstanceOf(IllegalArgumentException.class).hasMessage("Operation key must be base64 encoded 256 bits");
        assertThatThrownBy(()->new KnowledgeOperationMaterialCipher("invalid version",key(69),"","")).hasMessage("Invalid operation key version");
    }
    @Test void plannedRotationPreservesOldRetainedHistoryButMissingOrWrongFormerKeysFailClosed(){
        var original=new KnowledgeOperationMaterialCipher("v1",key(69),"","");var frame=original.seal(context(),new byte[]{1,2,3});
        var rotated=new KnowledgeOperationMaterialCipher("v2",key(70),"v1",key(69));assertThat(rotated.open(context(),frame)).isEqualTo(new byte[]{1,2,3});
        var next=rotated.seal(context(),new byte[]{4,5});assertThat(next.keyVersion()).isEqualTo("v2");
        unavailable(()->new KnowledgeOperationMaterialCipher("v2",key(70),"","").open(context(),frame));
        unavailable(()->new KnowledgeOperationMaterialCipher("v2",key(70),"v1",key(71)).open(context(),frame));
        unavailable(()->original.open(context(),new KnowledgeOperationMaterialCipher.Envelope(frame.ciphertext(),frame.nonce(),"unknown")));
    }
}
