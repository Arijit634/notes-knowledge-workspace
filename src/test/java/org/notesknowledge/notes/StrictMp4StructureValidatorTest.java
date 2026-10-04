package org.notesknowledge.notes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.notesknowledge.websupport.ApiFailureException;

@Tag("FAST")
class StrictMp4StructureValidatorTest {
    @TempDir Path directory;
    private final StrictMp4StructureValidator validator = new StrictMp4StructureValidator();

    @Test void acceptedSampleHasMatchingTimingDimensionsAndCustody() throws Exception {
        var result = validate(AttachmentParserPreflightTest.supportedMp4());
        assertThat(result.width()).isEqualTo(16);
        assertThat(result.height()).isEqualTo(16);
        assertThat(result.durationSeconds()).isEqualTo(1);
    }

    @Test void metadataWithoutSamplesAndMdatIsRejected() throws Exception {
        rejected(AttachmentParserPreflightTest.metadataOnlyMp4());
    }

    @Test void everyPartialTrailingHeaderIsRejected() throws Exception {
        byte[] valid = AttachmentParserPreflightTest.supportedMp4();
        for (int tail = 1; tail <= 7; tail++) rejected(Arrays.copyOf(valid, valid.length + tail));
        for (int missing = 1; missing <= 8; missing++) rejected(Arrays.copyOf(valid, valid.length - missing));
    }

    @Test void topAndNestedFramingCannotOverrunBoundaries() throws Exception {
        rejected(change("ftyp", 0, Integer.MAX_VALUE));
        rejected(change("ftyp", 0, -1));
        rejected(change("moov", 0, 7));
        rejected(change("trak", 0, 100_000));
        rejected(change("stbl", 0, -1));
        rejected(change("ftyp", 0, 0));
        rejected(change("ftyp", 0, 1));
    }

    @Test void absentAndEmptyMediaPayloadCannotBackSamples() throws Exception {
        byte[] valid = AttachmentParserPreflightTest.supportedMp4();
        int start = start(valid, "mdat"), length = ByteBuffer.wrap(valid).getInt(start);
        byte[] absent = new byte[valid.length - length];
        System.arraycopy(valid, 0, absent, 0, start);
        System.arraycopy(valid, start + length, absent, start, valid.length - start - length);
        rejected(absent);
        byte[] empty = new byte[valid.length - length + 8];
        System.arraycopy(valid, 0, empty, 0, start + 8);
        ByteBuffer.wrap(empty).putInt(start, 8);
        System.arraycopy(valid, start + length, empty, start + 8, valid.length - start - length);
        rejected(empty);
    }

    @Test void sampleAndTimingAndChunkCountsMustAgree() throws Exception {
        rejected(change("stsz", 16, 2));
        rejected(change("stts", 16, 2));
        rejected(change("stsc", 16, 2));
        rejected(change("stsc", 20, 2));
        rejected(change("stsc", 24, 2));
        rejected(change("stsz", 16, StrictMp4StructureValidator.MAX_SAMPLES + 1));
        rejected(change("stco", 12, StrictMp4StructureValidator.MAX_CHUNKS + 1));
        rejected(change("stts", 12, -1));
    }

    @Test void sampleRangesCannotEscapeOrCrossMdat() throws Exception {
        rejected(change("stco", 16, 0));
        rejected(change("stco", 16, -1));
        rejected(change("stco", 16, 31)); // mdat payload starts at 28; +8 bytes exceeds its end.
        rejected(change("stsz", 20, 9));
        rejected(change("stsz", 20, -1));
        rejected(change("stsz", 12, -1));
        rejected(change("stsz", 20, 0));
    }

    @Test void externalFragmentedAndAlternateTableStructuresFailClosed() throws Exception {
        rejected(change("url ", 8, 0));
        rejected(rename("moov", "moof"));
        rejected(rename("stco", "co64"));
        rejected(rename("stsz", "stz2"));
        rejected(rename("trak", "edts"));
        rejected(rename("avc1", "hvc1"));
        rejected(rename("avcC", "btrt"));
        rejected(change("stsd", 12, 2));
        byte[] config = AttachmentParserPreflightTest.supportedMp4();
        config[start(config, "avcC") + 9] = 100; // High profile is deliberately unsupported.
        rejected(config);
        config = AttachmentParserPreflightTest.supportedMp4();
        config[start(config, "avcC") + 11] = 19; // Not an allowed AVC level code.
        rejected(config);
        config = AttachmentParserPreflightTest.supportedMp4();
        config[start(config, "avcC") + 10] = 1; // Reserved compatibility bits must be zero.
        rejected(config);
    }

