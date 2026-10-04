package org.notesknowledge.notes;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionLaunch;
import org.apache.tika.config.TimeoutLimits;
import org.apache.tika.metadata.HttpHeaders;
import org.apache.tika.metadata.PagedText;
import org.apache.tika.metadata.TIFF;
import org.apache.tika.metadata.XMPDM;
import org.apache.tika.pipes.fork.PipesForkParser;
import org.apache.tika.pipes.fork.PipesForkParserConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Dependency preflight only: no production parser or HTTP route is activated. */
@Tag("FAST")
class AttachmentParserPreflightTest {
    @TempDir Path temporary;

    @Test
    void forkStartsWithOnlyApprovedParsersAndReportsPdfMetadata() throws Exception {
        Path pdf = Files.createTempFile(temporary, "synthetic-", ".bin");
        // Fixture construction only; all reading/parsing happens in the Tika child.
        try (var document = new PDDocument()) {
            document.addPage(new PDPage());
            document.save(pdf.toFile());
        }
        try (var parser = new PipesForkParser(configuration())) {
            var result = parser.parse(pdf);
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getMetadata().get(HttpHeaders.CONTENT_TYPE)).isEqualTo("application/pdf");
            assertThat(result.getMetadata().getInt(PagedText.N_PAGES)).isEqualTo(1);
        }
    }

    @Test
    void launchActionIsReportedWithoutExecutingOrExtractingIt() throws Exception {
        Path pdf = Files.createTempFile(temporary, "synthetic-", ".bin");
        try (var document = new PDDocument()) {
            document.addPage(new PDPage());
            var launch = new PDActionLaunch();
            launch.setF("synthetic-never-executed.exe");
            document.getDocumentCatalog().setOpenAction(launch);
            document.save(pdf.toFile());
        }
        try (var parser = new PipesForkParser(configuration())) {
            var result = parser.parse(pdf);
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getMetadata().get(HttpHeaders.CONTENT_TYPE)).isEqualTo("application/pdf");
            assertThat(result.getMetadata().getInt(PagedText.N_PAGES)).isEqualTo(1);
            assertThat(result.getMetadataList()).hasSize(1);
            assertThat(result.getContent()).isNullOrEmpty();
            // Tika intentionally reports the generic active-action category, not /S.
            assertThat(result.getMetadata().get("pdf:action-types")).isEqualTo("Action");
            assertThat(result.getMetadata().get("pdf:action-triggers")).isNotBlank();
        }
    }

    @Test
    void truncatedTrailingBoxCannotBeDistinguishedFromEndOfInputWarning() throws Exception {
        Path complete = Files.createTempFile(temporary, "synthetic-", ".bin");
        Path truncated = Files.createTempFile(temporary, "synthetic-", ".bin");
        Files.write(complete, singleSampleMp4());
        Files.write(truncated, concatenate(singleSampleMp4(), new byte[]{0, 0, 0}));
        try (var parser = new PipesForkParser(configuration())) {
            var first = parser.parse(complete);
            var second = parser.parse(truncated);
            assertThat(first.isSuccess()).isTrue();
            assertThat(second.isSuccess()).isTrue();
            for (var property : List.of(HttpHeaders.CONTENT_TYPE, TIFF.IMAGE_WIDTH,
                    TIFF.IMAGE_LENGTH, XMPDM.DURATION)) {
                assertThat(second.getMetadata().get(property)).isEqualTo(first.getMetadata().get(property));
            }
            assertThat(first.getMetadata().get(HttpHeaders.CONTENT_TYPE)).isEqualTo("video/mp4");
            assertThat(first.getMetadata().get(TIFF.IMAGE_WIDTH)).isEqualTo("16");
            assertThat(first.getMetadata().get(TIFF.IMAGE_LENGTH)).isEqualTo("16");
            assertThat(first.getMetadata().get(XMPDM.DURATION)).isEqualTo("1.0");
            assertThat(first.getMetadata().getValues("tk:exception:warn")).isNotEmpty();
            assertThat(second.getMetadata().getValues("tk:exception:warn"))
                    .containsExactly(first.getMetadata().getValues("tk:exception:warn"));
        }
    }

    @Test
    void measureWhetherMp4MetadataProvesMediaPayloadValidity() throws Exception {
        // An intentionally invalid MP4: plausible track metadata, no samples or mdat.
        Path mp4 = Files.createTempFile(temporary, "synthetic-", ".bin");
        Files.write(mp4, metadataOnlyMp4());
        try (var parser = new PipesForkParser(configuration())) {
            var result = parser.parse(mp4);
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getMetadata().get(HttpHeaders.CONTENT_TYPE)).isEqualTo("video/mp4");
            assertThat(result.getMetadata().get(TIFF.IMAGE_WIDTH)).isEqualTo("16");
            assertThat(result.getMetadata().get(TIFF.IMAGE_LENGTH)).isEqualTo("16");
            assertThat(result.getMetadata().get(XMPDM.DURATION)).isEqualTo("1.0");
            // Success and dimensions alone do not establish sample/payload validity.
            assertThat(result.getMetadata().get("tk:exception:warn")).isNotBlank();
        }
    }

    @Test
    void supportedAvcContainerAndForkAgreeOnBoundedMetadata() throws Exception {
        Path input = Files.createTempFile(temporary, "synthetic-", ".bin");
        Files.write(input, supportedMp4());
        var structural = new StrictMp4StructureValidator().validate(input);
        try (var parser = new PipesForkParser(configuration())) {
            var result = parser.parse(input);
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getMetadata().get(HttpHeaders.CONTENT_TYPE)).isEqualTo("video/mp4");
            assertThat(result.getMetadata().getInt(TIFF.IMAGE_WIDTH)).isEqualTo(structural.width());
            assertThat(result.getMetadata().getInt(TIFF.IMAGE_LENGTH)).isEqualTo(structural.height());
            assertThat(Double.parseDouble(result.getMetadata().get(XMPDM.DURATION)))
                    .isEqualTo(structural.durationSeconds());
        }
    }

    private PipesForkParserConfig configuration() throws Exception {
        var config = new PipesForkParserConfig()
                .setUserConfigPath(Path.of(getClass().getResource("/attachments/parser-preflight.json").toURI()))
                .setPluginsDir(Path.of("target/attachment-parser-plugins").toAbsolutePath())
                .setJavaPath(Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java").toString())
                .setNumClients(1).setMaxFilesPerProcess(25).setWriteLimit(256).setMaxEmbeddedCount(0)
                .setTimeoutLimits(new TimeoutLimits(15_000, 5_000))
                .setJvmArgs(List.of("-Xmx256m", "-XX:MaxDirectMemorySize=64m",
                        "-XX:ActiveProcessorCount=2", "-Djava.awt.headless=true"));
        config.getPipesConfig().setMaxInlineBytes(0);
        config.getPipesConfig().setMaxIpcPayloadBytes(65_536);
        return config;
    }

    private static boolean isWindows() { return System.getProperty("os.name").startsWith("Windows"); }

    static byte[] metadataOnlyMp4() throws Exception {
        var movie = ByteBuffer.allocate(100).putInt(0).putInt(0).putInt(0)
                .putInt(1000).putInt(1000).array();
        var handler = ByteBuffer.allocate(24).putInt(0).putInt(0)
                .put("vide".getBytes(StandardCharsets.US_ASCII)).array();
        var entry = ByteBuffer.allocate(94).putInt(0).putInt(1).putInt(86)
                .put("avc1".getBytes(StandardCharsets.US_ASCII));
        entry.position(40); entry.putShort((short)16).putShort((short)16);
        return concatenate(box("ftyp", "mp42\0\0\0\0mp42".getBytes(StandardCharsets.US_ASCII)),
                box("moov", concatenate(box("mvhd", movie), box("trak", box("mdia",
                        concatenate(box("hdlr", handler), box("minf", box("stbl", box("stsd", entry.array())))))))));
    }

    static byte[] supportedMp4() throws Exception { return singleSampleMp4(true); }

    private static byte[] singleSampleMp4() throws Exception { return singleSampleMp4(false); }

    private static byte[] singleSampleMp4(boolean avc) throws Exception {
        // Generated Motion JPEG sample; no fixture download, decoder or native process.
        var raster = new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var sampleOutput = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(raster, "jpeg", sampleOutput);
        // AVC fixture proves custody/configuration framing, NOT codec bitstream validity.
        byte[] sample = avc ? new byte[]{0, 0, 0, 4, 0x65, (byte)0x88, (byte)0x84, 0} : sampleOutput.toByteArray();
        byte[] codecConfig = avc ? box("avcC", new byte[]{1, 66, 0, 10, (byte)255, (byte)225,
                0, 4, 0x67, 66, 0, 10, 1, 0, 2, 0x68, 0}) : new byte[0];
        byte[] ftyp = box("ftyp", "mp42\0\0\0\0mp42".getBytes(StandardCharsets.US_ASCII));
        var movie = ByteBuffer.allocate(100).putInt(0).putInt(0).putInt(0)
                .putInt(1000).putInt(1000);
        movie.putInt(0x10000).putShort((short)0x100).position(36);
        movie.putInt(0x10000).putInt(0).putInt(0).putInt(0).putInt(0x10000).putInt(0)
                .putInt(0).putInt(0).putInt(0x40000000).position(96);
        movie.putInt(2);
        var track = ByteBuffer.allocate(84).putInt(7).putInt(0).putInt(0).putInt(1).putInt(0).putInt(1000);
        track.position(40); track.putInt(0x10000).putInt(0).putInt(0).putInt(0).putInt(0x10000).putInt(0)
                .putInt(0).putInt(0).putInt(0x40000000).putInt(16 << 16).putInt(16 << 16);
        byte[] mediaHeader = ByteBuffer.allocate(24).putInt(0).putInt(0).putInt(0)
                .putInt(1000).putInt(1000).putShort((short)0x55c4).putShort((short)0).array();
        byte[] handler = ByteBuffer.allocate(25).putInt(0).putInt(0)
                .put("vide".getBytes(StandardCharsets.US_ASCII)).array();
        var description = ByteBuffer.allocate(94).putInt(0).putInt(1).putInt(86 + codecConfig.length)
                .put((avc ? "avc1" : "jpeg").getBytes(StandardCharsets.US_ASCII));
        description.position(22); description.putShort((short)1);
        description.position(40); description.putShort((short)16).putShort((short)16)
                .putInt(72 << 16).putInt(72 << 16).putInt(0).putShort((short)1);
        description.position(90); description.putShort((short)24).putShort((short)-1);
        // mdat precedes moov, so the single chunk starts immediately after its header.
        byte[] table = box("stbl", concatenate(box("stsd", concatenate(description.array(), codecConfig)),
                box("stts", integers(0, 1, 1, 1000)), box("stsc", integers(0, 1, 1, 1, 1)),
                box("stsz", integers(0, 0, 1, sample.length)), box("stco", integers(0, 1, ftyp.length + 8))));
        byte[] dataInformation = box("dinf", box("dref", concatenate(integers(0, 1), box("url ", integers(1)))));
        byte[] media = box("mdia", concatenate(box("mdhd", mediaHeader), box("hdlr", handler),
                box("minf", concatenate(box("vmhd", integers(1, 0, 0)), dataInformation, table))));
        return concatenate(ftyp, box("mdat", sample), box("moov", concatenate(box("mvhd", movie.array()),
                box("trak", concatenate(box("tkhd", track.array()), media)))));
    }

    private static byte[] integers(int... values) {
        var buffer = ByteBuffer.allocate(values.length * 4);
        for (int value : values) buffer.putInt(value);
        return buffer.array();
    }

    private static byte[] box(String type, byte[] payload) {
        return ByteBuffer.allocate(payload.length + 8).putInt(payload.length + 8)
                .put(type.getBytes(StandardCharsets.US_ASCII)).put(payload).array();
    }

    private static byte[] concatenate(byte[]... values) throws Exception {
        var output = new ByteArrayOutputStream();
        for (var value : values) output.write(value);
        return output.toByteArray();
    }
}
