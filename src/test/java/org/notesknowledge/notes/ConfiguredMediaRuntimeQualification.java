package org.notesknowledge.notes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

/** Native, packaged Spring factory qualification, never a production entry point. */
public final class ConfiguredMediaRuntimeQualification {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Qualification roots required");
        var pdf = Path.of(args[0]);
        var video = Path.of(args[1]);
        var mode = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));
        var pdfRecords = Files.createTempDirectory("configured-pdf-", mode);
        var videoRecords = Files.createTempDirectory("configured-video-records-", mode);
        var scratch = Files.createTempDirectory("configured-video-", mode);
        var values = new HashMap<String, Object>();
        select(values, "pdf", pdf, pdfRecords, PdfRuntimeIdentity.fingerprint(pdf.resolve("runtime"),
                pdf.resolve("artifacts"), pdf.resolve("helper")), "native-pdf-v1");
        select(values, "video", video, videoRecords, SupervisedVideoClipRuntime.identity(video.resolve("helper"),
                video.resolve("runtime"), video.resolve("artifacts"), video.resolve("tools")), "ffmpeg-9.0.2-profile-v1");
        values.put("notes.media-runtime.video.scratch-directory", scratch.toString());
        try (var disabled = new AnnotationConfigApplicationContext(MediaRuntimeConfiguration.class)) {
            if (!disabled.getBeansOfType(PdfPageRuntime.class).isEmpty()
                    || !disabled.getBeansOfType(VideoClipRuntime.class).isEmpty()) throw failed();
        }
        try (var configured = new AnnotationConfigApplicationContext()) {
            configured.getEnvironment().getPropertySources().addFirst(new MapPropertySource("synthetic-native-deployment", values));
            configured.register(MediaRuntimeConfiguration.class);
            configured.refresh();
            var pages = configured.getBean(PdfPageRuntime.class);
            try (var child = pages.render(mixedPdf(), 2)) {
                var image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(child.bytes()));
                if (child.page() != 2 || image == null || (image.getRGB(32, 32) & 0xffffff) != 0x0000ff) throw failed();
                image.flush();
            }
            var clips = configured.getBean(VideoClipRuntime.class);
            byte[] original = Files.readAllBytes(video.resolve("tools/preflight.mp4"));
            try {
                var plan = clips.plan(original);
                try (var child = clips.render(original, plan, 0, plan.durations().size())) {
                    if (!child.plan().equals(plan)) throw failed();
                }
            } finally { java.util.Arrays.fill(original, (byte) 0); }
        }
        try (var files = Files.list(scratch)) { if (files.findAny().isPresent()) throw failed(); }
        for (var records : java.util.List.of(pdfRecords, videoRecords)) {
            try (var files = Files.list(records)) {
                if (!files.map(p -> p.getFileName().toString()).toList().equals(java.util.List.of("worker.lock"))) throw failed();
            }
            Files.delete(records.resolve("worker.lock")); Files.delete(records);
        }
        Files.delete(scratch);
        System.out.println("{\"passed\":true,\"configuredSpringFactories\":true,\"packagedProductionClasses\":true,\"selectedMixedPdfPage\":true,\"videoStartupAndRender\":true,\"providerCalls\":0}");
    }
    private static void select(HashMap<String, Object> values, String kind, Path root, Path records, String identity, String version) {
        String prefix = "notes.media-runtime." + kind + ".";
        values.put(prefix + "enabled", "true"); values.put(prefix + "profile", "linux-confined-v1");
        values.put(prefix + "root", root.toString()); values.put(prefix + "recovery-directory", records.toString());
        values.put(prefix + "artifact-identity", identity); values.put(prefix + "version", version);
    }
    private static byte[] mixedPdf() throws Exception {
        try (var document = new org.apache.pdfbox.pdmodel.PDDocument(); var out = new java.io.ByteArrayOutputStream()) {
            var text = new org.apache.pdfbox.pdmodel.PDPage(new org.apache.pdfbox.pdmodel.common.PDRectangle(64,64));
            document.addPage(text);
            try (var content = new org.apache.pdfbox.pdmodel.PDPageContentStream(document, text)) {
                content.beginText();
                content.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 9);
                content.newLineAtOffset(2, 32); content.showText("Excluded first page"); content.endText();
            }
            var scan = new org.apache.pdfbox.pdmodel.PDPage(new org.apache.pdfbox.pdmodel.common.PDRectangle(64,64)); document.addPage(scan);
            var bitmap = new java.awt.image.BufferedImage(64,64,java.awt.image.BufferedImage.TYPE_INT_RGB);
            var graphics = bitmap.createGraphics(); graphics.setColor(java.awt.Color.BLUE); graphics.fillRect(0,0,64,64); graphics.dispose();
            try (var content = new org.apache.pdfbox.pdmodel.PDPageContentStream(document,scan)) {
                content.drawImage(org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(document,bitmap),0,0,64,64);
            } finally { bitmap.flush(); }
            document.save(out); return out.toByteArray();
        }
    }
    private static IllegalStateException failed() { return new IllegalStateException("Configured media runtime qualification failed"); }
}
