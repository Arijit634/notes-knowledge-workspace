package org.notesknowledge.notes;

import java.nio.file.Path;

/** Test-only entrypoint loaded externally by Boot PropertiesLauncher; never shipped in the JAR. */
public final class AttachmentPackagedForkProbe {
    public static void main(String[] arguments) {
        Path custody = null;
        try {
            custody = AttachmentParserRuntime.privateDirectory();
            Path pdf = custody.resolve("synthetic.pdf"), mp4 = custody.resolve("synthetic.mp4");
            try (var document = new org.apache.pdfbox.pdmodel.PDDocument()) {
                document.addPage(new org.apache.pdfbox.pdmodel.PDPage());
                document.save(pdf.toFile());
            }
            java.nio.file.Files.write(mp4, AttachmentParserPreflightTest.supportedMp4());
            try (var runtime = new AttachmentParserRuntime()) {
            if (!"application/pdf".equals(runtime.parse(pdf).get("Content-Type"))) {
                throw new IllegalStateException("PDF qualification failed");
            }
            if (!"video/mp4".equals(runtime.parse(mp4).get("Content-Type"))) {
                throw new IllegalStateException("MP4 qualification failed");
            }
            System.out.println("PACKAGED_ATTACHMENT_FORK_OK");
            }
        } catch (Exception failure) {
            System.err.println("PACKAGED_ATTACHMENT_FORK_FAILED");
            System.exit(1);
        } finally {
            if (custody != null) AttachmentParserRuntime.removeDirectory(custody);
        }
    }
}
