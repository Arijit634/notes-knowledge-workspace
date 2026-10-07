package org.notesknowledge.knowledge;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Deployment-owned metadata, never supplied by the browser or inferred from content. */
@ConfigurationProperties("knowledge.derivation")
record ProviderDispatchProperties(String dispatchPolicy,List<String> approvedSourceFingerprints) {
    ProviderDispatchProperties {
        approvedSourceFingerprints=approvedSourceFingerprints==null?List.of():List.copyOf(approvedSourceFingerprints);
    }
    @Override public String toString(){return "ProviderDispatchProperties[REDACTED]";}
}
