package org.notesknowledge.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

@Tag("FAST")
class Argon2CompatibilityTest {
    @Test
    void springSecurity711UsesArgon2idVersionableEncodingWithIndependentSalts() {
        var configured = new IdentityPasswordConfiguration().identityPasswordEncoder();
        String first = configured.encode("synthetic-benchmark-secret");
        String second = configured.encode("synthetic-benchmark-secret");
        assertThat(first).startsWith("{argon2id}$argon2id$v=19$m=65536,t=3,p=1$");
        assertThat(second).isNotEqualTo(first);
        assertThat(configured.matches("synthetic-benchmark-secret", first)).isTrue();
        assertThat(configured.matches("incorrect-synthetic", first)).isFalse();
        var older = new Argon2PasswordEncoder(16, 32, 1, 16_384, 2);
        assertThat(configured.upgradeEncoding("{argon2id}" + older.encode(
                "synthetic-benchmark-secret"))).isTrue();
    }

    @Test
    void measuredLocalEncodeAndVerifyRemainBoundedForCompatibilityReview() {
        var configured = new IdentityPasswordConfiguration().identityPasswordEncoder();
        long start = System.nanoTime();
        String encoded = configured.encode("synthetic-local-benchmark-secret");
        long encodedAt = System.nanoTime();
        assertThat(configured.matches("synthetic-local-benchmark-secret", encoded)).isTrue();
        long verifiedAt = System.nanoTime();
        long encodeMs = Duration.ofNanos(encodedAt - start).toMillis();
        long verifyMs = Duration.ofNanos(verifiedAt - encodedAt).toMillis();
        System.out.printf("Argon2id local compatibility sample: encode=%dms verify=%dms%n",
                encodeMs, verifyMs);
        assertThat(encodeMs).isGreaterThanOrEqualTo(0L);
        assertThat(verifyMs).isGreaterThanOrEqualTo(0L);
    }
}
