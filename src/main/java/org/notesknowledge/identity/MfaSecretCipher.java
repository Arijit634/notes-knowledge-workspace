package org.notesknowledge.identity;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** MFA-purpose AES-GCM key ring, independent of security-email encryption. */
@Component
@IdentityCoreEnabled
final class MfaSecretCipher {
    record Envelope(byte[] ciphertext, byte[] nonce, byte[] tag, String keyVersion) { }
    private final SecureRandom random = new SecureRandom();
    private final String currentVersion;
    private final SecretKey currentKey;
    private final String previousVersion;
    private final SecretKey previousKey;

    MfaSecretCipher(@Value("${identity.mfa.key-version:v1}") String currentVersion,
            @Value("${identity.mfa.key-base64:}") String currentEncoded,
            @Value("${identity.mfa.previous-key-version:}") String previousVersion,
            @Value("${identity.mfa.previous-key-base64:}") String previousEncoded) {
        this.currentVersion = currentVersion;
        this.currentKey = key(currentEncoded);
        this.previousVersion = previousVersion;
        this.previousKey = key(previousEncoded);
    }

    Envelope seal(UUID userId, byte[] seed) {
        if (currentKey == null || seed.length != 20) {
            throw ApiFailureException.of(ApiFailureException.Kind.SERVICE_UNAVAILABLE);
        }
        byte[] nonce = new byte[12];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, currentKey, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad(userId));
            byte[] combined = cipher.doFinal(seed);
            return new Envelope(Arrays.copyOf(combined, combined.length - 16), nonce,
                    Arrays.copyOfRange(combined, combined.length - 16, combined.length), currentVersion);
        } catch (GeneralSecurityException exception) {
            throw ApiFailureException.of(ApiFailureException.Kind.SERVICE_UNAVAILABLE);
        }
    }

    byte[] open(UUID userId, Envelope envelope) {
        SecretKey key = envelope.keyVersion().equals(currentVersion) ? currentKey
                : envelope.keyVersion().equals(previousVersion) ? previousKey : null;
        if (key == null) {
            throw ApiFailureException.of(ApiFailureException.Kind.SERVICE_UNAVAILABLE);
        }
        byte[] combined = new byte[envelope.ciphertext().length + envelope.tag().length];
        System.arraycopy(envelope.ciphertext(), 0, combined, 0, envelope.ciphertext().length);
        System.arraycopy(envelope.tag(), 0, combined, envelope.ciphertext().length, envelope.tag().length);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, envelope.nonce()));
            cipher.updateAAD(aad(userId));
            return cipher.doFinal(combined);
        } catch (GeneralSecurityException exception) {
            throw ApiFailureException.of(ApiFailureException.Kind.SERVICE_UNAVAILABLE);
        }
    }

    private byte[] aad(UUID id) {
        return ("identity:mfa:totp-seed:" + id).getBytes(StandardCharsets.US_ASCII);
    }

    private static SecretKey key(String encoded) {
        if (encoded == null || encoded.isBlank()) return null;
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(encoded); }
        catch (IllegalArgumentException exception) { throw new IllegalArgumentException("MFA key malformed"); }
        if (bytes.length != 32) throw new IllegalArgumentException("MFA key must be 256 bits");
        return new SecretKeySpec(bytes, "AES");
    }
}
