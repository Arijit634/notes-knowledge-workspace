package org.notesknowledge.notes;

import java.nio.file.Files;
import java.nio.file.Path;
import org.notesknowledge.knowledge.spi.PrivateDerivationSource.PdfChild;

/** Build-time metadata only; excluded from the production JAR. Does not enable
 * processing, attest platform qualification, approve media or clear codec rights. */
public final class MediaRuntimeImageDescriptor {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Output metadata path required");
        Path pdf = Path.of("/opt/pdf"), video = Path.of("/opt/video");
        String pdfIdentity = PdfRuntimeIdentity.fingerprint(pdf.resolve("runtime"),pdf.resolve("artifacts"),pdf.resolve("helper"));
        String videoIdentity = SupervisedVideoClipRuntime.identity(video.resolve("helper"),video.resolve("runtime"),video.resolve("artifacts"),video.resolve("tools"));
        String text = "# Artifact metadata only. Operator must verify the candidate image and qualification evidence.\n"
                + "# Neither runtime is enabled by this file; no provider or source approval is included.\n"
                + "notes.media-runtime.pdf.profile=linux-confined-v1\nnotes.media-runtime.pdf.root=/opt/pdf\n"
                + "notes.media-runtime.pdf.version=native-pdf-v1\nnotes.media-runtime.pdf.artifact-identity="+pdfIdentity+"\n"
                + "notes.media-runtime.video.profile=linux-confined-v1\nnotes.media-runtime.video.root=/opt/video\n"
                + "notes.media-runtime.video.version=ffmpeg-9.0.2-profile-v1\nnotes.media-runtime.video.artifact-identity="+videoIdentity+"\n"
                + "knowledge.derivation.pdf-transform-fingerprint="+PdfChild.transformFingerprint(PdfPageRasterWorker.PROFILE,"native-pdf-v1",pdfIdentity,
                        PdfPageWorkerProtocol.digest(PdfPageRasterWorker.CONFIGURATION.getBytes(java.nio.charset.StandardCharsets.UTF_8)))+"\n"
                + "knowledge.derivation.video-transform-fingerprint="+PdfChild.transformFingerprint(SupervisedVideoClipRuntime.PROFILE,"ffmpeg-9.0.2-profile-v1",videoIdentity,
                        PdfPageWorkerProtocol.digest(SupervisedVideoClipRuntime.CONFIGURATION.getBytes(java.nio.charset.StandardCharsets.UTF_8)))+"\n";
        Files.writeString(Path.of(args[0]),text,java.nio.file.StandardOpenOption.CREATE_NEW);
    }
}
