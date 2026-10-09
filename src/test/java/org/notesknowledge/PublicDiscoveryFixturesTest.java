package org.notesknowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("FAST")
class PublicDiscoveryFixturesTest {
    @Test
    void sameMillisecondOwnersHaveDifferentPublicHandles() {
        UUID first = UUID.fromString("019a1234-5678-7000-8000-000000000001");
        UUID second = UUID.fromString("019a1234-5678-7000-8000-000000000002");

        assertThat(first.version()).isEqualTo(7);
        assertThat(second.version()).isEqualTo(7);
        assertThat(first.variant()).isEqualTo(2);
        assertThat(second.variant()).isEqualTo(2);
        assertThat(first.getMostSignificantBits() >>> 16)
            .isEqualTo(second.getMostSignificantBits() >>> 16);
        assertThat(first.toString().replace("-", "").substring(0, 12))
            .isEqualTo(second.toString().replace("-", "").substring(0, 12));

        assertThat(PublicDiscoveryFixtures.publicHandle(first))
            .isEqualTo("writer_70008000000000000001")
            .isNotEqualTo(PublicDiscoveryFixtures.publicHandle(second));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "00000000-0000-7000-8000-000000000000",
        "019a1234-5678-7abc-9def-123456789abc",
        "ffffffff-ffff-7fff-bfff-ffffffffffff"
    })
    void publicHandlesHaveApprovedFormatAndLength(String value) {
        String handle = PublicDiscoveryFixtures.publicHandle(UUID.fromString(value));

        assertThat(handle).hasSize(27).matches("^[a-z][a-z0-9_]{2,29}$");
    }
}
