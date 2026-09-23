package org.notesknowledge.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.notesknowledge.websupport.ApiFailureException;

@Tag("FAST")
class RateKeyDeriverTest {
    @Test
    void keysAreOpaquePurposeSeparatedAndDoNotExposeRawCandidates() {
        var keys = new RateKeyDeriver("AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=");
        String first = keys.derive("PASSWORD_LOGIN", "candidate:synthetic@example.test").value();
        assertThat(first).hasSize(43).doesNotContain("synthetic", "example", "@", ".");
        assertThat(keys.derive("PASSWORD_LOGIN", "candidate:synthetic@example.test").value())
                .isEqualTo(first);
        assertThat(keys.derive("REGISTRATION", "candidate:synthetic@example.test").value())
                .isNotEqualTo(first);
        assertThat(keys.derive("PASSWORD_LOGIN", "source:127.0.0.1").value())
                .isNotEqualTo(first);
    }

    @Test
    void missingKeyFailsClosed() {
        var keys = new RateKeyDeriver("");
        assertThatThrownBy(() -> keys.derive("PASSWORD_LOGIN", "source:127.0.0.1"))
                .isInstanceOf(ApiFailureException.class);
    }
}
