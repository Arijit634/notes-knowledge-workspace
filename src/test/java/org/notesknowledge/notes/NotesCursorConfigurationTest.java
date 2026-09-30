package org.notesknowledge.notes;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.notesknowledge.websupport.OpaqueCursorCodec;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@Tag("FAST")
@Tag("SECURITY")
class NotesCursorConfigurationTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-30T12:00:00Z"),
            ZoneOffset.UTC);
    private static final OpaqueCursorCodec.ExpectedCursorContext CONTEXT =
            new OpaqueCursorCodec.ExpectedCursorContext(
                    new OpaqueCursorCodec.RouteFamily("OWNER_NOTES"),
                    new OpaqueCursorCodec.ScopeFingerprint("a".repeat(64)),
                    new OpaqueCursorCodec.FilterFingerprint("b".repeat(64)),
                    new OpaqueCursorCodec.SortCode("UPDATED_DESC"));
    private static final OpaqueCursorCodec.OrderingTuple POSITION =
            new OpaqueCursorCodec.OrderingTuple(List.of(new OpaqueCursorCodec.UuidValue(
                    UUID.fromString("01990a55-9e12-7ac4-8f5b-31aa4a91d401"))));

    private final ApplicationContextRunner contexts = new ApplicationContextRunner()
            .withUserConfiguration(NotesCursorConfiguration.class)
            .withBean(Clock.class, () -> CLOCK);

    @Test
    void configuredKeySurvivesRestartAndPreviousVersionCanDecode() {
        String[] first = {""};
        contexts.withPropertyValues(active("v1", (byte) 0x31)).run(context -> {
            assertThat(context).hasNotFailed();
            first[0] = context.getBean(OpaqueCursorCodec.class)
                    .encode(CONTEXT, POSITION, Duration.ofMinutes(5));
        });
        contexts.withPropertyValues(active("v1", (byte) 0x31)).run(context ->
                assertThat(context.getBean(OpaqueCursorCodec.class).decode(first[0], CONTEXT)
                        .position()).isEqualTo(POSITION));
        contexts.withPropertyValues(properties(active("v2", (byte) 0x52),
                previous(0, "v1", (byte) 0x31))).run(context -> {
                    assertThat(context).hasNotFailed();
                    var codec = context.getBean(OpaqueCursorCodec.class);
                    assertThat(codec.decode(first[0], CONTEXT).position()).isEqualTo(POSITION);
                    assertThat(codec.encode(CONTEXT, POSITION, Duration.ofMinutes(5)))
                            .startsWith("c1.v2.");
                });
    }

    @Test
    void missingMalformedAndWrongLengthKeysFailStartup() {
        contexts.run(context -> assertThat(context).hasFailed());
        contexts.withPropertyValues("notes.cursor.active.version=v1",
                "notes.cursor.active.key-base64=not-base64!")
                .run(context -> assertThat(context).hasFailed());
        contexts.withPropertyValues("notes.cursor.active.version=v1",
                "notes.cursor.active.key-base64=" + Base64.getEncoder().encodeToString(new byte[16]))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void duplicateVersionsAndMoreThanTwoPreviousKeysFailStartup() {
        contexts.withPropertyValues(properties(active("v1", (byte) 0x31),
                previous(0, "v1", (byte) 0x52)))
                .run(context -> assertThat(context).hasFailed());
        contexts.withPropertyValues(properties(active("v4", (byte) 0x64),
                previous(0, "v1", (byte) 0x31), previous(1, "v2", (byte) 0x52),
                previous(2, "v3", (byte) 0x63)))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void typedConfigurationNeverRendersKeyMaterial() {
        String material = key((byte) 0x31);
        var configured = new NotesCursorProperties(
                new NotesCursorProperties.ConfiguredKey("v1", material), List.of());
        assertThat(configured.toString()).doesNotContain(material);
        assertThat(configured.active().toString()).doesNotContain(material);
    }

    private static String[] active(String version, byte fill) {
        return new String[] {"notes.cursor.active.version=" + version,
                "notes.cursor.active.key-base64=" + key(fill)};
    }

    private static String[] previous(int index, String version, byte fill) {
        return new String[] {"notes.cursor.previous[" + index + "].version=" + version,
                "notes.cursor.previous[" + index + "].key-base64=" + key(fill)};
    }

    private static String key(byte fill) {
        byte[] material = new byte[32];
        Arrays.fill(material, fill);
        return Base64.getEncoder().encodeToString(material);
    }

    private static String[] properties(String[]... groups) {
        return Arrays.stream(groups).flatMap(Arrays::stream).toArray(String[]::new);
    }
}
