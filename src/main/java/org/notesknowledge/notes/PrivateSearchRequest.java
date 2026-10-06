package org.notesknowledge.notes;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.notesknowledge.websupport.ApiFailureException;

/** Strictly typed after decoding: no coercion or client-selected search strategy. */
record PrivateSearchRequest(String query, String lifecycle, List<String> tags,
        String sort, String cursor, Integer limit) {
    private static final Set<String> FIELDS = Set.of("query", "lifecycle", "tags", "sort", "cursor", "limit");

    static PrivateSearchRequest decode(Map<String, Object> input) {
        if (input == null || !FIELDS.containsAll(input.keySet()) || !(input.get("query") instanceof String)) throw invalid();
        for (String field : List.of("lifecycle", "sort", "cursor")) {
            if (input.containsKey(field) && !(input.get(field) instanceof String)) throw invalid();
        }
        if (input.containsKey("limit") && !(input.get("limit") instanceof Integer)) throw invalid();
        List<String> tags = List.of();
        if (input.containsKey("tags")) {
            if (!(input.get("tags") instanceof List<?> values) || values.size() > 10
                    || values.stream().anyMatch(value -> !(value instanceof String))) throw invalid();
            tags = values.stream().map(String.class::cast).toList();
        }
        return new PrivateSearchRequest((String) input.get("query"),
                (String) input.get("lifecycle"), tags, (String) input.get("sort"),
                (String) input.get("cursor"), (Integer) input.get("limit"));
    }

    PrivateSearchRequest {
        if (query == null || query.isBlank() || query.length() > 256
                || query.codePoints().anyMatch(c -> Character.isISOControl(c)
                    || Character.getType(c) == Character.SURROGATE)) throw invalid();
        query = Normalizer.normalize(query, Normalizer.Form.NFC).toLowerCase(Locale.ROOT)
                .replaceAll("(?U)\\s+", " ").strip();
        if (query.isBlank() || query.length() > 256) throw invalid();
        lifecycle = lifecycle == null ? "active" : lifecycle;
        sort = sort == null ? "relevance" : sort;
        if (!Set.of("active", "archived", "trashed").contains(lifecycle) || !sort.equals("relevance")) throw invalid();
        if (tags == null || tags.size() > 10 || tags.stream().anyMatch(java.util.Objects::isNull)
                || tags.stream().mapToInt(String::length).sum() > 500) throw invalid();
        tags = TagLabel.validate(tags).stream().map(TagLabel::normalized).toList();
    }

    /** Tiny static product vocabulary; never learned from a foreign or AI corpus. */
    String lexicalVariant() {
        return switch (query) {
            case "ps", "psn" -> "playstation";
            case "playstation" -> "ps";
            case "micro" -> "microsoft";
            case "microsoft" -> "micro";
            default -> query;
        };
    }

    @Override public String toString() { return "PrivateSearchRequest[REDACTED]"; }
    private static ApiFailureException invalid() { return ApiFailureException.of(ApiFailureException.Kind.INVALID_INPUT); }
}
