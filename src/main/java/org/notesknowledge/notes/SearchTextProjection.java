package org.notesknowledge.notes;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Pattern;

/** Transient normalized-to-authoritative Markdown offsets; never stored as another corpus. */
final class SearchTextProjection {
    private static final Pattern PRESENTATION = Pattern.compile("</?[A-Za-z][A-Za-z0-9-]*([\\p{javaWhitespace}][^>]*|/?)>|(^|\\n)[ \\t]*([#]{1,6}|[-*>])[ \\t]+|[`*]|~~");
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}_]+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern GRAPHEME = Pattern.compile("\\X");
    private final String text;
    private final int[] starts, ends;

    private SearchTextProjection(String text, int[] starts, int[] ends) {
        this.text = text; this.starts = starts; this.ends = ends;
    }

    static SearchTextProjection of(String source) {
        char[] masked = source.toCharArray();
        var matches = PRESENTATION.matcher(source);
        while (matches.find()) Arrays.fill(masked, matches.start(), matches.end(), ' ');
        StringBuilder text = new StringBuilder();
        int[] starts = new int[source.length() * 2 + 1], ends = new int[starts.length];
        var clusters = GRAPHEME.matcher(new String(masked));
        while (clusters.find()) {
            int from = clusters.start(), at = clusters.end(), code = Character.codePointAt(masked, from);
            if (Character.isWhitespace(code) || Character.isSpaceChar(code) || Character.isISOControl(code)) {
                if (!text.isEmpty() && text.charAt(text.length()-1) != ' ') {
                    starts[text.length()] = from; ends[text.length()] = at; text.append(' ');
                }
                continue;
            }
            String normalized = Normalizer.normalize(new String(masked, from, at-from), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
            for (int i = 0; i < normalized.length(); i++) {
                starts[text.length()] = from; ends[text.length()] = at; text.append(normalized.charAt(i));
            }
        }
        if (!text.isEmpty() && text.charAt(text.length()-1) == ' ') text.setLength(text.length()-1);
        return new SearchTextProjection(text.toString(), Arrays.copyOf(starts,text.length()), Arrays.copyOf(ends,text.length()));
    }

    String text() { return text; }
    int sourceStart(int normalizedOffset) { return starts[normalizedOffset]; }
    int sourceEnd(int normalizedOffset) { return ends[normalizedOffset]; }

    String snippet(String query, String variant) {
        int match = text.indexOf(query);
        if (match < 0) match = text.indexOf(variant);
        if (match < 0) {
            String token = query.split(" ",2)[0];
            var words = WORD.matcher(text);
            int inspected = 0;
            while (words.find() && inspected++ < 10000) {
                String word = words.group();
                if ((token.length() >= 3 && word.startsWith(token)) || oneEdit(token,word)) { match = words.start(); break; }
            }
        }
        int start = Math.max(0, match - 60), end = Math.min(text.length(), start + 240);
        // Never split a surrogate pair at the presentation boundary.
        if (start > 0 && Character.isLowSurrogate(text.charAt(start))) start--;
        if (end < text.length() && end > 0 && Character.isHighSurrogate(text.charAt(end-1))) end--;
        return text.substring(start,end).replace("<", "").replace(">", "");
    }

    private static boolean oneEdit(String a, String b) {
        if (a.length() < 3 || a.length() > 32 || b.length() > 32 || Math.abs(a.length()-b.length()) > 1) return false;
        int i=0,j=0,edits=0;
        while(i<a.length() && j<b.length()) {
            if(a.charAt(i)==b.charAt(j)) { i++;j++; }
            else { if(++edits>1)return false; if(a.length()>=b.length())i++;if(b.length()>=a.length())j++; }
        }
        return edits+(a.length()-i)+(b.length()-j)<=1;
    }
    @Override public String toString() { return "SearchTextProjection[REDACTED]"; }
}
