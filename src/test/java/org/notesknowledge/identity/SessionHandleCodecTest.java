package org.notesknowledge.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.notesknowledge.websupport.ApiFailureException;

@Tag("FAST")
@Tag("SECURITY")
class SessionHandleCodecTest {
    private static final String KEY = "BAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQ=";
    private static final String NEXT = "BQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQU=";

    @Test void opaqueOwnerBoundAudienceSeparatedAndRotatable() {
        UUID owner = UUID.randomUUID();
        UUID primary = UUID.randomUUID();
        var first = new SessionHandleCodec("v1", KEY, "", "");
        String handle = first.encode(primary.toString(), owner);
        assertThat(handle).doesNotContain(primary.toString(), owner.toString());
        assertThat(first.decode(handle, owner).primaryId()).isEqualTo(primary.toString());
        var rotated = new SessionHandleCodec("v2", NEXT, "v1", KEY);
        assertThat(rotated.decode(handle, owner).primaryId()).isEqualTo(primary.toString());
        assertThatThrownBy(() -> rotated.decode(handle, UUID.randomUUID()))
                .isInstanceOf(ApiFailureException.class);
        var noPrevious = new SessionHandleCodec("v2", NEXT, "", "");
        assertThatThrownBy(() -> noPrevious.decode(handle, owner))
                .isInstanceOf(ApiFailureException.class);
        var wrongPurpose = new SessionHandleCodec("v1", NEXT, "", "");
        assertThatThrownBy(() -> wrongPurpose.decode(handle, owner))
                .isInstanceOf(ApiFailureException.class);
        char altered = handle.charAt(20) == 'A' ? 'B' : 'A';
        String tampered = handle.substring(0, 20) + altered + handle.substring(21);
        assertThatThrownBy(() -> first.decode(tampered, owner))
                .isInstanceOf(ApiFailureException.class);
    }

    @Test void keyUnavailabilityFailsClosed() {
        var unavailable = new SessionHandleCodec("v1", "", "", "");
        assertThatThrownBy(() -> unavailable.encode(UUID.randomUUID().toString(), UUID.randomUUID()))
                .isInstanceOf(ApiFailureException.class);
    }

    @Test void untrustedAgentIsReducedToFixedVocabulary() {
        assertThat(SessionClientLabel.from("Firefox/145 Windows private-marker"))
                .isEqualTo("Firefox on Windows");
        assertThat(SessionClientLabel.from("Chrome/145 Android secret-marker"))
                .isEqualTo("Chrome on Android");
        assertThat(SessionClientLabel.from("unrecognized private-marker"))
                .isEqualTo("Unknown browser");
        assertThat(SessionClientLabel.from("x".repeat(2049)))
                .isEqualTo("Unknown browser");
    }
}
