package org.notesknowledge.websupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("FAST")
class OpaqueCursorCodecTest {

    private static final byte[] KEY_ONE = syntheticKey((byte) 0x31);
    private static final byte[] KEY_TWO = syntheticKey((byte) 0x52);
    private static final Instant START = Instant.parse("2026-09-22T12:00:00Z");
    private static final AtomicLong FORGED_NONCE_SEQUENCE = new AtomicLong();
    private static final OpaqueCursorCodec.ExpectedCursorContext CONTEXT =
            new OpaqueCursorCodec.ExpectedCursorContext(
                    new OpaqueCursorCodec.RouteFamily("SYNTHETIC_OWNER_LIST"),
                    scope("synthetic-private-actor-marker"),
                    filter("synthetic-private-filter-marker"),
                    new OpaqueCursorCodec.SortCode("SYNTHETIC_UPDATED_DESC"));
    private static final OpaqueCursorCodec.OrderingTuple POSITION =
            new OpaqueCursorCodec.OrderingTuple(List.of(
                    new OpaqueCursorCodec.UuidValue(
                            UUID.fromString("01990a55-9e12-7ac4-8f5b-31aa4a91d401")),
                    new OpaqueCursorCodec.SignedLongValue(-7),
                    new OpaqueCursorCodec.EpochMillisValue(1_790_000_000_000L),
                    new OpaqueCursorCodec.EnumCodeValue("SYNTHETIC_ACTIVE")));

    @Test
    void roundTripUsesFreshNonceAndKeepsPrivateMarkersOutOfToken() {
        Fixture fixture = new Fixture();
        String first = fixture.codec.encode(CONTEXT, POSITION, Duration.ofMinutes(5));
        String second = fixture.codec.encode(CONTEXT, POSITION, Duration.ofMinutes(5));

        assertThat(first).startsWith("c1.v1.").isNotEqualTo(second)
                .doesNotContain("synthetic-private-actor-marker",
                        "synthetic-private-filter-marker",
                        Base64.getUrlEncoder().withoutPadding().encodeToString(KEY_ONE));
        assertThat(first.length()).isLessThanOrEqualTo(OpaqueCursorCodec.MAX_TOKEN_LENGTH);
        OpaqueCursorCodec.Continuation decodedFirst = fixture.codec.decode(first, CONTEXT);
        OpaqueCursorCodec.Continuation decodedSecond = fixture.codec.decode(second, CONTEXT);
        assertThat(decodedFirst).isEqualTo(decodedSecond);
        assertThat(decodedFirst.context()).isEqualTo(CONTEXT);
        assertThat(decodedFirst.position()).isEqualTo(POSITION);
        assertThat(decodedFirst.issuedAt()).isEqualTo(START);
        assertThat(decodedFirst.expiresAt()).isEqualTo(START.plus(Duration.ofMinutes(5)));
        byte[] firstPayload = Base64.getUrlDecoder().decode(first.split("\\.")[2]);
        byte[] secondPayload = Base64.getUrlDecoder().decode(second.split("\\.")[2]);
        assertThat(Arrays.copyOf(firstPayload, 12))
                .isNotEqualTo(Arrays.copyOf(secondPayload, 12));
    }

