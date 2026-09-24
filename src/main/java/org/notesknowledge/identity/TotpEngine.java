package org.notesknowledge.identity;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

/** RFC 6238 TOTP with a bounded window; replay authority remains in PostgreSQL. */
@Component
@IdentityCoreEnabled
final class TotpEngine {
    private final MfaProperties policy;

    TotpEngine(MfaProperties policy) { this.policy = policy; }

    long matchingStep(byte[] seed, String proof, Instant at, Long lastAccepted) {
        if (proof == null || !proof.matches("[0-9]{6}")) {
            return -1;
        }
        long current = Math.floorDiv(at.getEpochSecond(), policy.timestepSeconds());
        long matched = -1;
        for (int offset = -policy.allowedSkewSteps(); offset <= policy.allowedSkewSteps(); offset++) {
            long step = current + offset;
            if (step < 0 || (lastAccepted != null && step <= lastAccepted)) {
                continue;
            }
            byte[] expected = String.format(java.util.Locale.ROOT, "%0" + policy.digits() + "d",
                    value(seed, step)).getBytes(StandardCharsets.US_ASCII);
            if (MessageDigest.isEqual(expected, proof.getBytes(StandardCharsets.US_ASCII))) {
                matched = Math.max(matched, step);
            }
        }
        return matched;
    }

    String codeAt(byte[] seed, long step) {
        return String.format(java.util.Locale.ROOT, "%0" + policy.digits() + "d", value(seed, step));
    }

    private int value(byte[] seed, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(seed, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
            return binary % 1_000_000;
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("TOTP primitive unavailable");
        }
    }
}
