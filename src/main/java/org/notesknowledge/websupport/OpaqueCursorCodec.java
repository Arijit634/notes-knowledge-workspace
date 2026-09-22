package org.notesknowledge.websupport;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;

/** Authenticated continuation state, never identity, permission, or authorization. */
public final class OpaqueCursorCodec {

    public static final int MAX_TOKEN_LENGTH = 2_048;

    private static final String FORMAT = "c1";
    private static final int SCHEMA = 1;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int MAX_CLEAR_BYTES = 512;
    private static final long MAX_LIFETIME_SECONDS = 7 * 24 * 60 * 60;

    private final Clock clock;
    private final CursorKeyRing keyRing;
    private final NonceSource nonces;

    public OpaqueCursorCodec(Clock clock, CursorKeyRing keyRing) {
        this(clock, keyRing, new SecureRandom()::nextBytes);
    }

    OpaqueCursorCodec(Clock clock, CursorKeyRing keyRing, NonceSource nonces) {
        this.clock = Objects.requireNonNull(clock, "clock");
        if (!ZoneOffset.UTC.equals(clock.getZone())) {
            throw new IllegalArgumentException("Cursor clock must use UTC");
        }
        this.keyRing = Objects.requireNonNull(keyRing, "keyRing");
        this.nonces = Objects.requireNonNull(nonces, "nonces");
    }

    @FunctionalInterface
    interface NonceSource {
        void fill(byte[] nonce);
    }

    /** Server-owned codes, not a URI, raw filter, or client-supplied SQL expression. */
    public record RouteFamily(String code) {
        public RouteFamily {
            validateCode(code);
        }
    }

    /** Already-derived SHA-256 of the current authority scope; derivation belongs to the caller. */
    public record ScopeFingerprint(String digestHex) {
        public ScopeFingerprint {
            validateDigest(digestHex);
        }

        @Override
        public String toString() {
            return "ScopeFingerprint[REDACTED]";
        }
    }

    /** Already-derived SHA-256 of normalized allowlisted filters, never raw query text. */
    public record FilterFingerprint(String digestHex) {
        public FilterFingerprint {
            validateDigest(digestHex);
        }

        @Override
        public String toString() {
            return "FilterFingerprint[REDACTED]";
        }
    }

    public record SortCode(String code) {
        public SortCode {
            validateCode(code);
        }
    }

    /** Constructed from current server authority and normalized request semantics on every page. */
    public record ExpectedCursorContext(RouteFamily routeFamily,
            ScopeFingerprint scope, FilterFingerprint filter, SortCode sort) {
        public ExpectedCursorContext {
            Objects.requireNonNull(routeFamily, "routeFamily");
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(filter, "filter");
            Objects.requireNonNull(sort, "sort");
        }
    }

    public sealed interface OrderingScalar permits UuidValue, SignedLongValue,
            EpochMillisValue, EnumCodeValue {
    }

    public record UuidValue(UUID value) implements OrderingScalar {
        public UuidValue {
            Objects.requireNonNull(value, "value");
        }
    }

    public record SignedLongValue(long value) implements OrderingScalar {
    }

    public record EpochMillisValue(long value) implements OrderingScalar {
        public EpochMillisValue {
            if (value < 0 || value > 253_402_300_799_999L) {
                throw new IllegalArgumentException("Invalid epoch-millisecond continuation");
            }
        }
    }

    public record EnumCodeValue(String code) implements OrderingScalar {
        public EnumCodeValue {
            validateCode(code);
        }
    }

    /** No private title/body/search text is admitted as an ordering scalar. */
    public record OrderingTuple(List<OrderingScalar> scalars) {
        public OrderingTuple {
            scalars = List.copyOf(Objects.requireNonNull(scalars, "scalars"));
            if (scalars.isEmpty() || scalars.size() > 4) {
                throw new IllegalArgumentException("Invalid ordering tuple size");
            }
        }
    }

