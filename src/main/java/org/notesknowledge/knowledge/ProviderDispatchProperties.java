package org.notesknowledge.knowledge;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Deployment-owned metadata, never supplied by the browser or inferred from content. */
@ConfigurationProperties("knowledge.derivation")
record ProviderDispatchProperties(String dispatchPolicy,List<String> approvedSourceFingerprints,List<String> approvedQueryFingerprints) {
    @org.springframework.boot.context.properties.bind.ConstructorBinding
    ProviderDispatchProperties {
        approvedSourceFingerprints=approvedSourceFingerprints==null?List.of():List.copyOf(approvedSourceFingerprints);
        approvedQueryFingerprints=approvedQueryFingerprints==null?List.of():List.copyOf(approvedQueryFingerprints);
    }
    ProviderDispatchProperties(String dispatchPolicy,List<String> approvedSourceFingerprints){this(dispatchPolicy,approvedSourceFingerprints,List.of());}
    @Override public String toString(){return "ProviderDispatchProperties[REDACTED]";}
}
