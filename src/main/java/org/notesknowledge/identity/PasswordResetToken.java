package org.notesknowledge.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/** Reuses the capability locator/secret format with a distinct verifier domain. */
final class PasswordResetToken {
    private static final byte[] DOMAIN = "notes-knowledge-password-reset-v1\0"
            .getBytes(StandardCharsets.US_ASCII);

    private PasswordResetToken() { }

    static String issue(UUID capabilityId) {
        return VerificationToken.issue(capabilityId);
    }

    static UUID locator(String token) {
        return VerificationToken.locator(token);
    }

    static byte[] digest(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(DOMAIN);
            return digest.digest(token.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