    public record Continuation(ExpectedCursorContext context, OrderingTuple position,
            Instant issuedAt, Instant expiresAt) {
    }

    public String encode(ExpectedCursorContext context, OrderingTuple position,
            Duration lifetime) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(lifetime, "lifetime");
        if (lifetime.isNegative() || lifetime.isZero() || lifetime.getNano() != 0
                || lifetime.getSeconds() > MAX_LIFETIME_SECONDS) {
            throw new IllegalArgumentException("Invalid technical cursor lifetime");
        }
        CursorKeyRing.CursorKey active = keyRing.keys().active();
        long issued = clock.instant().getEpochSecond();
        long expiry = Math.addExact(issued, lifetime.getSeconds());
        byte[] clear = serialize(context, position, issued, expiry);
        byte[] nonce = new byte[NONCE_BYTES];
        try {
            nonces.fill(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, active.secretKey(),
                    new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(active.version()));
            byte[] sealed = cipher.doFinal(clear);
            byte[] payload = new byte[nonce.length + sealed.length];
            System.arraycopy(nonce, 0, payload, 0, nonce.length);
            System.arraycopy(sealed, 0, payload, nonce.length, sealed.length);
            String token = FORMAT + "." + active.version() + "."
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
            if (token.length() > MAX_TOKEN_LENGTH) {
                throw new IllegalStateException("Cursor exceeds technical maximum");
            }
            return token;
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("Cursor encryption unavailable");
        } finally {
            Arrays.fill(clear, (byte) 0);
        }
    }

    public Continuation decode(String token, ExpectedCursorContext expected) {
        Objects.requireNonNull(expected, "expected");
        if (token == null || token.length() > MAX_TOKEN_LENGTH || token.isBlank()) {
            throw malformed();
        }
        String[] segments = token.split("\\.", -1);
        if (segments.length != 3 || !FORMAT.equals(segments[0])
                || !CursorKeyRing.validVersion(segments[1])
                || !segments[2].matches("[A-Za-z0-9_-]+")) {
            throw malformed();
        }
        CursorKeyRing.CursorKey key = keyRing.keys().accepted(segments[1])
                .orElseThrow(OpaqueCursorCodec::malformed);
        byte[] payload;
        try {
            payload = Base64.getUrlDecoder().decode(segments[2]);
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
                    .equals(segments[2]) || payload.length < NONCE_BYTES + TAG_BITS / 8) {
                throw malformed();
            }
        } catch (IllegalArgumentException exception) {
            throw malformed();
        }
        byte[] nonce = Arrays.copyOfRange(payload, 0, NONCE_BYTES);
        byte[] sealed = Arrays.copyOfRange(payload, NONCE_BYTES, payload.length);
        byte[] clear = null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key.secretKey(),
                    new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(segments[1]));
            clear = cipher.doFinal(sealed);
            if (clear.length > MAX_CLEAR_BYTES) {
                throw malformed();
            }
            Continuation continuation = deserialize(clear);
            if (!continuation.context().equals(expected)
                    || continuation.issuedAt().isAfter(clock.instant())
                    || !continuation.expiresAt().isAfter(clock.instant())
                    || !continuation.expiresAt().isAfter(continuation.issuedAt())
                    || Duration.between(continuation.issuedAt(), continuation.expiresAt())
                            .getSeconds() > MAX_LIFETIME_SECONDS) {
                throw malformed();
            }
            return continuation;
        } catch (java.security.GeneralSecurityException | IOException | RuntimeException exception) {
            throw malformed();
        } finally {
            if (clear != null) {
                Arrays.fill(clear, (byte) 0);
            }
        }
    }

    private byte[] serialize(ExpectedCursorContext context, OrderingTuple position,
            long issued, long expiry) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeInt(SCHEMA);
            writeCode(output, context.routeFamily().code());
            output.write(HexFormat.of().parseHex(context.scope().digestHex()));
            output.write(HexFormat.of().parseHex(context.filter().digestHex()));
            writeCode(output, context.sort().code());
            output.writeByte(position.scalars().size());
            for (OrderingScalar scalar : position.scalars()) {
                switch (scalar) {
                    case UuidValue uuid -> {
                        output.writeByte(1);
                        output.writeLong(uuid.value().getMostSignificantBits());
                        output.writeLong(uuid.value().getLeastSignificantBits());
                    }
                    case SignedLongValue number -> {
                        output.writeByte(2);
                        output.writeLong(number.value());
                    }
                    case EpochMillisValue time -> {
                        output.writeByte(3);
                        output.writeLong(time.value());
                    }
                    case EnumCodeValue code -> {
                        output.writeByte(4);
                        writeCode(output, code.code());
                    }
                }
            }
            output.writeLong(issued);
            output.writeLong(expiry);
            byte[] clear = bytes.toByteArray();
            if (clear.length > MAX_CLEAR_BYTES) {
                throw new IllegalArgumentException("Cursor continuation is too large");
            }
            return clear;
        } catch (IOException exception) {
            throw new IllegalStateException("Cursor serialization unavailable");
        }
    }

    private Continuation deserialize(byte[] clear) throws IOException {
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(clear));
        if (input.readInt() != SCHEMA) {
            throw malformed();
        }
        RouteFamily route = new RouteFamily(readCode(input));
        byte[] scope = new byte[32];
        input.readFully(scope);
        byte[] filter = new byte[32];
        input.readFully(filter);
        SortCode sort = new SortCode(readCode(input));
        int size = input.readUnsignedByte();
        if (size < 1 || size > 4) {
            throw malformed();
        }
        java.util.ArrayList<OrderingScalar> scalars = new java.util.ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            scalars.add(switch (input.readUnsignedByte()) {
                case 1 -> new UuidValue(new UUID(input.readLong(), input.readLong()));
                case 2 -> new SignedLongValue(input.readLong());
                case 3 -> new EpochMillisValue(input.readLong());
                case 4 -> new EnumCodeValue(readCode(input));
                default -> throw malformed();
            });
        }
        long issued = input.readLong();
        long expiry = input.readLong();
        if (input.available() != 0) {
            throw malformed();
        }
        ExpectedCursorContext context = new ExpectedCursorContext(route,
                new ScopeFingerprint(HexFormat.of().formatHex(scope)),
                new FilterFingerprint(HexFormat.of().formatHex(filter)), sort);
        return new Continuation(context, new OrderingTuple(scalars),
                Instant.ofEpochSecond(issued), Instant.ofEpochSecond(expiry));
    }

    private static void validateCode(String code) {
        if (code == null || !code.matches("[A-Z][A-Z0-9_]{0,47}")) {
            throw new IllegalArgumentException("Invalid cursor code");
        }
    }

    private static void validateDigest(String digestHex) {
        if (digestHex == null || !digestHex.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Expected an already-derived SHA-256 fingerprint");
        }
    }

    private static void writeCode(DataOutputStream output, String code) throws IOException {
        byte[] ascii = code.getBytes(StandardCharsets.US_ASCII);
        output.writeByte(ascii.length);
        output.write(ascii);
    }

    private static String readCode(DataInputStream input) throws IOException {
        int length = input.readUnsignedByte();
        if (length < 1 || length > 48) {
            throw malformed();
        }
        byte[] ascii = new byte[length];
        input.readFully(ascii);
        return new String(ascii, StandardCharsets.US_ASCII);
    }

    private static byte[] aad(String keyVersion) {
        return ("notes-cursor:" + FORMAT + "." + keyVersion)
                .getBytes(StandardCharsets.US_ASCII);
    }

    private static ApiFailureException malformed() {
        return ApiFailureException.of(ApiFailureException.Kind.MALFORMED_REQUEST);
    }
}
