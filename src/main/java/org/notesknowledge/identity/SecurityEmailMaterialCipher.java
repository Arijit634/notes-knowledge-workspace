package org.notesknowledge.identity;

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

/** Worker-only, purpose-bound temporary delivery material; not capability authority. */
@Component
final class SecurityEmailMaterialCipher {
    record Envelope(byte[] ciphertext, byte[] nonce, byte[] tag, String keyVersion) { }

    private final SecureRandom random = new SecureRandom();
    private final String currentVersion;
    private final SecretKey currentKey;
    private final String previousVersion;
    private final SecretKey previousKey;

    SecurityEmailMaterialCipher(
            @Value("${identity.delivery.key-version:v1}") String currentVersion,
            @Value("${identity.delivery.key-base64:}") String currentEncoded,
            @Value("${identity.delivery.previous-key-version:}") String previousVersion,
            @Value("${identity.delivery.previous-key-base64:}") String previousEncoded) {
        this.currentVersion = currentVersion;
        this.currentKey = key(currentEncoded);
        this.previousVersion = previousVersion;
        this.previousKey = key(previousEncoded);
    }

    Envelope seal(UUID capabilityId, String token) {
        return seal("email_verification", capabilityId, token);
    }

    Envelope seal(String purpose, UUID capabilityId, String token) {
        if (currentKey == null) {
            throw ApiFailureException.of(ApiFailureException.Kind.SERVICE_UNAVAILABLE);
        }
        byte[] nonce = new byte[12];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, currentKey, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad(purpose, capabilityId));
            byte[] combined = cipher.doFinal(token.getBytes(StandardCharsets.US_ASCII));
            byte[] content = java.util.Arrays.copyOf(combined, combined.length - 16);
            byte[] tag = java.util.Arrays.copyOfRange(combined, combined.length - 16, combined.length);
            return new Envelope(content, nonce, tag, currentVersion);
        } catch (GeneralSecurityException exception) {
            throw ApiFailureException.of(ApiFailureException.Kind.SERVICE_UNAVAILABLE);
        }
    }

    String open(UUID capabilityId, Envelope envelope) {
        return open("email_verification", capabilityId, envelope);
    }

    String open(String purpose, UUID capabilityId, Envelope envelope) {
        SecretKey key = envelope.keyVersion().equals(currentVersion) ? currentKey
                : envelope.keyVersion().equals(previousVersion) ? previousKey : null;
        if (key == null) {
            throw new IllegalStateException("Delivery key unavailable");
        }
        byte[] combined = new byte[envelope.ciphertext().length + envelope.tag().length];
        System.arraycopy(envelope.ciphertext(), 0, combined, 0, envelope.ciphertext().length);
        System.arraycopy(envelope.tag(), 0, combined, envelope.ciphertext().length, envelope.tag().length);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, envelope.nonce()));
            cipher.updateAAD(aad(purpose, capabilityId));
            return new String(cipher.doFinal(combined), StandardCharsets.US_ASCII);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Delivery material invalid");
        }
    }

    private byte[] aad(String purpose, UUID id) {
        if (!"email_verification".equals(purpose) && !"password_reset".equals(purpose)) {
            throw new IllegalArgumentException("Unsupported capability purpose");
        }
        return ("capability_link:" + purpose + ":" + id).getBytes(StandardCharsets.US_ASCII);
    }

    private static SecretKey key(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        final byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Identity delivery key is malformed");
        }
        if (bytes.length != 32) {
            throw new IllegalArgumentException("Identity delivery key must be 256 bits");
        }
        return new SecretKeySpec(bytes, "AES");
    }
}
