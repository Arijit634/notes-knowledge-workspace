package org.notesknowledge;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;
import static org.assertj.core.api.Assertions.*;

@Tag("FAST")
@Tag("SECURITY")
class BrowserIntegrationServerTest {
    @TempDir Path root;

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"../outside.txt", "..\\outside.txt", "/outside.txt", "C:\\outside.txt",
        "sub/scene.png", "sub\\scene.png", "scene.png/../outside.txt", "scene.png:stream", "other.png"})
    void rejectsUntrustedNamesWithoutWritingAnything(String name) throws Exception {
        Path directory = Files.createDirectory(root.resolve("fixtures"));
        Path outside = Files.writeString(root.resolve("outside.txt"), "unchanged");
        var media = new MockMultipartFile("file", name, "image/png", new byte[]{1, 2});
        assertThatIllegalArgumentException().isThrownBy(() -> BrowserIntegrationServer.writeFixture(directory, media));
        assertThat(Files.readString(outside)).isEqualTo("unchanged");
        try (var entries = Files.list(directory)) { assertThat(entries).isEmpty(); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"scene.png", "fictional.pdf", "aster.wav", "scene.mp4"})
    void writesOnlyApprovedLiteralNamesAndNeverOverwrites(String name) throws Exception {
        Path directory = Files.createDirectory(root.resolve("fixtures"));
        byte[] bytes = {1, 2, 3};
        var media = new MockMultipartFile("file", name, "application/octet-stream", bytes);
        BrowserIntegrationServer.writeFixture(directory, media);
        assertThat(Files.readAllBytes(directory.resolve(name))).isEqualTo(bytes);
        var replacement = new MockMultipartFile("file", name, "application/octet-stream", new byte[]{9});
        assertThatExceptionOfType(FileAlreadyExistsException.class)
            .isThrownBy(() -> BrowserIntegrationServer.writeFixture(directory, replacement));
        assertThat(Files.readAllBytes(directory.resolve(name))).isEqualTo(bytes);
    }
}
