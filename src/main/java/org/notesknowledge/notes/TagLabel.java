package org.notesknowledge.notes;

import java.text.Normalizer;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

import org.notesknowledge.websupport.ApiFailureException;

/** Note-local values, not global taxonomy identities. */
record TagLabel(String normalized, String display) {
    static List<TagLabel> validate(List<String> values) {
        if (values.size() > 50) throw invalid();
        var seen = new HashSet<String>();
        return values.stream().map(value -> {
            if (value == null) throw invalid();
            String display = Normalizer.normalize(value.strip(), Normalizer.Form.NFC);
            String normalized = Normalizer.normalize(display.toLowerCase(Locale.ROOT),
                    Normalizer.Form.NFC);
            if (display.isBlank() || display.length() > 100 || normalized.length() > 100
                    || display.codePoints().anyMatch(code -> Character.isISOControl(code)
                        || Character.getType(code) == Character.SURROGATE)
                    || !seen.add(normalized)) throw invalid();
            return new TagLabel(normalized, display);
        }).sorted(Comparator.comparing(TagLabel::normalized)).toList();
    }

    private static ApiFailureException invalid() {
        return ApiFailureException.of(ApiFailureException.Kind.INVALID_INPUT);
    }
}