    @Test void durationDimensionsAndConfigurationAreBounded() throws Exception {
        rejected(change("mdhd", 20, 0));
        rejected(change("mdhd", 24, -1));
        rejected(change("stts", 20, -1));
        rejected(change("mdhd", 24, 301_000));
        byte[] dimension = AttachmentParserPreflightTest.supportedMp4();
        ByteBuffer.wrap(dimension).putShort(start(dimension, "avc1") + 32, (short)0);
        rejected(dimension);
        dimension = AttachmentParserPreflightTest.supportedMp4();
        ByteBuffer.wrap(dimension).putShort(start(dimension, "avc1") + 32, (short)4097);
        rejected(dimension);
        byte[] config = AttachmentParserPreflightTest.supportedMp4();
        ByteBuffer.wrap(config).putShort(start(config, "avcC") + 14, (short)65535);
        rejected(config);
    }

    @Test void excessiveBoxesAndNestingRejectWithoutRecursionOrLargeAllocation() throws Exception {
        byte[] valid = AttachmentParserPreflightTest.supportedMp4();
        var output = new java.io.ByteArrayOutputStream();
        output.write(valid);
        byte[] padding = ByteBuffer.allocate(8).putInt(8).put("free".getBytes(StandardCharsets.US_ASCII)).array();
        for (int i = 0; i <= StrictMp4StructureValidator.MAX_BOXES; i++) output.write(padding);
        rejected(output.toByteArray());
        byte[] nested = padding;
        for (int i = 0; i < 100; i++) nested = ByteBuffer.allocate(nested.length + 8).putInt(nested.length + 8)
                .put("moov".getBytes(StandardCharsets.US_ASCII)).put(nested).array();
        rejected(nested);
    }

    @Test @org.junit.jupiter.api.Timeout(10) void deterministicHostileHeaderAndCountCorpusIsBounded() throws Exception {
        Random random = new Random(48012);
        for (int length = 0; length <= 32; length++) for (int i = 0; i < 20; i++) {
            byte[] input = new byte[length]; random.nextBytes(input); rejected(input);
        }
        for (int size : new int[]{0, 2, 7, 8, 9, Integer.MAX_VALUE, Integer.MIN_VALUE, -1}) {
            rejected(change("stts", 12, size));
            rejected(change("stsc", 12, size));
        }
    }

    private StrictMp4StructureValidator.Video validate(byte[] bytes) throws Exception {
        Path path = directory.resolve("synthetic.bin"); Files.write(path, bytes);
        return validator.validate(path);
    }
    private void rejected(byte[] bytes) throws Exception {
        Path path = directory.resolve("synthetic.bin"); Files.write(path, bytes);
        assertThatThrownBy(() -> validator.validate(path)).isInstanceOfSatisfying(ApiFailureException.class,
                failure -> assertThat(failure.kind()).isEqualTo(ApiFailureException.Kind.INVALID_INPUT));
    }
    private byte[] change(String type, int offset, int value) throws Exception {
        byte[] bytes = AttachmentParserPreflightTest.supportedMp4();
        ByteBuffer.wrap(bytes).putInt(start(bytes, type) + offset, value);
        return bytes;
    }
    private byte[] rename(String from, String to) throws Exception {
        byte[] bytes = AttachmentParserPreflightTest.supportedMp4();
        System.arraycopy(to.getBytes(StandardCharsets.US_ASCII), 0, bytes, start(bytes, from) + 4, 4);
        return bytes;
    }
    // Mutation helper for generated fixtures only; production never scans byte strings.
    private static int start(byte[] bytes, String type) {
        byte[] pattern = type.getBytes(StandardCharsets.US_ASCII);
        for (int i = 4; i <= bytes.length - 4; i++) {
            if (Arrays.equals(bytes, i, i + 4, pattern, 0, 4)) return i - 4;
        }
        throw new AssertionError("Fixture box absent");
    }
}
