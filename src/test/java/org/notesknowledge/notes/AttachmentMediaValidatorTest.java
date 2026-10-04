package org.notesknowledge.notes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionLaunch;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.notesknowledge.websupport.ApiFailureException;

@Tag("FAST")
class AttachmentMediaValidatorTest {
    @TempDir Path directory;

    @Test void acceptsFourKindsWithTrustedApplicableMetadata() throws Exception {
        try (var validator = new AttachmentMediaValidator()) {
            Path png = directory.resolve("synthetic.png");
            javax.imageio.ImageIO.write(new BufferedImage(16, 12, BufferedImage.TYPE_INT_RGB), "png", png.toFile());
            var image = validator.validate(png, "synthetic.png", "image/png");
            assertThat(image.kind()).isEqualTo("image"); assertThat(image.width()).isEqualTo(16);
            assertThat(image.height()).isEqualTo(12); assertThat(image.durationSeconds()).isNull();
            Path wav = Files.write(directory.resolve("synthetic.wav"), wav(16000, 1, 16000));
            var audio = validator.validate(wav, "synthetic.wav", "audio/wav");
            assertThat(audio.kind()).isEqualTo("audio"); assertThat(audio.durationSeconds()).isEqualTo(1.0);
            Path mp4 = Files.write(directory.resolve("synthetic.mp4"), AttachmentParserPreflightTest.supportedMp4());
            var video = validator.validate(mp4, "synthetic.mp4", "video/mp4");
            assertThat(video.kind()).isEqualTo("video"); assertThat(video.width()).isEqualTo(16);
            assertThat(video.durationSeconds()).isEqualTo(1.0);
            Path pdf = pdf(1, false);
            var document = validator.validate(pdf, "synthetic.pdf", "application/pdf");
            assertThat(document.kind()).isEqualTo("pdf"); assertThat(document.pageCount()).isEqualTo(1);
        }
    }

    @Test void validatesAtPdfPageLimitAndRejectsExcessPagesAndActions() throws Exception {
        try (var validator = new AttachmentMediaValidator()) {
            assertThat(validator.validate(pdf(100, false), "synthetic.pdf", "application/pdf").pageCount()).isEqualTo(100);
            invalid(() -> validator.validate(pdf(101, false), "synthetic.pdf", "application/pdf"));
            invalid(() -> validator.validate(pdf(1, true), "synthetic.pdf", "application/pdf"));
        }
    }

    @Test void wavContainerTimingAndExactEofAreMandatory() throws Exception {
        try (var validator = new AttachmentMediaValidator()) {
            byte[] good = wav(16000, 1, 16000);
            for (int truncated : new int[]{1, 2, 7, 12}) {
                Path path = Files.write(directory.resolve("synthetic.wav"), java.util.Arrays.copyOf(good, good.length - truncated));
                invalid(() -> validator.validate(path, "synthetic.wav", "audio/wav"));
            }
            ByteBuffer.wrap(good).order(ByteOrder.LITTLE_ENDIAN).putInt(24, 1);
            Path path = Files.write(directory.resolve("synthetic.wav"), good);
            invalid(() -> validator.validate(path, "synthetic.wav", "audio/wav"));
        }
    }

    @Test void rejectsSpoofedHintsAndUnsupportedFifthKind() throws Exception {
        Path png = directory.resolve("synthetic.png");
        javax.imageio.ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", png.toFile());
        try (var validator = new AttachmentMediaValidator()) {
            invalid(() -> validator.validate(png, "synthetic.pdf", "image/png"));
            invalid(() -> validator.validate(png, "synthetic.png", "application/pdf"));
            Path text = Files.writeString(directory.resolve("synthetic.txt"), "synthetic unsupported bytes");
            assertThatThrownBy(() -> validator.validate(text, "synthetic.txt", "text/plain"))
                    .isInstanceOfSatisfying(ApiFailureException.class,
                            failure -> assertThat(failure.kind()).isEqualTo(ApiFailureException.Kind.UNSUPPORTED_MEDIA_TYPE));
        }
    }

    @Test void outerFileLimitPrecedesDangerousParsing() throws Exception {
        Path path = directory.resolve("oversized.bin");
        try (var file = new java.io.RandomAccessFile(path.toFile(), "rw")) {
            file.setLength(AttachmentMediaValidator.VIDEO_BYTES + 1L);
        }
        try (var validator = new AttachmentMediaValidator()) {
            assertThatThrownBy(() -> validator.validate(path, "synthetic.pdf", "application/pdf"))
                    .isInstanceOfSatisfying(ApiFailureException.class,
                            failure -> assertThat(failure.kind()).isEqualTo(ApiFailureException.Kind.REQUEST_TOO_LARGE));
        }
    }

