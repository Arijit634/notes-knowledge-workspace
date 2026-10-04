package org.notesknowledge.notes;

import jakarta.annotation.PreDestroy;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Locale;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.stream.ImageOutputStreamImpl;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.sound.sampled.AudioSystem;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.PDF;
import org.apache.tika.metadata.PagedText;
import org.apache.tika.metadata.TIFF;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.metadata.XMPDM;
import org.notesknowledge.websupport.ApiFailureException;

/** Bounded validation only. No media extraction, provider dispatch or content indexing. */
final class AttachmentMediaValidator implements AutoCloseable {
    static final int IMAGE_BYTES = 5 * 1024 * 1024, AUDIO_BYTES = 10 * 1024 * 1024;
    static final int PDF_BYTES = 10 * 1024 * 1024, VIDEO_BYTES = 25 * 1024 * 1024;
    static final int DIMENSION = 4096, PAGES = 100, SECONDS = 300;
    static final long PIXELS = 8_294_400;
    private AttachmentParserRuntime runtime;

    record Validated(String kind, String mediaType, long sizeBytes, Integer width, Integer height,
            Double durationSeconds, Integer pageCount) { }

    Validated validate(Path custody, String displayFilename, String declaredType) {
        try {
            long length = Files.size(custody);
            if (length == 0) throw invalid();
            if (length > VIDEO_BYTES) throw tooLarge();
            byte[] signature;
            try (var input = Files.newInputStream(custody)) { signature = input.readNBytes(12); }
            Validated validated;
            if (signature.length >= 8 && Arrays.equals(Arrays.copyOf(signature, 8),
                    new byte[]{(byte)137, 80, 78, 71, 13, 10, 26, 10})) {
                bounded(length, IMAGE_BYTES); validated = image(custody, "png");
            } else if (signature.length >= 3 && signature[0] == (byte)255
                    && signature[1] == (byte)216 && signature[2] == (byte)255) {
                bounded(length, IMAGE_BYTES); validated = image(custody, "jpeg");
            } else if (signature.length >= 12 && ascii(signature, 0, 4).equals("RIFF")
                    && ascii(signature, 8, 4).equals("WAVE")) {
                bounded(length, AUDIO_BYTES); validated = audio(custody, length);
            } else if (signature.length >= 5 && ascii(signature, 0, 5).equals("%PDF-")) {
                bounded(length, PDF_BYTES); validated = pdf(custody, length);
            } else if (signature.length >= 8 && ascii(signature, 4, 4).equals("ftyp")) {
                validated = video(custody, length);
            } else throw ApiFailureException.of(ApiFailureException.Kind.UNSUPPORTED_MEDIA_TYPE);
            hints(validated.mediaType(), displayFilename, declaredType);
            return validated;
        } catch (ApiFailureException failure) { throw failure; }
        catch (IOException failure) { throw unavailable(); }
    }

    private Validated video(Path path, long size) {
        var structural = new StrictMp4StructureValidator().validate(path);
        Metadata metadata = parser().parse(path);
        requireType(metadata, "video/mp4");
        try {
            int width = Integer.parseInt(metadata.get(TIFF.IMAGE_WIDTH));
            int height = Integer.parseInt(metadata.get(TIFF.IMAGE_LENGTH));
            double duration = Double.parseDouble(metadata.get(XMPDM.DURATION));
            if (width != structural.width() || height != structural.height() || !Double.isFinite(duration)
                    || Math.abs(duration - structural.durationSeconds()) > 0.001) throw invalid();
            return new Validated("video", "video/mp4", size, width, height, duration, null);
        } catch (NumberFormatException | NullPointerException failure) { throw invalid(); }
    }

