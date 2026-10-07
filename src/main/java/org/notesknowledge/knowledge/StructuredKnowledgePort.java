package org.notesknowledge.knowledge;

import java.util.List;

/** Capability-specific application contract; evidence IDs are ephemeral, never source authority. */
interface StructuredKnowledgePort {
    record Evidence(String id,String text,String modality) {
        @Override public String toString(){return "ProviderEvidence[REDACTED]";}
    }
    record Claim(String text,List<String> evidenceIds) {
        public Claim {evidenceIds=List.copyOf(evidenceIds);}
        @Override public String toString(){return "ProviderClaim[REDACTED]";}
    }
    record Output(List<Claim> claims,boolean conflicting) {
        public Output {claims=List.copyOf(claims);}
        @Override public String toString(){return "StructuredProviderOutput[REDACTED]";}
    }
    boolean available();
    float[] embedQuery(KnowledgeQueryGate.QueryPermit permit,String query);
    Output generate(KnowledgeQueryGate.EvidencePermit permit,String query,String task,List<Evidence> evidence);
}
