package org.notesknowledge.knowledge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

record EmbeddingLineage(String id,String configuration,String modality,int dimension,String operator,UUID policyId,String normalization) {
    static EmbeddingLineage create(AiDerivationProperties c,ProcessingPolicyService.AcknowledgedProcessingPolicy p,String modality) {
        if(!c.configured() || !c.approvedPolicyFingerprint().equals(p.fingerprint())
                ||!java.util.Set.of("note","image","audio","video","pdf").contains(modality))
            throw new IllegalArgumentException("Unavailable lineage");
        String normalized=String.join("\n",c.provider(),c.adapterVersion(),c.embeddingModel(),c.mediaModel(),c.modelRevision(),
            "document/text-surrogate/float32",c.dimension().toString(),c.operator(),c.normalization(),modality,
            "markdown-weighted-600-900-overlap80-v2", "media-typed-bounded-v1",c.configurationId(),c.region(),c.tier(),
            p.policyId().toString(),Long.toString(p.version()),p.fingerprint());
        try { return new EmbeddingLineage(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(normalized.getBytes(StandardCharsets.UTF_8))),normalized,modality,c.dimension(),c.operator(),p.policyId(),c.normalization()); }
        catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    void validate(float[] vector) {
        if(vector==null ||vector.length!=dimension) throw new DerivationFailure(KnowledgeWork.Failure.INVALID_OUTPUT);
        double norm=0;
        for(float value:vector) { if(!Float.isFinite(value)) throw new DerivationFailure(KnowledgeWork.Failure.INVALID_OUTPUT); norm+=(double)value*value; }
        if(norm==0) throw new DerivationFailure(KnowledgeWork.Failure.INVALID_OUTPUT);
    }
    float[] prepare(float[] vector) {
        validate(vector);float[] prepared=vector.clone();
        if(normalization.equals("unit")) {
            double norm=0;for(float value:prepared)norm+=(double)value*value;
            double scale=Math.sqrt(norm);for(int i=0;i<prepared.length;i++)prepared[i]=(float)(prepared[i]/scale);
        }
        return prepared;
    }
    @Override public String toString() { return "EmbeddingLineage[configuration omitted]"; }
}
