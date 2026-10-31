package org.notesknowledge.knowledge;

interface AudioSignalPort {
    record Verification(String decision,String description,int startMs,int endMs) {
        boolean supported(){return "supported".equals(decision);}
        @Override public String toString(){return "AudioVerification[private content omitted]";}
    }
    boolean available();
    float[] embed(KnowledgeQueryGate.EvidencePermit permit,String query,byte[] wav);
    Verification verify(KnowledgeQueryGate.EvidencePermit permit,String query,byte[] wav,double seconds);
    default Verification verifyVideo(KnowledgeQueryGate.EvidencePermit permit,String query,byte[] mp4,double seconds){throw new DerivationFailure(KnowledgeWork.Failure.PROVIDER_UNAVAILABLE);}
}
