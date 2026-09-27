package org.notesknowledge.identity;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Purpose-separated encrypted locator. A valid handle is never session authority. */
@Component
@IdentityCoreEnabled
final class SessionHandleCodec {
    record Location(String primaryId, UUID owner) { }
    private static final String AUDIENCE = "identity:session-management:";
    private final SecureRandom random = new SecureRandom();
    private final String currentVersion;
    private final SecretKey current;
    private final String previousVersion;
    private final SecretKey previous;

    SessionHandleCodec(@Value("${identity.session-handle.key-version:v1}") String currentVersion,
            @Value("${identity.session-handle.key-base64:}") String currentKey,
            @Value("${identity.session-handle.previous-key-version:}") String previousVersion,
            @Value("${identity.session-handle.previous-key-base64:}") String previousKey) {
        if (!currentVersion.matches("[A-Za-z0-9_-]{1,16}")
                || (!previousVersion.isEmpty() && !previousVersion.matches("[A-Za-z0-9_-]{1,16}"))
                || (!previousVersion.isEmpty() && previousVersion.equals(currentVersion))) {
            throw new IllegalArgumentException("Session handle key version is invalid");
        }
        this.currentVersion = currentVersion;
        this.current = key(currentKey);
        this.previousVersion = previousVersion;
        this.previous = key(previousKey);
    }

    String encode(String primaryId, UUID owner) {
        if (current == null) throw unavailable();
        UUID primary = UUID.fromString(primaryId);
        ByteBuffer plain = ByteBuffer.allocate(33).put((byte) 1)
                .putLong(primary.getMostSignificantBits()).putLong(primary.getLeastSignificantBits())
                .putLong(owner.getMostSignificantBits()).putLong(owner.getLeastSignificantBits());
        byte[] nonce = new byte[12];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, current, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad(currentVersion));
            byte[] sealed = cipher.doFinal(plain.array());
            ByteBuffer frame = ByteBuffer.allocate(nonce.length + sealed.length);
            frame.put(nonce).put(sealed);
            return currentVersion + "." + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(frame.array());
        } catch (GeneralSecurityException failure) { throw unavailable(); }
    }

    Location decode(String handle, UUID expectedOwner) {
        if (handle == null || handle.length() > 120
                || !handle.matches("[A-Za-z0-9_-]{1,16}\\.[A-Za-z0-9_-]{82}")) {
            throw notFound();
        }
        int dot = handle.indexOf('.');
        String version = handle.substring(0, dot);
        SecretKey key = version.equals(currentVersion) ? current
                : version.equals(previousVersion) ? previous : null;
        if (key == null) throw notFound();
        try {
            byte[] frame = Base64.getUrlDecoder().decode(handle.substring(dot + 1));
            if (frame.length != 61) throw notFound();
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(frame)
                    .equals(handle.substring(dot + 1))) throw notFound();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(128, frame, 0, 12));
            cipher.updateAAD(aad(version));
            byte[] raw = cipher.doFinal(frame, 12, frame.length - 12);
            if (raw.length != 33) throw notFound();
            ByteBuffer buffer = ByteBuffer.wrap(raw);
            if (buffer.get() != 1) throw notFound();
            UUID primary = new UUID(buffer.getLong(), buffer.getLong());
            UUID owner = new UUID(buffer.getLong(), buffer.getLong());
            if (!expectedOwner.equals(owner)) throw notFound();
            return new Location(primary.toString(), owner);
        } catch (GeneralSecurityException | IllegalArgumentException failure) {
            throw notFound();
        }
    }

    private static byte[] aad(String version) {
        return (AUDIENCE + version).getBytes(StandardCharsets.US_ASCII);
    }

    private static SecretKey key(String encoded) {
        if (encoded == null || encoded.isBlank()) return null;
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(encoded); }
        catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Session handle key malformed");
        }
        if (bytes.length != 32) throw new IllegalArgumentException("Session handle key must be 256 bits");
        return new SecretKeySpec(bytes, "AES");
    }

    private static ApiFailureException notFound() {
        return ApiFailureException.of(ApiFailureException.Kind.RESOURCE_NOT_FOUND);
    }

    private static ApiFailureException unavailable() {
        return ApiFailureException.of(ApiFailureException.Kind.SERVICE_UNAVAILABLE);
    }
}
