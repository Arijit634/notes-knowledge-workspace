package org.notesknowledge.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.notesknowledge.websupport.ApiFailureException;

@Tag("FAST")
class AvatarValidatorTest {
    private final AvatarValidator validator = new AvatarValidator();

    @ParameterizedTest @ValueSource(strings = {"png", "jpeg"})
    void canonicalFormatAndMetadataComeFromBytesNotFilename(String format) throws Exception {
        var result = validate(AvatarImages.image(format), "misleading.svg");
        assertThat(result.mediaType()).isEqualTo("image/" + format);
        assertThat(result.width()).isEqualTo(3); assertThat(result.height()).isEqualTo(2);
        assertThat(ImageIO.read(new ByteArrayInputStream(result.bytes())).getWidth()).isEqualTo(3);
        assertThat(result.toString()).isEqualTo("CanonicalAvatar[REDACTED]");
    }

    @Test void stripsMetadataAndTrailingPolyglotMaterial() throws Exception {
        String canary = "SYNTHETIC_UNTRUSTED_METADATA_CANARY";
        byte[] text = AvatarImages.pngWithText(canary), tail = "<script>SYNTHETIC_TRAILER</script>".getBytes(StandardCharsets.US_ASCII);
        byte[] input = Arrays.copyOf(text, text.length + tail.length);
        System.arraycopy(tail, 0, input, text.length, tail.length);
        var result = validate(input, "fixture.png");
        assertThat(new String(result.bytes(), StandardCharsets.ISO_8859_1)).doesNotContain(canary, "SYNTHETIC_TRAILER", "tEXt");
        assertThat(ImageIO.read(new ByteArrayInputStream(result.bytes())).getHeight()).isEqualTo(2);
    }

    @Test void streamBoundDoesNotNeedCallerSizeOrContentLength() {
        expect(new byte[AvatarValidator.SOURCE_BYTES + 1], "fixture.png", ApiFailureException.Kind.REQUEST_TOO_LARGE);
    }

    @Test void rejectsUnsupportedActualBytesDespiteClaimedFilename() {
        for (String source : new String[] {"<svg/>", "GIF89a", "<html>", "RIFF0000WEBP", ""}) {
            expect(source.getBytes(StandardCharsets.US_ASCII), "fixture.png", ApiFailureException.Kind.UNSUPPORTED_MEDIA_TYPE);
        }
    }

    @Test void malformedSupportedImagesAreValidationFailures() throws Exception {
        expect(new byte[] {(byte)137,80,78,71,13,10,26,10}, "fixture.png", ApiFailureException.Kind.INVALID_INPUT);
        expect(new byte[] {(byte)255,(byte)216,(byte)255}, "fixture.jpg", ApiFailureException.Kind.INVALID_INPUT);
        byte[] jpeg = AvatarImages.image("jpeg");
        expect(Arrays.copyOf(jpeg, jpeg.length / 2), "fixture.jpg", ApiFailureException.Kind.INVALID_INPUT);
    }

    @ParameterizedTest @ValueSource(ints = {4097, 50000, 0})
    void dimensionsAreRejectedBeforeRasterDecode(int width) throws Exception {
        expect(AvatarImages.dimensions(width, 2), "fixture.png", ApiFailureException.Kind.INVALID_INPUT);
    }

    @Test void pixelBoundIsIndependentOfIndividualDimensionBound() throws Exception {
        expect(AvatarImages.dimensions(4096, 4096), "fixture.png", ApiFailureException.Kind.INVALID_INPUT);
    }

    @Test void filenameControlsAndBoundsAreRejectedWithoutEcho() throws Exception {
        byte[] png = AvatarImages.image("png");
        for (String name : new String[] {"x".repeat(256), "bad\r\nname.png", "\uD800"}) expect(png, name, ApiFailureException.Kind.INVALID_INPUT);
        assertThat(validate(png, null).filename()).isEmpty();
    }

    @Test void canonicalOutputHasAnIndependentHardLimit() {
        var output = new AvatarValidator.BoundedImageOutput(4);
        output.write(new byte[4], 0, 4);
        assertThatThrownBy(() -> output.write(1)).isInstanceOf(RuntimeException.class);
        assertThat(output.bytes()).hasSize(4);
    }

    @Test void jpegAndPngEncodersUseTheBoundedSeekableOutput() throws Exception {
        for (String format : new String[] {"png", "jpeg"}) {
            var writer = ImageIO.getImageWritersByFormatName(format).next();
            try (var output = new AvatarValidator.BoundedImageOutput(16)) {
                writer.setOutput(output);
                var image = new java.awt.image.BufferedImage(3,2,java.awt.image.BufferedImage.TYPE_INT_RGB);
                assertThatThrownBy(() -> writer.write(null,new javax.imageio.IIOImage(image,null,null),writer.getDefaultWriteParam()))
                        .isInstanceOf(Exception.class);
                assertThat(output.bytes().length).isLessThanOrEqualTo(16);
            } finally {writer.dispose();}
        }
    }

    private AvatarValidator.Canonical validate(byte[] bytes, String name) { return validator.validate(new ByteArrayInputStream(bytes), name); }
    private void expect(byte[] bytes, String name, ApiFailureException.Kind kind) {
        assertThatThrownBy(() -> validate(bytes, name)).isInstanceOfSatisfying(ApiFailureException.class,
                error -> assertThat(error.kind()).isEqualTo(kind));
    }
}
