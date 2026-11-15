package org.notesknowledge.knowledge;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@Tag("FAST") @Tag("SECURITY") @Tag("API")
@ExtendWith(OutputCaptureExtension.class)
class ProcessingDisclosureDiagnosticsTest {
    @TempDir Path temp;
    private static final String PRIVATE_MARKER = "disclosure-value-must-not-be-logged";

    private byte[] bundle() {
        return ProcessingDisclosureFixtures.bundle(
                UUID.fromString("01990a30-e254-70b3-a1d7-0763c4de2201"), "synthetic-test", 1,
                "synthetic-1", Instant.parse("2026-10-23T00:00:00Z"),
                "synthetic", "synthetic", "synthetic");
    }
    private ProcessingPolicyRepository.Policy policy(ProcessingDisclosure d) {
        return new ProcessingPolicyRepository.Policy(d.id(), d.code(), d.version(),
                d.fingerprint(), d.revision(), d.effectiveAt());
    }
    private ProcessingDisclosureProperties properties(ProcessingDisclosure d, String path) {
        return new ProcessingDisclosureProperties(path, d.fingerprint(), d.provider(),
                d.tier(), d.region(), d.categories());
    }
    private void denied(ProcessingDisclosureResolver resolver, ProcessingPolicyRepository.Policy policy) {
        assertThatThrownBy(() -> resolver.require(policy)).isInstanceOfSatisfying(ApiFailureException.class,
                e -> assertThat(e.kind()).isEqualTo(ApiFailureException.Kind.SERVICE_UNAVAILABLE));
    }
    private void privateValuesAbsent(CapturedOutput output, ProcessingDisclosure d) {
        assertThat(output.getAll()).doesNotContain(PRIVATE_MARKER, temp.toString(),
                d.id().toString(), d.fingerprint(), d.content());
    }

    @ParameterizedTest @ValueSource(strings = {"missing", "invalid", "invalidPath"})
    void fileFailuresHaveOnlyFixedReasons(String failure, CapturedOutput output) throws Exception {
        var d = ProcessingDisclosure.decode(bundle());
        Path path = temp.resolve(PRIVATE_MARKER);
        if (failure.equals("invalid")) Files.writeString(path, PRIVATE_MARKER);
        String location = failure.equals("invalidPath") ? PRIVATE_MARKER + '\0' : path.toString();
        var resolver = new ProcessingDisclosureResolver(properties(d, location), mock(AiDerivationProperties.class));
        denied(resolver, policy(d));
        String reason = switch (failure) {
            case "missing" -> "FILE_UNAVAILABLE";
            case "invalid" -> "INVALID_DOCUMENT";
            default -> "FILE_ACCESS_FAILED";
        };
        assertThat(output.getAll()).contains("knowledge_disclosure_unavailable reason=" + reason);
        privateValuesAbsent(output, d);
    }

    @Test @EnabledOnOs(OS.LINUX)
    void projectedSymlinkRemainsRejectedWithoutLoggingItsTarget(CapturedOutput output) throws Exception {
        var d = ProcessingDisclosure.decode(bundle());
        Path target = temp.resolve(PRIVATE_MARKER);
        Files.write(target, bundle());
        Path link = temp.resolve("projection.json");
        Files.createSymbolicLink(link, target);
        var resolver = new ProcessingDisclosureResolver(properties(d, link.toString()), mock(AiDerivationProperties.class));
        denied(resolver, policy(d));
        assertThat(output.getAll()).contains("knowledge_disclosure_unavailable reason=SYMBOLIC_LINK");
        privateValuesAbsent(output, d);
    }

    @ParameterizedTest @ValueSource(strings = {"policy", "approval", "derivationIncomplete", "derivationMismatch"})
    void bindingFailuresStillDenyWithSanitizedDiagnostics(String change, CapturedOutput output) {
        var d = ProcessingDisclosure.decode(bundle());
        var p = policy(d);
        var props = properties(d, null);
        var derivation = mock(AiDerivationProperties.class);
        String reason;
        switch (change) {
            case "policy" -> {
                p = new ProcessingPolicyRepository.Policy(d.id(), d.code(), 2,
                        d.fingerprint(), d.revision(), d.effectiveAt());
                reason = "POLICY_BINDING_MISMATCH";
            }
            case "approval" -> {
                props = new ProcessingDisclosureProperties(null, null, d.provider(), d.tier(), d.region(), d.categories());
                reason = "DEPLOYMENT_BINDING_MISMATCH";
            }
            case "derivationIncomplete" -> {
                when(derivation.enabled()).thenReturn(true);
                reason = "DERIVATION_CONFIGURATION_INCOMPLETE";
            }
            default -> {
                when(derivation.enabled()).thenReturn(true);
                when(derivation.configured()).thenReturn(true);
                when(derivation.provider()).thenReturn("other");
                reason = "DERIVATION_BINDING_MISMATCH";
            }
        }
        denied(new ProcessingDisclosureResolver(props, derivation, d), p);
        assertThat(output.getAll()).contains("knowledge_disclosure_unavailable reason=" + reason);
        privateValuesAbsent(output, d);
    }

    @Test void repeatDenialLogsOnceButNeverCachesAuthorization(CapturedOutput output) {
        var d = ProcessingDisclosure.decode(bundle());
        var resolver = new ProcessingDisclosureResolver(properties(d, null), mock(AiDerivationProperties.class), d);
        var wrong = new ProcessingPolicyRepository.Policy(d.id(), d.code(), 2,
                d.fingerprint(), d.revision(), d.effectiveAt());
        denied(resolver, wrong);
        denied(resolver, wrong);
        assertThat(resolver.require(policy(d))).isEqualTo(d);
        denied(resolver, wrong);
        assertThat(output.getAll().split("knowledge_disclosure_unavailable", -1)).hasSize(2);
        privateValuesAbsent(output, d);
    }

    @Test void validSnapshotRemainsImmutableAndQuiet(CapturedOutput output) throws Exception {
        var d = ProcessingDisclosure.decode(bundle());
        Path path = temp.resolve(PRIVATE_MARKER);
        Files.write(path, bundle());
        var resolver = new ProcessingDisclosureResolver(properties(d, path.toString()), mock(AiDerivationProperties.class));
        Files.writeString(path, PRIVATE_MARKER);
        assertThat(resolver.require(policy(d))).isEqualTo(d);
        assertThat(output.getAll()).doesNotContain("knowledge_disclosure_unavailable");
        privateValuesAbsent(output, d);
    }
}
