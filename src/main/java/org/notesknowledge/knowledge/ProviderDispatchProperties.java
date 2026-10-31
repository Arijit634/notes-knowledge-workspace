package org.notesknowledge.knowledge;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Deployment-owned metadata, never supplied by the browser or inferred from content. */
@ConfigurationProperties("knowledge.derivation")
record ProviderDispatchProperties(String dispatchPolicy,List<String> approvedSourceFingerprints,List<String> approvedQueryFingerprints,
        List<String> approvedChildFingerprints,java.util.UUID syntheticWorkspaceOwner) {
    @org.springframework.boot.context.properties.bind.ConstructorBinding
    ProviderDispatchProperties {
        approvedSourceFingerprints=approvedSourceFingerprints==null?List.of():List.copyOf(approvedSourceFingerprints);
        approvedQueryFingerprints=approvedQueryFingerprints==null?List.of():List.copyOf(approvedQueryFingerprints);
        approvedChildFingerprints=approvedChildFingerprints==null?List.of():List.copyOf(approvedChildFingerprints);
    }
    ProviderDispatchProperties(String dispatchPolicy,List<String> approvedSourceFingerprints,List<String> approvedQueryFingerprints){
        this(dispatchPolicy,approvedSourceFingerprints,approvedQueryFingerprints,List.of());
    }
    ProviderDispatchProperties(String dispatchPolicy,List<String> approvedSourceFingerprints,List<String> approvedQueryFingerprints,List<String> approvedChildFingerprints){
        this(dispatchPolicy,approvedSourceFingerprints,approvedQueryFingerprints,approvedChildFingerprints,null);
    }
    ProviderDispatchProperties(String dispatchPolicy,List<String> approvedSourceFingerprints){this(dispatchPolicy,approvedSourceFingerprints,List.of());}
    @Override public String toString(){return "ProviderDispatchProperties[REDACTED]";}
}
