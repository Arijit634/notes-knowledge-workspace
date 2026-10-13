package org.notesknowledge.notes;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@Tag("FAST") @Tag("SECURITY")
class MediaRuntimeConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(MediaRuntimeConfiguration.class);

    @Test void unconfiguredApplicationHasNoMediaRuntime() {
        context.run(application -> {
            assertThat(application).hasNotFailed();
            assertThat(application).doesNotHaveBean(PdfPageRuntime.class).doesNotHaveBean(VideoClipRuntime.class);
        });
    }
    @Test void explicitDisabledSelectionDoesNotInspectUnavailablePaths() {
        context.withPropertyValues("notes.media-runtime.pdf.enabled=false", "notes.media-runtime.video.enabled=false",
                "notes.media-runtime.pdf.root=unavailable", "notes.media-runtime.video.root=unavailable")
                .run(application -> assertThat(application).hasNotFailed());
    }
    @Test void enabledPdfCannotSelectAnUnqualifiedOrMissingDeployment() {
        context.withPropertyValues("notes.media-runtime.pdf.enabled=true")
                .run(application -> assertThat(application).hasFailed());
    }
    @Test void enabledVideoCannotSelectAnUnqualifiedOrMissingDeployment() {
        context.withPropertyValues("notes.media-runtime.video.enabled=true")
                .run(application -> assertThat(application).hasFailed());
    }
    @Test void noWindowsOrUnconfinedFallbackIsSelected() {
        for (String profile : java.util.List.of("windows", "unconfined", "auto", "docker")) {
            context.withPropertyValues("notes.media-runtime.pdf.enabled=true", "notes.media-runtime.pdf.profile=" + profile)
                    .run(application -> assertThat(application).hasFailed());
        }
    }
    @Test void selectionDiagnosticsNeverExposePathsOrArtifactIdentities() {
        var selection = new MediaRuntimeProperties.Selection(true, "linux-confined-v1", java.nio.file.Path.of("synthetic"),
                null, null, "a".repeat(64), "v1");
        assertThat(selection.toString()).isEqualTo("MediaRuntimeSelection[REDACTED]");
        assertThat(new MediaRuntimeProperties(selection, selection).toString()).isEqualTo("MediaRuntimeProperties[REDACTED]");
    }
}
