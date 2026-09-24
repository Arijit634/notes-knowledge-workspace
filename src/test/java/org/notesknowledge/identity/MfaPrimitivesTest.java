package org.notesknowledge.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.notesknowledge.websupport.ApiFailureException;

@Tag("FAST") @Tag("SECURITY")
class MfaPrimitivesTest {
    private static final MfaProperties POLICY = new MfaProperties(Duration.ofMinutes(5),
            Duration.ofMinutes(10), Duration.ofMinutes(5), 30, 6, 1, 8);
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Test void rfc6238VectorAndBoundedReplayWindow() {
        TotpEngine engine = new TotpEngine(POLICY);
        byte[] seed = "12345678901234567890".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        assertThat(engine.codeAt(seed, 1)).isEqualTo("287082");
        assertThat(engine.matchingStep(seed, "287082", Instant.ofEpochSecond(59), null))
                .isEqualTo(1);
        assertThat(engine.matchingStep(seed, "287082", Instant.ofEpochSecond(59), 1L))
                .isEqualTo(-1);
        assertThat(engine.matchingStep(seed, "287082", Instant.ofEpochSecond(150), null))
                .isEqualTo(-1);
        assertThat(engine.matchingStep(seed, "12x", Instant.ofEpochSecond(59), null))
                .isEqualTo(-1);
    }

    @Test void purposeBoundCipherRejectsWrongAccountTamperingAndMissingKey() {
        MfaSecretCipher cipher = new MfaSecretCipher("v1", KEY, "", "");
        UUID user = UUID.randomUUID();
        byte[] seed = new byte[20];
        Arrays.fill(seed, (byte) 12);
        var envelope = cipher.seal(user, seed);
        assertThat(envelope.ciphertext()).isNotEqualTo(seed);
        assertThat(cipher.open(user, envelope)).isEqualTo(seed);
        assertThatThrownBy(() -> cipher.open(UUID.randomUUID(), envelope))
                .isInstanceOf(ApiFailureException.class);
        envelope.ciphertext()[0] ^= 1;
        assertThatThrownBy(() -> cipher.open(user, envelope))
                .isInstanceOf(ApiFailureException.class);
        assertThatThrownBy(() -> new MfaSecretCipher("v1", "", "", "").seal(user, seed))
                .isInstanceOf(ApiFailureException.class);
    }

    @Test void handleIsAccountAndPendingNonceBound() {
        MfaEnrollmentHandle handles = new MfaEnrollmentHandle(KEY);
        UUID user = UUID.randomUUID();
        byte[] nonce = new byte[12];
        String handle = handles.forPending(user, nonce);
        assertThat(handle).hasSize(43).doesNotContain(user.toString());
        assertThat(handles.matches(handle, user, nonce)).isTrue();
        assertThat(handles.matches(handle, UUID.randomUUID(), nonce)).isFalse();
        nonce[0] = 1;
        assertThat(handles.matches(handle, user, nonce)).isFalse();
    }

    @Test void invalidPolicyIsRejected() {
        assertThatThrownBy(() -> new MfaProperties(Duration.ofHours(1),
                Duration.ofMinutes(10), Duration.ofMinutes(5), 30, 6, 1, 8))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MfaProperties(Duration.ofMinutes(5),
                Duration.ofMinutes(10), Duration.ofMinutes(5), 30, 6, 2, 8))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
