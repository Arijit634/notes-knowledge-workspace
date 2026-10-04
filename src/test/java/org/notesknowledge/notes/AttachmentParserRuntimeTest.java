package org.notesknowledge.notes;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;
import org.apache.tika.config.loader.TikaLoader;
import org.apache.tika.metadata.PDF;
import org.apache.tika.metadata.PagedText;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.CompositeParser;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("FAST")
class AttachmentParserRuntimeTest {
    @TempDir Path temporary;

    @Test
    void effectiveRegistryContainsOnlyPdfAndMp4() throws Exception {
        var loader = TikaLoader.load(Path.of(getClass().getResource("/attachments/parser.json").toURI()));
        var parser = (CompositeParser)loader.loadParsers();
        assertThat(parser.getAllComponentParsers()).extracting(p -> p.getClass().getSimpleName())
                .containsExactlyInAnyOrder("PDFParser", "MP4Parser");
    }

    @Test
    void immutableProductionConfigurationForksAndReturnsOnlyAllowedMetadata() throws Exception {
        Path input = temporary.resolve("synthetic.pdf");
        try (var document = new PDDocument()) {
            document.addPage(new PDPage());
            document.getDocumentInformation().setTitle("private-fixture-title-never-returned");
            document.save(input.toFile());
        }
        Path ownedDirectory;
        try (var runtime = new AttachmentParserRuntime()) {
            ownedDirectory = (Path)org.springframework.test.util.ReflectionTestUtils.getField(runtime, "directory");
            assertThat(Files.isDirectory(ownedDirectory)).isTrue();
            var result = runtime.parse(input);
            assertThat(result.getInt(PagedText.N_PAGES)).isEqualTo(1);
            assertThat(result.get("dc:title")).isNull();
            assertThat(result.get("X-TIKA:content")).isNull();
            assertThat(result.get(PDF.IS_ENCRYPTED)).isEqualTo("false");
            assertThat(runtime.parse(writeMp4()).get("Content-Type")).isEqualTo("video/mp4");
        }
        assertThat(Files.exists(ownedDirectory)).isFalse();
    }

    @Test
    void embeddedFileIsDetectableWithoutEmbeddedExtraction() throws Exception {
        Path input = temporary.resolve("synthetic-embedded.pdf");
        try (var document = new PDDocument()) {
            document.addPage(new PDPage());
            var specification = new PDComplexFileSpecification();
            specification.setFile("synthetic.txt");
            specification.setEmbeddedFile(new PDEmbeddedFile(document,
                    new ByteArrayInputStream(new byte[]{65, 66, 67})));
            var tree = new PDEmbeddedFilesNameTreeNode();
            tree.setNames(Map.of("synthetic.txt", specification));
            var names = new PDDocumentNameDictionary(document.getDocumentCatalog());
            names.setEmbeddedFiles(tree);
            document.getDocumentCatalog().setNames(names);
            document.save(input.toFile());
        }
        // Diagnostic assertion of the actual fork metadata before endpoint activation.
        var config = new org.apache.tika.pipes.fork.PipesForkParserConfig()
                .setUserConfigPath(Path.of(getClass().getResource("/attachments/parser.json").toURI()))
                .setPluginsDir(temporary.resolve("plugins"))
                .setNumClients(1).setMaxEmbeddedCount(0).setWriteLimit(256)
                .setTimeoutLimits(new org.apache.tika.config.TimeoutLimits(15_000, 5_000))
                .setJvmArgs(java.util.List.of("-Xmx256m", "-XX:MaxDirectMemorySize=64m",
                        "-XX:ActiveProcessorCount=2", "-Djava.awt.headless=true"));
        config.getPipesConfig().setMaxIpcPayloadBytes(65_536);
        config.getPipesConfig().setMaxInlineBytes(0);
        try (var parser = new org.apache.tika.pipes.fork.PipesForkParser(config)) {
            var result = parser.parse(input);
            assertThat(result.getMetadataList()).hasSize(1);
            assertThat(result.getMetadata().get(TikaCoreProperties.EMBEDDED_RESOURCE_LIMIT_REACHED)).isNotNull();
        }
    }

    private Path writeMp4() throws Exception {
        return Files.write(temporary.resolve("synthetic.mp4"), AttachmentParserPreflightTest.supportedMp4());
    }
}
