package org.notesknowledge.profile;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import javax.imageio.ImageIO;
import javax.imageio.IIOImage;
import javax.imageio.stream.ImageOutputStreamImpl;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.stereotype.Component;

@Component
class AvatarValidator {
    static final int SOURCE_BYTES = 5 * 1024 * 1024;
    static final int OUTPUT_BYTES = 5 * 1024 * 1024;
    static final int DIMENSION = 4096;
    static final long PIXELS = 4_194_304;
    private static final byte[] PNG = {(byte)137, 80, 78, 71, 13, 10, 26, 10};

    record Canonical(byte[] bytes, String mediaType, int width, int height, String filename) {
        @Override public String toString() { return "CanonicalAvatar[REDACTED]"; }
    }

    Canonical validate(InputStream source, String filename) {
        String display = filename == null ? "" : filename;
        if (display.codePointCount(0, display.length()) > 255
                || display.codePoints().anyMatch(c -> Character.isISOControl(c) || c >= 0xD800 && c <= 0xDFFF)) {
            throw failure(ApiFailureException.Kind.INVALID_INPUT);
        }
        try {
            // Read one extra byte regardless of declared size or Content-Length.
            byte[] input = source.readNBytes(SOURCE_BYTES + 1);
            if (input.length > SOURCE_BYTES) throw failure(ApiFailureException.Kind.REQUEST_TOO_LARGE);
            String format;
            if (input.length >= 8 && Arrays.equals(Arrays.copyOf(input, 8), PNG)) format = "png";
            else if (input.length >= 3 && input[0] == (byte)0xff && input[1] == (byte)0xd8 && input[2] == (byte)0xff) format = "jpeg";
            else throw failure(ApiFailureException.Kind.UNSUPPORTED_MEDIA_TYPE);

            var reader = ImageIO.getImageReadersByFormatName(format).next();
            try (var imageInput = new MemoryCacheImageInputStream(new ByteArrayInputStream(input))) {
                boolean[] warned = {false};
                reader.addIIOReadWarningListener((ignored, warning) -> warned[0] = true);
                // Source metadata is never loaded into the output image/encoder.
                reader.setInput(imageInput, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > DIMENSION || height > DIMENSION
                        || (long)width * height > PIXELS) throw failure(ApiFailureException.Kind.INVALID_INPUT);
                BufferedImage decoded = reader.read(0);
                if (decoded == null || warned[0]) throw failure(ApiFailureException.Kind.INVALID_INPUT);
                BufferedImage fresh = new BufferedImage(width, height,
                        format.equals("png") ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
                var graphics = fresh.createGraphics();
                try { graphics.drawImage(decoded, 0, 0, null); } finally { graphics.dispose(); decoded.flush(); }
                try {
                    var writer = ImageIO.getImageWritersByFormatName(format).next();
                    try (var output = new BoundedImageOutput(OUTPUT_BYTES)) {
                        writer.setOutput(output);
                        writer.write(null, new IIOImage(fresh, null, null), writer.getDefaultWriteParam());
                        return new Canonical(output.bytes(), "image/" + format, width, height, display);
                    } finally { writer.dispose(); }
                } finally { fresh.flush(); }
            } finally { reader.dispose(); }
        } catch (OutputTooLarge exception) { throw failure(ApiFailureException.Kind.REQUEST_TOO_LARGE); }
        catch (IOException | IllegalArgumentException exception) {
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof OutputTooLarge) throw failure(ApiFailureException.Kind.REQUEST_TOO_LARGE);
            }
            throw failure(ApiFailureException.Kind.INVALID_INPUT);
        }
    }

    /** The encoder's seekable buffer itself is bounded, with no ImageIO disk cache. */
    static final class BoundedImageOutput extends ImageOutputStreamImpl {
        private final byte[] buffer;
        private int size;
        BoundedImageOutput(int limit) { buffer = new byte[limit]; }
        @Override public void write(int value) {
            require(1); buffer[(int)streamPos++] = (byte)value; size = Math.max(size, (int)streamPos);
        }
        @Override public void write(byte[] bytes, int offset, int length) {
            require(length); System.arraycopy(bytes, offset, buffer, (int)streamPos, length);
            streamPos += length; size = Math.max(size, (int)streamPos);
        }
        @Override public int read() { return streamPos >= size ? -1 : buffer[(int)streamPos++] & 255; }
        @Override public int read(byte[] bytes, int offset, int length) {
            if (length == 0) return 0;
            if (streamPos >= size) return -1;
            int count = (int)Math.min(length, size - streamPos);
            System.arraycopy(buffer, (int)streamPos, bytes, offset, count); streamPos += count; return count;
        }
        byte[] bytes() { return Arrays.copyOf(buffer, size); }
        private void require(int added) {
            if (added < 0 || streamPos < 0 || streamPos > buffer.length || added > buffer.length - streamPos) throw new OutputTooLarge();
        }
    }
    private static final class OutputTooLarge extends RuntimeException { }
    private static ApiFailureException failure(ApiFailureException.Kind kind) { return ApiFailureException.of(kind); }
}
