package org.notesknowledge.knowledge;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.notesknowledge.websupport.ApiFailureException;

record PolicyAcknowledgementRequest(UUID processingPolicyId,String policyCode,long policyVersion,
        String policyFingerprint,String disclosureRevision) {
    private static final Set<String> FIELDS=Set.of("processingPolicyId","policyCode","policyVersion","policyFingerprint","disclosureRevision","confirmAcknowledgement");
    static PolicyAcknowledgementRequest decode(Map<String,Object> input) {
        if(input==null||!input.keySet().equals(FIELDS)||!Boolean.TRUE.equals(input.get("confirmAcknowledgement"))
                ||!(input.get("processingPolicyId") instanceof String id)
                ||!(input.get("policyCode") instanceof String code)||!code.matches("[a-z][a-z0-9_.-]{0,63}")
                ||!(input.get("policyFingerprint") instanceof String fingerprint)||!fingerprint.matches("[0-9a-f]{64}")
                ||!(input.get("disclosureRevision") instanceof String revision)||!revision.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")
                ||!(input.get("policyVersion") instanceof Integer||input.get("policyVersion") instanceof Long)
                ||((Number)input.get("policyVersion")).longValue()<1) throw invalid();
        try {
            UUID uuid=UUID.fromString(id);
            if(!uuid.toString().equals(id)) throw invalid();
            return new PolicyAcknowledgementRequest(uuid,code,((Number)input.get("policyVersion")).longValue(),fingerprint,revision);
        } catch(IllegalArgumentException bad) { throw invalid(); }
    }
    private static ApiFailureException invalid() { return ApiFailureException.of(ApiFailureException.Kind.INVALID_INPUT); }
    @Override public String toString() { return "PolicyAcknowledgementRequest[REDACTED]"; }
}
