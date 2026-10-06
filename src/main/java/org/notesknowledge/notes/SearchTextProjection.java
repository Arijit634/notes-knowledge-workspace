package org.notesknowledge.notes;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Bounded projection parity and snippet clipping; no full-body offset allocation. */
final class SearchTextProjection {
    private static final Pattern PRESENTATION = Pattern.compile("</?[A-Za-z][A-Za-z0-9-]*([\\p{javaWhitespace}][^>]*|/?)>|(^|\\n)[ \\t]*([#]{1,6}|[-*>])[ \\t]+|[`*]|~~");
    private static final Pattern SPACE = Pattern.compile("[\\p{javaWhitespace}\\p{Z}\\p{Cc}]+");
    private final String text;

    private SearchTextProjection(String text) { this.text = text; }

    // Matches search_body_v1: authoritative text is never rewritten.
    static SearchTextProjection of(String source) {
        String prefix = prefix(source, 1024);
        String normalized = Normalizer.normalize(prefix, Normalizer.Form.NFC);
        normalized = SPACE.matcher(PRESENTATION.matcher(normalized).replaceAll(" ")).replaceAll(" ").trim();
        return new SearchTextProjection(prefix(normalized.toLowerCase(Locale.ROOT), 1024));
    }

    private static String prefix(String source, int codePoints) {
        // Scan at most the fixed projection prefix, not the entire body.
        int end=0;
        for(int i=0;i<codePoints && end<source.length();i++) end+=Character.charCount(source.codePointAt(end));
        return source.substring(0, end);
    }

    String text() { return text; }

    /** SQL returns at most 240 code points (480 UTF16 units), never complete Markdown. */
    static String snippet(String source) {
        if (source.length() > 480) throw new IllegalArgumentException("Unbounded search snippet source");
        String plain = source.replace("<", "").replace(">", "");
        int end = Math.min(240, plain.length());
        if (end < plain.length() && end > 0 && Character.isHighSurrogate(plain.charAt(end - 1))) end--;
        return plain.substring(0, end);
    }

    @Override public String toString() { return "SearchTextProjection[REDACTED]"; }
}