    private Validated pdf(Path path, long size) {
        Metadata metadata = parser().parse(path);
        requireType(metadata, "application/pdf");
        Integer pages = boundedInteger(metadata, PagedText.N_PAGES);
        if (pages == null || pages < 1 || pages > PAGES || !"false".equals(metadata.get(PDF.IS_ENCRYPTED))) throw invalid();
        for (var property : java.util.List.of(PDF.HAS_XFA, PDF.HAS_ACROFORM_FIELDS, PDF.HAS_SIGNATURE_FIELDS,
                PDF.HAS_COLLECTION, PDF.HAS_3D)) {
            if ("true".equals(metadata.get(property))) throw invalid();
        }
        for (var property : java.util.List.of(PDF.ACTION_TYPES, PDF.ACTION_TRIGGERS, PDF.ANNOTATION_TYPES,
                PDF.ANNOTATION_SUBTYPES, PDF.EMBEDDED_FILE_DESCRIPTION, PDF.ASSOCIATED_FILE_RELATIONSHIP,
                TikaCoreProperties.EMBEDDED_RESOURCE_LIMIT_REACHED, TikaCoreProperties.EMBEDDED_DEPTH_LIMIT_REACHED,
                TikaCoreProperties.TIKA_META_EXCEPTION_EMBEDDED_STREAM)) {
            if (metadata.getValues(property).length != 0) throw invalid();
        }
        Integer threeD = boundedInteger(metadata, PDF.NUM_3D_ANNOTATIONS);
        if (threeD != null && threeD != 0) throw invalid();
        return new Validated("pdf", "application/pdf", size, null, null, null, pages);
    }

    private Validated audio(Path path, long size) {
        // Deliberately only canonical RIFF PCM WAV: one 16-byte fmt then one data
        // chunk, exact EOF, no nested/unknown chunks or compressed codec dispatch.
        if (size < 44) throw invalid();
        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        try (var channel = FileChannel.open(path, StandardOpenOption.READ)) {
            while (header.hasRemaining()) if (channel.read(header) < 1) throw invalid();
            byte[] bytes = header.array();
            long riff = Integer.toUnsignedLong(header.getInt(4)), payload = Integer.toUnsignedLong(header.getInt(40));
            int channels = Short.toUnsignedInt(header.getShort(22)), rate = header.getInt(24);
            int bits = Short.toUnsignedInt(header.getShort(34)), alignment = Short.toUnsignedInt(header.getShort(32));
            if (!ascii(bytes, 12, 4).equals("fmt ") || header.getInt(16) != 16 || header.getShort(20) != 1
                    || !ascii(bytes, 36, 4).equals("data") || riff + 8 != size || payload + 44 != size
                    || channels < 1 || channels > 2 || rate < 8000 || rate > 48000 || bits != 16
                    || alignment != channels * 2 || header.getInt(28) != rate * alignment
                    || payload == 0 || payload % alignment != 0) throw invalid();
            double duration = (double)payload / alignment / rate;
            if (duration <= 0 || duration > SECONDS) throw invalid();
            try (var sound = AudioSystem.getAudioInputStream(path.toFile())) {
                var format = sound.getFormat();
                if (!format.getEncoding().equals(javax.sound.sampled.AudioFormat.Encoding.PCM_SIGNED)
                        || format.isBigEndian() || format.getChannels() != channels
                        || format.getSampleSizeInBits() != bits || format.getSampleRate() != rate
                        || sound.getFrameLength() != payload / alignment) throw invalid();
            }
            return new Validated("audio", "audio/wav", size, null, null, duration, null);
        } catch (javax.sound.sampled.UnsupportedAudioFileException failure) { throw invalid(); }
        catch (IOException failure) { throw unavailable(); }
    }

