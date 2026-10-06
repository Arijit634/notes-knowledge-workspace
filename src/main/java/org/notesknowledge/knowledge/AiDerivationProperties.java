package org.notesknowledge.knowledge;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** No production provider/model is implicit. Enabling is an explicit operator policy decision. */
@ConfigurationProperties("knowledge.derivation")
record AiDerivationProperties(boolean enabled, boolean schedulingEnabled, String provider, String embeddingModel,
        String mediaModel, String modelRevision, String region, String tier, String configurationId,
        String adapterVersion, Integer dimension, String operator, String normalization,
        String approvedPolicyFingerprint) {
    boolean configured() {
        return enabled && bounded(provider) && bounded(embeddingModel) && bounded(mediaModel)
            && bounded(modelRevision) && bounded(region) && bounded(tier) && bounded(configurationId)
            && bounded(adapterVersion) && dimension!=null && dimension>0 && dimension<=16000
            && java.util.Set.of("cosine","l2","inner_product").contains(operator==null?"":operator)
            && java.util.Set.of("none","unit").contains(normalization==null?"":normalization)
            && approvedPolicyFingerprint!=null && approvedPolicyFingerprint.matches("[0-9a-f]{64}");
    }
    private static boolean bounded(String value) { return value!=null && value.matches("[A-Za-z0-9][A-Za-z0-9_.:/-]{0,127}"); }
}
