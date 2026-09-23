package org.notesknowledge.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** HMACs a bounded subject and server-observed network signal; raw inputs never reach Redis. */
@Component
public final class RateKeyDeriver {
    private final byte[] key;

    public RateKeyDeriver(@Value("${identity.rate.key-base64:}") String encoded) {
        try {
            this.key = encoded.isBlank() ? null : Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Rate HMAC key is malformed");
        }
        if (key != null && key.length < 32) {
            throw new IllegalArgumentException("Rate HMAC key must have at least 256 bits");
        }
    }

    public RateLimitPort.OpaqueKey derive(String controlClass, String input) {
        if (key == null) {
            throw ApiFailureException.of(ApiFailureException.Kind.SERVICE_UNAVAILABLE);
        }
        if (input == null || input.length() > 512) {
            throw ApiFailureException.of(ApiFailureException.Kind.MALFORMED_REQUEST);
        }
        try {
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] digest = hmac.doFinal((controlClass + "\0" + input)
                    .getBytes(StandardCharsets.UTF_8));
            return new RateLimitPort.OpaqueKey(Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(digest));
        } catch (GeneralSecurityException exception) {
            throw ApiFailureException.of(ApiFailureException.Kind.SERVICE_UNAVAILABLE);
        }
    }
}
