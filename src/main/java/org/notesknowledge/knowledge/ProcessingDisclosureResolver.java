package org.notesknowledge.knowledge;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import org.springframework.stereotype.Component;

/** Reads one approved local deployment snapshot at construction, never under a DB transaction. */
@Component
class ProcessingDisclosureResolver {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ProcessingDisclosureResolver.class);
    private enum UnavailableReason {
        SNAPSHOT_UNAVAILABLE, SYMBOLIC_LINK, FILE_UNAVAILABLE, FILE_ACCESS_FAILED, INVALID_DOCUMENT,
        POLICY_BINDING_MISMATCH, DEPLOYMENT_BINDING_MISMATCH, UNSUPPORTED_PROVIDER_BINDING,
        DERIVATION_CONFIGURATION_INCOMPLETE, DERIVATION_BINDING_MISMATCH
    }
    private record Loaded(ProcessingDisclosure disclosure, UnavailableReason reason) { }
    private final ProcessingDisclosureProperties properties;
    private final AiDerivationProperties derivation;
    private final ProcessingDisclosure snapshot;
    private final UnavailableReason loadFailure;
    private final java.util.concurrent.atomic.AtomicBoolean failureReported = new java.util.concurrent.atomic.AtomicBoolean();

    @org.springframework.beans.factory.annotation.Autowired
    ProcessingDisclosureResolver(ProcessingDisclosureProperties properties, AiDerivationProperties derivation) {
        this(properties,derivation,load(properties.path()));
    }
    ProcessingDisclosureResolver(ProcessingDisclosureProperties properties,AiDerivationProperties derivation,ProcessingDisclosure snapshot) {
        this(properties, derivation, new Loaded(snapshot, UnavailableReason.SNAPSHOT_UNAVAILABLE));
    }
    private ProcessingDisclosureResolver(ProcessingDisclosureProperties properties,AiDerivationProperties derivation,Loaded loaded) {
        this.properties = properties; this.derivation = derivation;
        this.snapshot = loaded.disclosure(); this.loadFailure = loaded.reason();
    }
    private static Loaded load(String location) {
        if (location == null || location.isBlank()) return new Loaded(null, UnavailableReason.SNAPSHOT_UNAVAILABLE);
        try {
            Path path = Path.of(location);
            if (Files.isSymbolicLink(path)) return new Loaded(null, UnavailableReason.SYMBOLIC_LINK);
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return new Loaded(null, UnavailableReason.FILE_UNAVAILABLE);
            try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                return new Loaded(ProcessingDisclosure.decode(input.readNBytes(ProcessingDisclosure.MAX_FILE_BYTES + 1)), null);
            }
        } catch (org.notesknowledge.websupport.ApiFailureException invalid) {
            return new Loaded(null, UnavailableReason.INVALID_DOCUMENT);
        } catch (Exception invalid) { return new Loaded(null, UnavailableReason.FILE_ACCESS_FAILED); }
    }
    ProcessingDisclosure require(ProcessingPolicyRepository.Policy policy) {
        var d = snapshot;
        List<String> tasks = properties.categories();
        if (d == null) throw unavailable(loadFailure);
        if (!d.id().equals(policy.id()) || !d.code().equals(policy.code())
                || d.version() != policy.version() || !d.revision().equals(policy.disclosureRevision())
                || !d.effectiveAt().equals(policy.effectiveAt()) || !d.fingerprint().equals(policy.fingerprint()))
            throw unavailable(UnavailableReason.POLICY_BINDING_MISMATCH);
        if (!d.fingerprint().equals(properties.approvedFingerprint())
                || !d.provider().equals(properties.provider()) || !d.tier().equals(properties.tier())
                || !d.region().equals(properties.region()) || !d.categories().equals(tasks))
            throw unavailable(UnavailableReason.DEPLOYMENT_BINDING_MISMATCH);
        if (!("gemini".equals(d.provider()) && "unpaid".equals(d.tier()) && "global".equals(d.region())
                || "synthetic".equals(d.provider()) && "synthetic".equals(d.tier())))
            throw unavailable(UnavailableReason.UNSUPPORTED_PROVIDER_BINDING);
        boolean configured = derivation.configured();
        if ((derivation.enabled()||configured) && !configured)
            throw unavailable(UnavailableReason.DERIVATION_CONFIGURATION_INCOMPLETE);
        if ((derivation.enabled()||configured) && (!d.provider().equals(derivation.provider())
                || !d.tier().equals(derivation.tier()) || !d.region().equals(derivation.region())
                // This initial configured adapter surface exposes all five task categories.
                || !d.categories().equals(ProcessingDisclosure.TASKS)))
            throw unavailable(UnavailableReason.DERIVATION_BINDING_MISMATCH);
        return d;
    }
    private org.notesknowledge.websupport.ApiFailureException unavailable(UnavailableReason reason) {
        // A bounded operator diagnostic, never a path, value, fingerprint or exception.
        // This only suppresses duplicate logging; every call still checks authority.
        if (failureReported.compareAndSet(false, true)) LOG.warn("knowledge_disclosure_unavailable reason={}", reason);
        return ProcessingDisclosure.unavailable();
    }
}