    private Validated image(Path path, String format) {
        try {
            byte[] bytes = Files.readAllBytes(path); // Source length was capped at 5 MiB before allocation.
            var reader = ImageIO.getImageReadersByFormatName(format).next();
            try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
                boolean[] warned = {false};
                reader.addIIOReadWarningListener((ignored, text) -> warned[0] = true);
                reader.setInput(input, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > DIMENSION || height > DIMENSION || (long)width * height > PIXELS) throw invalid();
                BufferedImage decoded = reader.read(0);
                if (decoded == null || warned[0]) throw invalid();
                BufferedImage fresh = new BufferedImage(width, height,
                        format.equals("png") ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
                var graphics = fresh.createGraphics();
                try { graphics.drawImage(decoded, 0, 0, null); } finally { graphics.dispose(); decoded.flush(); }
                var writer = ImageIO.getImageWritersByFormatName(format).next();
                try (var output = new BoundedImageOutput()) {
                    writer.setOutput(output);
                    writer.write(null, new IIOImage(fresh, null, null), writer.getDefaultWriteParam());
                    Files.write(path, output.bytes()); // Only a fresh raster is stored, never source metadata/trailing bytes.
                    return new Validated("image", "image/" + format, output.size, width, height, null, null);
                } finally { writer.dispose(); fresh.flush(); }
            } finally { reader.dispose(); }
        } catch (OutputTooLarge failure) { throw tooLarge(); }
        catch (IOException | IllegalArgumentException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) if (cause instanceof OutputTooLarge) throw tooLarge();
            throw invalid();
        }
    }

    private static void hints(String type, String filename, String declaredType) {
        String declaration = declaredType == null ? "" : declaredType.toLowerCase(Locale.ROOT);
        if (!declaration.isEmpty() && !declaration.equals("application/octet-stream") && !declaration.equals(type)
                && !(type.equals("audio/wav") && declaration.equals("audio/x-wav"))) throw invalid();
        int dot = filename.lastIndexOf('.');
        if (dot < 0) return;
        String extension = filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        boolean match = switch (type) {
            case "image/png" -> extension.equals("png");
            case "image/jpeg" -> extension.equals("jpg") || extension.equals("jpeg");
            case "audio/wav" -> extension.equals("wav");
            case "video/mp4" -> extension.equals("mp4");
            case "application/pdf" -> extension.equals("pdf");
            default -> false;
        };
        if (!match) throw invalid();
    }

    private synchronized AttachmentParserRuntime parser() {
        if (runtime == null) runtime = new AttachmentParserRuntime();
        return runtime;
    }
    @Override @PreDestroy public synchronized void close() { if (runtime != null) runtime.close(); runtime = null; }
    private static void requireType(Metadata metadata, String expected) { if (!expected.equals(metadata.get("Content-Type"))) throw invalid(); }
    private static Integer boundedInteger(Metadata metadata, org.apache.tika.metadata.Property property) {
        try { return metadata.getInt(property); }
        catch (NumberFormatException failure) { throw invalid(); }
    }
    private static String ascii(byte[] bytes, int offset, int length) { return new String(bytes, offset, length, java.nio.charset.StandardCharsets.US_ASCII); }
    private static void bounded(long bytes, int limit) { if (bytes > limit) throw tooLarge(); }
    private static ApiFailureException invalid() { return ApiFailureException.of(ApiFailureException.Kind.INVALID_INPUT); }
    private static ApiFailureException tooLarge() { return ApiFailureException.of(ApiFailureException.Kind.REQUEST_TOO_LARGE); }
    private static ApiFailureException unavailable() { return ApiFailureException.of(ApiFailureException.Kind.SERVICE_UNAVAILABLE); }

    private static final class OutputTooLarge extends RuntimeException { }
    private static final class BoundedImageOutput extends ImageOutputStreamImpl {
        private final byte[] buffer = new byte[IMAGE_BYTES];
        private int size;
        private void require(int length) { if (length < 0 || streamPos < 0 || streamPos > buffer.length || length > buffer.length - streamPos) throw new OutputTooLarge(); }
        @Override public void write(int value) { require(1); buffer[(int)streamPos++] = (byte)value; size = Math.max(size, (int)streamPos); }
        @Override public void write(byte[] bytes, int offset, int length) { require(length); System.arraycopy(bytes, offset, buffer, (int)streamPos, length); streamPos += length; size = Math.max(size, (int)streamPos); }
        @Override public int read() { return streamPos >= size ? -1 : buffer[(int)streamPos++] & 255; }
        @Override public int read(byte[] bytes, int offset, int length) {
            if (length == 0) return 0;
            if (streamPos >= size) return -1;
            int count = (int)Math.min(length, size - streamPos); System.arraycopy(buffer, (int)streamPos, bytes, offset, count); streamPos += count; return count;
        }
        byte[] bytes() { return Arrays.copyOf(buffer, size); }
    }
}