    @Test
    void boundsExcludeRawPathsIdentityPrivateTextAndUnconfiguredLimits() {
        assertThatThrownBy(() -> new OpaqueCursorCodec.RouteFamily("/api/notes"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OpaqueCursorCodec.SortCode("title ASC; DROP"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OpaqueCursorCodec.ScopeFingerprint("private-user-id"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OpaqueCursorCodec.FilterFingerprint("search text"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OpaqueCursorCodec.EnumCodeValue(
                "synthetic-private-title-marker"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OpaqueCursorCodec.OrderingTuple(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OpaqueCursorCodec.OrderingTuple(List.of(
                new OpaqueCursorCodec.SignedLongValue(1),
                new OpaqueCursorCodec.SignedLongValue(2),
                new OpaqueCursorCodec.SignedLongValue(3),
                new OpaqueCursorCodec.SignedLongValue(4),
                new OpaqueCursorCodec.SignedLongValue(5))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OpaqueCursorCodec.EpochMillisValue(-1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CursorKeyRing.CursorKey("v1", new byte[16]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CursorKeyRing.KeySnapshot(
                new CursorKeyRing.CursorKey("v1", KEY_ONE), List.of(
                        new CursorKeyRing.CursorKey("v1", KEY_TWO))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CursorKeyRing.KeySnapshot(
                new CursorKeyRing.CursorKey("v1", KEY_ONE), List.of(
                        new CursorKeyRing.CursorKey("v2", KEY_TWO),
                        new CursorKeyRing.CursorKey("v3", KEY_TWO),
                        new CursorKeyRing.CursorKey("v4", KEY_TWO))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Fixture().codec.encode(
                CONTEXT, POSITION, Duration.ofDays(8)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new CursorKeyRing.CursorKey("v1", KEY_ONE).toString())
                .doesNotContain(Base64.getEncoder().encodeToString(KEY_ONE));

        PageLimitPolicy limits = new PageLimitPolicy(2, 3);
        assertThat(limits.resolve(null)).isEqualTo(2);
        assertThat(limits.resolve(3)).isEqualTo(3);
        assertMalformed(() -> limits.resolve(0));
        assertMalformed(() -> limits.resolve(-1));
        assertMalformed(() -> limits.resolve(4));
        CursorPage<String> page = new CursorPage<>(List.of("synthetic-item"), null);
        assertThat(page.items()).containsExactly("synthetic-item");
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    @Tag("SECURITY")
    void rotationAcceptsOnlyActiveAndTwoBoundedPreviousVersions() {
        Fixture fixture = new Fixture();
        String old = fixture.codec.encode(CONTEXT, POSITION, Duration.ofMinutes(5));
        fixture.ring.snapshot = new CursorKeyRing.KeySnapshot(
                new CursorKeyRing.CursorKey("v2", KEY_TWO),
                List.of(new CursorKeyRing.CursorKey("v1", KEY_ONE)));
        assertThat(fixture.codec.decode(old, CONTEXT).position()).isEqualTo(POSITION);
        String current = fixture.codec.encode(CONTEXT, POSITION, Duration.ofMinutes(5));
        assertThat(current).startsWith("c1.v2.");
        assertThat(fixture.codec.decode(current, CONTEXT).position()).isEqualTo(POSITION);
        fixture.ring.snapshot = new CursorKeyRing.KeySnapshot(
                new CursorKeyRing.CursorKey("v2", KEY_TWO), List.of());
        assertMalformed(() -> fixture.codec.decode(old, CONTEXT));
        assertMalformed(() -> fixture.codec.decode(old.replaceFirst("v1", "v9"), CONTEXT));
    }

    @Test
    @Tag("SECURITY")
    void tamperAndEnvelopeVariantsFailWithOneSafeCode() {
        Fixture fixture = new Fixture();
        String valid = fixture.codec.encode(CONTEXT, POSITION, Duration.ofMinutes(5));
        String[] parts = valid.split("\\.");
        byte[] payload = Base64.getUrlDecoder().decode(parts[2]);

        assertMalformed(() -> fixture.codec.decode(mutate(payload, 0), CONTEXT));
        assertMalformed(() -> fixture.codec.decode(mutate(payload, 14), CONTEXT));
        assertMalformed(() -> fixture.codec.decode(mutate(payload, payload.length - 1), CONTEXT));
        assertMalformed(() -> fixture.codec.decode("c2." + parts[1] + "." + parts[2], CONTEXT));
        assertMalformed(() -> fixture.codec.decode(valid + ".extra", CONTEXT));
        assertMalformed(() -> fixture.codec.decode(valid.substring(0, valid.length() - 5), CONTEXT));
        assertMalformed(() -> fixture.codec.decode("c1.v1.%", CONTEXT));
        assertMalformed(() -> fixture.codec.decode("c1.v1.A".repeat(700), CONTEXT));
        assertMalformed(() -> fixture.codec.decode("c1.unknown." + parts[2], CONTEXT));

        // The alias deliberately has the same synthetic bytes: only authenticated AAD differs.
        fixture.ring.snapshot = new CursorKeyRing.KeySnapshot(
                new CursorKeyRing.CursorKey("v1", KEY_ONE),
                List.of(new CursorKeyRing.CursorKey("alias", KEY_ONE)));
        assertMalformed(() -> fixture.codec.decode("c1.alias." + parts[2], CONTEXT));
    }

    @Test
    @Tag("SECURITY")
    void everyRouteScopeFilterSortAndTemporalMismatchFails() throws Exception {
        Fixture fixture = new Fixture();
        String valid = fixture.codec.encode(CONTEXT, POSITION, Duration.ofSeconds(10));
        assertMalformed(() -> fixture.codec.decode(valid, new OpaqueCursorCodec.ExpectedCursorContext(
                new OpaqueCursorCodec.RouteFamily("SYNTHETIC_PUBLIC_LIST"),
                CONTEXT.scope(), CONTEXT.filter(), CONTEXT.sort())));
        assertMalformed(() -> fixture.codec.decode(valid, new OpaqueCursorCodec.ExpectedCursorContext(
                CONTEXT.routeFamily(), scope("other-private-actor"),
                CONTEXT.filter(), CONTEXT.sort())));
        assertMalformed(() -> fixture.codec.decode(valid, new OpaqueCursorCodec.ExpectedCursorContext(
                CONTEXT.routeFamily(), CONTEXT.scope(),
                filter("other-query-contract"), CONTEXT.sort())));
        assertMalformed(() -> fixture.codec.decode(valid, new OpaqueCursorCodec.ExpectedCursorContext(
                CONTEXT.routeFamily(), CONTEXT.scope(), CONTEXT.filter(),
                new OpaqueCursorCodec.SortCode("SYNTHETIC_CREATED_DESC"))));

        fixture.clock.now = START.plusSeconds(10);
        assertMalformed(() -> fixture.codec.decode(valid, CONTEXT));
        fixture.clock.now = START.minusSeconds(1);
        assertMalformed(() -> fixture.codec.decode(valid, CONTEXT));
        fixture.clock.now = START;

        assertMalformed(() -> fixture.codec.decode(reseal(valid, KEY_ONE, clear ->
                ByteBuffer.wrap(clear).putInt(2)), CONTEXT));
        assertMalformed(() -> fixture.codec.decode(reseal(valid, KEY_ONE, clear -> {
            ByteBuffer.wrap(clear, clear.length - 16, 16)
                    .putLong(START.plusSeconds(30).getEpochSecond());
        }), CONTEXT));
        assertMalformed(() -> fixture.codec.decode(reseal(valid, KEY_ONE, clear -> {
            ByteBuffer.wrap(clear, clear.length - 8, 8)
                    .putLong(START.getEpochSecond());
        }), CONTEXT));
        assertMalformed(() -> fixture.codec.decode(reseal(valid, KEY_ONE, clear -> {
            clear[4] = 0;
        }), CONTEXT));
    }

    private static String mutate(byte[] original, int offset) {
        byte[] changed = original.clone();
        changed[offset] ^= 1;
        return "c1.v1." + Base64.getUrlEncoder().withoutPadding().encodeToString(changed);
    }

    private static String reseal(String token, byte[] key,
            java.util.function.Consumer<byte[]> change) throws Exception {
        String[] parts = token.split("\\.");
        byte[] payload = Base64.getUrlDecoder().decode(parts[2]);
        byte[] originalNonce = Arrays.copyOfRange(payload, 0, 12);
        Cipher decrypt = Cipher.getInstance("AES/GCM/NoPadding");
        decrypt.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(128, originalNonce));
        decrypt.updateAAD("notes-cursor:c1.v1".getBytes(StandardCharsets.US_ASCII));
        byte[] clear = decrypt.doFinal(payload, 12, payload.length - 12);
        change.accept(clear);
        byte[] freshNonce = new byte[12];
        ByteBuffer.wrap(freshNonce).putInt(0x77889900)
                .putLong(FORGED_NONCE_SEQUENCE.incrementAndGet());
        Cipher encrypt = Cipher.getInstance("AES/GCM/NoPadding");
        encrypt.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(128, freshNonce));
        encrypt.updateAAD("notes-cursor:c1.v1".getBytes(StandardCharsets.US_ASCII));
        byte[] sealed = encrypt.doFinal(clear);
        byte[] changedPayload = new byte[12 + sealed.length];
        System.arraycopy(freshNonce, 0, changedPayload, 0, 12);
        System.arraycopy(sealed, 0, changedPayload, 12, sealed.length);
        return "c1.v1." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(changedPayload);
    }

    private static void assertMalformed(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(ApiFailureException.class)
                .hasMessage("malformed_request")
                .hasNoCause();
    }

    private static OpaqueCursorCodec.ScopeFingerprint scope(String marker) {
        return new OpaqueCursorCodec.ScopeFingerprint(sha(marker));
    }

    private static OpaqueCursorCodec.FilterFingerprint filter(String marker) {
        return new OpaqueCursorCodec.FilterFingerprint(sha(marker));
    }

    private static String sha(String marker) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(marker.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Test SHA-256 unavailable");
        }
    }

    private static byte[] syntheticKey(byte value) {
        byte[] key = new byte[32];
        Arrays.fill(key, value);
        return key;
    }

    private static final class Fixture {
        private final MutableClock clock = new MutableClock();
        private final MutableKeyRing ring = new MutableKeyRing();
        private final OpaqueCursorCodec codec = new OpaqueCursorCodec(clock, ring);
    }

    private static final class MutableKeyRing implements CursorKeyRing {
        private KeySnapshot snapshot = new KeySnapshot(
                new CursorKey("v1", KEY_ONE), List.of());

        @Override
        public KeySnapshot keys() {
            return snapshot;
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now = START;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
