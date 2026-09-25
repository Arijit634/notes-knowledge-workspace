package org.notesknowledge.identity;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Opaque integrity-bound locator, never an authentication or enrollment authority. */
@Component
@IdentityCoreEnabled
final class MfaEnrollmentHandle {
    private final byte[] key;

    MfaEnrollmentHandle(@Value("${identity.mfa.handle-key-base64:}") String encoded) {
        if (encoded == null || encoded.isBlank()) { key = null; return; }
        try { key = Base64.getDecoder().decode(encoded); }
        catch (IllegalArgumentException exception) { throw new IllegalArgumentException("MFA handle key malformed"); }
        if (key.length != 32) throw new IllegalArgumentException("MFA handle key must be 256 bits");
    }

    String forPending(UUID userId, byte[] nonce) {
        if (key == null) throw ApiFailureException.of(ApiFailureException.Kind.SERVICE_UNAVAILABLE);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            mac.update("identity:mfa:enrollment-handle:v1:".getBytes(StandardCharsets.US_ASCII));
            mac.update(ByteBuffer.allocate(16).putLong(userId.getMostSignificantBits())
                    .putLong(userId.getLeastSignificantBits()).array());
            mac.update(nonce);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal());
        } catch (GeneralSecurityException exception) {
            throw ApiFailureException.of(ApiFailureException.Kind.SERVICE_UNAVAILABLE);
        }
    }

    boolean matches(String supplied, UUID userId, byte[] nonce) {
        if (supplied == null || !supplied.matches("[A-Za-z0-9_-]{43}")) return false;
        byte[] expected = forPending(userId, nonce).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, supplied.getBytes(StandardCharsets.US_ASCII));
    }
}
