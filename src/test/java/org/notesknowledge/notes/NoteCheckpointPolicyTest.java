package org.notesknowledge.notes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("FAST")
class NoteCheckpointPolicyTest {
    private final Instant created = Instant.parse("2026-10-01T00:00:00Z");
    private final NoteRecord saved = new NoteRecord(UUID.randomUUID(), "Saved", "Body", "active", false,
            false, created, created, 1, List.of());

    @Test void cadenceRequiresMeaningfulChangeAndSavedHistoryAge() {
        var policy = new NoteCheckpointPolicy(Duration.ofMinutes(5), 50);
        assertThat(policy.eligible(saved, "Changed", "Body", null, created.plusSeconds(299))).isFalse();
        assertThat(policy.eligible(saved, "Saved", "Body", null, created.plusSeconds(600))).isFalse();
        assertThat(policy.eligible(saved, "Changed", "Body", null, created.plusSeconds(300))).isTrue();
        assertThat(policy.eligible(saved, "Saved", "Changed", created.plusSeconds(300), created.plusSeconds(599))).isFalse();
        assertThat(policy.eligible(saved, "Saved", "Changed", created.plusSeconds(300), created.plusSeconds(600))).isTrue();
    }

    @Test void policyRejectsUnboundedOrImpossibleRetentionAndCadence() {
        for (int limit : List.of(1, 1001)) assertThatThrownBy(() -> new NoteCheckpointPolicy(Duration.ofMinutes(5), limit))
                .isInstanceOf(IllegalArgumentException.class);
        for (Duration spacing : List.of(Duration.ZERO, Duration.ofDays(8))) {
            assertThatThrownBy(() -> new NoteCheckpointPolicy(spacing, 50)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
