package org.notesknowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("FAST")
class LeaseValuesTest {

    @Test
    @Tag("SECURITY")
    void leaseTokenIsOpaqueRedactedAndNeverAnAuthorityClaim() {
        UUID synthetic = UUID.fromString("01990a55-9e12-7ac4-8f5b-31aa4a91d401");
        LeaseToken token = LeaseToken.fromDatabase(synthetic);

        assertThat(token.value()).isEqualTo(synthetic);
        assertThat(token.toString()).isEqualTo("LeaseToken[REDACTED]")
                .doesNotContain(synthetic.toString());
        assertThat(token).isEqualTo(LeaseToken.fromDatabase(synthetic));
        assertThatThrownBy(() -> LeaseToken.fromDatabase(
                UUID.fromString("00000000-0000-4000-8000-000000000001")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(synthetic.toString());
    }

    @Test
    void ownerAndPolicyAreBoundedWithoutProductRetryRules() {
        assertThat(new LeaseOwner("synthetic_worker-1").alias())
                .isEqualTo("synthetic_worker-1");
        assertThatThrownBy(() -> new LeaseOwner("user@example.com"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LeaseOwner("x".repeat(65)))
                .isInstanceOf(IllegalArgumentException.class);

        LeasePolicy policy = new LeasePolicy(Duration.ofSeconds(30), 3);
        assertThat(policy.checkedBatchSize(1)).isEqualTo(1);
        assertThat(policy.checkedBatchSize(3)).isEqualTo(3);
        assertThatThrownBy(() -> policy.checkedBatchSize(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.checkedBatchSize(4))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LeasePolicy(Duration.ZERO, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LeasePolicy(Duration.ofDays(2), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LeasePolicy(Duration.ofSeconds(1), 1_001))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
