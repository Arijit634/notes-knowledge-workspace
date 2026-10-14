package org.notesknowledge.knowledge;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** No production provider/model is implicit. Enabling is an explicit operator policy decision. */
@ConfigurationProperties("knowledge.derivation")
record AiDerivationProperties(boolean enabled, boolean schedulingEnabled, String provider, String embeddingModel,
        String mediaModel, String modelRevision, String region, String tier, String configurationId,
        String adapterVersion, Integer dimension, String operator, String normalization,
        String approvedPolicyFingerprint, boolean pdfPageChildren, String pdfTransformFingerprint,
        boolean videoChildren,String videoTransformFingerprint) {
    @org.springframework.boot.context.properties.bind.ConstructorBinding
    AiDerivationProperties { }
    AiDerivationProperties(boolean enabled,boolean schedulingEnabled,String provider,String embeddingModel,
            String mediaModel,String modelRevision,String region,String tier,String configurationId,
            String adapterVersion,Integer dimension,String operator,String normalization,String approvedPolicyFingerprint) {
        this(enabled,schedulingEnabled,provider,embeddingModel,mediaModel,modelRevision,region,tier,configurationId,
            adapterVersion,dimension,operator,normalization,approvedPolicyFingerprint,false,null,false,null);
    }
    AiDerivationProperties(boolean enabled,boolean schedulingEnabled,String provider,String embeddingModel,
            String mediaModel,String modelRevision,String region,String tier,String configurationId,
            String adapterVersion,Integer dimension,String operator,String normalization,String approvedPolicyFingerprint,
            boolean pdfPageChildren,String pdfTransformFingerprint) {
        this(enabled,schedulingEnabled,provider,embeddingModel,mediaModel,modelRevision,region,tier,configurationId,
            adapterVersion,dimension,operator,normalization,approvedPolicyFingerprint,pdfPageChildren,pdfTransformFingerprint,false,null);
    }
    boolean configured() {
        return enabled && (!pdfPageChildren||pdfTransformFingerprint!=null&&pdfTransformFingerprint.matches("[0-9a-f]{64}"))
            && (!videoChildren||videoTransformFingerprint!=null&&videoTransformFingerprint.matches("[0-9a-f]{64}"))
            && bounded(provider) && bounded(embeddingModel) && bounded(mediaModel)
            && bounded(modelRevision) && bounded(region) && bounded(tier) && bounded(configurationId)
            && bounded(adapterVersion) && dimension!=null && dimension>0 && dimension<=16000
            && java.util.Set.of("cosine","l2","inner_product").contains(operator==null?"":operator)
            && java.util.Set.of("none","unit").contains(normalization==null?"":normalization)
            && approvedPolicyFingerprint!=null && approvedPolicyFingerprint.matches("[0-9a-f]{64}");
    }
    private static boolean bounded(String value) { return value!=null && value.matches("[A-Za-z0-9][A-Za-z0-9_.:/-]{0,127}"); }
    @Override public String toString(){return "AiDerivationProperties[REDACTED]";}
}
