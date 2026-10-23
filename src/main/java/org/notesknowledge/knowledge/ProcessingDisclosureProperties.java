package org.notesknowledge.knowledge;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Deployment approval is explicit and absent by default; this is not provider authority. */
@ConfigurationProperties("knowledge.processing-disclosure")
record ProcessingDisclosureProperties(String path, String approvedFingerprint, String provider,
        String tier, String region, List<String> categories) {
    @Override public String toString() { return "ProcessingDisclosureProperties[REDACTED]"; }
}
