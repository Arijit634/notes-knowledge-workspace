package org.notesknowledge.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

/** A lookup identifier followed by 256 random bits; only the digest grants authority. */
final class VerificationToken {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final byte[] DOMAIN = "notes-knowledge-email-verification-v1\0"
            .getBytes(StandardCharsets.US_ASCII);

    private VerificationToken() { }

    static String issue(UUID capabilityId) {
        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        return capabilityId + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
    }

    static UUID locator(String token) {
        if (token == null || token.length() != 80 || token.charAt(36) != '.') {
            throw new IllegalArgumentException("Invalid verification token");
        }
        try {
            UUID id = UUID.fromString(token.substring(0, 36));
            byte[] secret = Base64.getUrlDecoder().decode(token.substring(37));
            if (id.version() != 7 || secret.length != 32) {
                throw new IllegalArgumentException("Invalid verification token");
            }
            return id;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid verification token");
        }
    }

    static byte[] digest(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(DOMAIN);
            return digest.digest(token.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }
}