    @Test void imageCanonicalizationRemovesTrailingContentAndRejectsPixelBombBeforeDecode() throws Exception {
        Path path = directory.resolve("canonical.png");
        javax.imageio.ImageIO.write(new BufferedImage(16, 12, BufferedImage.TYPE_INT_RGB), "png", path.toFile());
        byte[] canonical = Files.readAllBytes(path);
        Files.writeString(path, "<script>synthetic-inert-tail</script>", java.nio.file.StandardOpenOption.APPEND);
        try (var validator = new AttachmentMediaValidator()) {
            assertThat(validator.validate(path, "synthetic.png", "image/png").sizeBytes()).isEqualTo(Files.size(path));
            assertThat(new String(Files.readAllBytes(path), java.nio.charset.StandardCharsets.ISO_8859_1))
                    .doesNotContain("synthetic-inert-tail", "<script>");
            assertThat(javax.imageio.ImageIO.read(path.toFile()).getWidth()).isEqualTo(16);
            // A tiny header declares disallowed raster dimensions; no pixel allocation is needed.
            byte[] huge = canonical.clone();
            ByteBuffer.wrap(huge).putInt(16, 4096).putInt(20, 4096);
            var crc = new java.util.zip.CRC32(); crc.update(huge, 12, 17);
            ByteBuffer.wrap(huge).putInt(29, (int)crc.getValue());
            Files.write(path, huge);
            invalid(() -> validator.validate(path, "synthetic.png", "image/png"));
        }
    }

    @Test void audioDurationIsBoundedIndependentlyOfByteLimit() throws Exception {
        Path path = Files.write(directory.resolve("duration.wav"), wav(8000, 1, 8000 * 300));
        try (var validator = new AttachmentMediaValidator()) {
            assertThat(validator.validate(path, "synthetic.wav", "audio/wav").durationSeconds()).isEqualTo(300);
            Files.write(path, wav(8000, 1, 8000 * 301));
            invalid(() -> validator.validate(path, "synthetic.wav", "audio/wav"));
        }
    }

    @Test void productionPdfGateRejectsEncryptionEmbeddedFilesFormsAndMalformedStructure() throws Exception {
        try (var validator = new AttachmentMediaValidator()) {
            for (String kind : java.util.List.of("encrypted", "embedded", "form")) {
                Path path = directory.resolve(kind + ".pdf");
                try (var document = new PDDocument()) {
                    document.addPage(new PDPage());
                    switch (kind) {
                        case "encrypted" -> document.protect(new org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy(
                                "synthetic-owner-password", "synthetic-user-password", new org.apache.pdfbox.pdmodel.encryption.AccessPermission()));
                        case "form" -> {
                            var form = new org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm(document);
                            var field = new org.apache.pdfbox.pdmodel.interactive.form.PDTextField(form);
                            field.setPartialName("syntheticField"); form.getFields().add(field);
                            document.getDocumentCatalog().setAcroForm(form);
                        }
                        case "embedded" -> {
                            var specification = new org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification();
                            specification.setFile("synthetic.txt");
                            specification.setEmbeddedFile(new org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile(
                                    document, new java.io.ByteArrayInputStream(new byte[]{65, 66, 67})));
                            var tree = new org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode();
                            tree.setNames(java.util.Map.of("synthetic.txt", specification));
                            var names = new org.apache.pdfbox.pdmodel.PDDocumentNameDictionary(document.getDocumentCatalog());
                            names.setEmbeddedFiles(tree); document.getDocumentCatalog().setNames(names);
                        }
                        default -> throw new AssertionError("Synthetic PDF kind");
                    }
                    document.save(path.toFile());
                }
                invalid(() -> validator.validate(path, "synthetic.pdf", "application/pdf"));
            }
            Path malformed = Files.writeString(directory.resolve("malformed.pdf"), "%PDF-1.4\nsynthetic incomplete document");
            invalid(() -> validator.validate(malformed, "synthetic.pdf", "application/pdf"));
        }
    }

    private Path pdf(int pages, boolean action) throws Exception {
        Path path = directory.resolve("synthetic-" + pages + "-" + action + ".pdf");
        try (var document = new PDDocument()) {
            for (int i = 0; i < pages; i++) document.addPage(new PDPage());
            if (action) {
                var launch = new PDActionLaunch(); launch.setF("synthetic-never-executed.exe");
                document.getDocumentCatalog().setOpenAction(launch);
            }
            document.save(path.toFile());
        }
        return path;
    }

    static byte[] wav(int rate, int channels, int frames) {
        int size = frames * channels * 2;
        return ByteBuffer.allocate(44 + size).order(ByteOrder.LITTLE_ENDIAN)
                .put("RIFF".getBytes()).putInt(36 + size).put("WAVEfmt ".getBytes()).putInt(16)
                .putShort((short)1).putShort((short)channels).putInt(rate).putInt(rate * channels * 2)
                .putShort((short)(channels * 2)).putShort((short)16).put("data".getBytes()).putInt(size).array();
    }

    private void invalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ApiFailureException.class,
                failure -> assertThat(failure.kind()).isEqualTo(ApiFailureException.Kind.INVALID_INPUT));
    }
}
