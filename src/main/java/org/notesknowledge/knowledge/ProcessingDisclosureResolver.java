package org.notesknowledge.knowledge;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import org.springframework.stereotype.Component;

/** Reads one approved local deployment snapshot at construction, never under a DB transaction. */
@Component
class ProcessingDisclosureResolver {
    private final ProcessingDisclosureProperties properties;
    private final AiDerivationProperties derivation;
    private final ProcessingDisclosure snapshot;

    @org.springframework.beans.factory.annotation.Autowired
    ProcessingDisclosureResolver(ProcessingDisclosureProperties properties, AiDerivationProperties derivation) {
        this(properties,derivation,load(properties.path()));
    }
    ProcessingDisclosureResolver(ProcessingDisclosureProperties properties,AiDerivationProperties derivation,ProcessingDisclosure snapshot) {
        this.properties = properties; this.derivation = derivation;
        this.snapshot = snapshot;
    }
    private static ProcessingDisclosure load(String location) {
        if (location == null || location.isBlank()) return null;
        try {
            Path path = Path.of(location);
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return null;
            try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                return ProcessingDisclosure.decode(input.readNBytes(ProcessingDisclosure.MAX_FILE_BYTES + 1));
            }
        } catch (Exception invalid) { return null; }
    }
    ProcessingDisclosure require(ProcessingPolicyRepository.Policy policy) {
        var d = snapshot;
        List<String> tasks = properties.categories();
        if (d == null || !d.id().equals(policy.id()) || !d.code().equals(policy.code())
                || d.version() != policy.version() || !d.revision().equals(policy.disclosureRevision())
                || !d.effectiveAt().equals(policy.effectiveAt()) || !d.fingerprint().equals(policy.fingerprint())
                || !d.fingerprint().equals(properties.approvedFingerprint())
                || !d.provider().equals(properties.provider()) || !d.tier().equals(properties.tier())
                || !d.region().equals(properties.region()) || !d.categories().equals(tasks)
                || !("gemini".equals(d.provider()) && "unpaid".equals(d.tier()) && "global".equals(d.region())
                    || "synthetic".equals(d.provider()) && "synthetic".equals(d.tier())))
            throw ProcessingDisclosure.unavailable();
        if ((derivation.enabled()||derivation.configured()) && (!derivation.configured() || !d.provider().equals(derivation.provider())
                || !d.tier().equals(derivation.tier()) || !d.region().equals(derivation.region())
                // This initial configured adapter surface exposes all five task categories.
                || !d.categories().equals(ProcessingDisclosure.TASKS))) throw ProcessingDisclosure.unavailable();
        return d;
    }
}
