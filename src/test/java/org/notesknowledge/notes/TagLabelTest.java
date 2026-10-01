package org.notesknowledge.notes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.notesknowledge.websupport.ApiFailureException;

@Tag("FAST")
class TagLabelTest {
    @Test
    void normalizesUnicodeAndCaseWithoutLosingDisplayLabels() {
        assertThat(TagLabel.validate(List.of("  Films  ", "Cafe\u0301")))
                .containsExactly(new TagLabel("café", "Café"), new TagLabel("films", "Films"));
        // Locale-independent case normalization even when the host default differs.
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"));
            assertThat(TagLabel.validate(List.of("FILMS")).getFirst().normalized()).isEqualTo("films");
        } finally { Locale.setDefault(previous); }
    }

    @Test
    void rejectsDuplicateNormalizedLabelsAndInvalidBoundedValues() {
        for (List<String> values : List.of(List.of("Films", " films "),
                List.of("Café", "Cafe\u0301"), List.of(" "), List.of("bad\nlabel"),
                List.of("x".repeat(101)), Collections.nCopies(51, "x"))) {
            assertThatThrownBy(() -> TagLabel.validate(values)).isInstanceOf(ApiFailureException.class);
        }
        assertThat(TagLabel.validate(List.of())).isEmpty();
        assertThat(TagLabel.validate(List.of("x".repeat(100)))).hasSize(1);
    }
}
