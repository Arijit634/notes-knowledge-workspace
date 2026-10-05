package org.notesknowledge.notes;

import org.notesknowledge.websupport.ApiFailureException;

/** Strict single-range grammar, bounded before numeric parsing; all arithmetic is long. */
final class MediaRangeParser {
    static final int MAX_HEADER_LENGTH = 256;
    private MediaRangeParser() { }

    static Selection parse(String header, long size) {
        if (size <= 0) throw new IllegalArgumentException("Positive representation size required");
        if (header == null) return new Selection(false, 0, size - 1);
        if (header.isEmpty() || header.length() > MAX_HEADER_LENGTH || !header.startsWith("bytes=")) throw malformed();
        String value = header.substring(6);
        int dash = value.indexOf('-');
        if (dash < 0 || dash != value.lastIndexOf('-') || value.length() == 1) throw malformed();
        if (dash == 0) {
            long suffix = number(value.substring(1));
            if (suffix == 0) throw ApiFailureException.rangeNotSatisfiable(size);
            return new Selection(true, suffix >= size ? 0 : size - suffix, size - 1);
        }
        long start = number(value.substring(0, dash));
        long end = dash == value.length() - 1 ? size - 1 : number(value.substring(dash + 1));
        if (start >= size || end < start) throw ApiFailureException.rangeNotSatisfiable(size);
        return new Selection(true, start, Math.min(end, size - 1));
    }

    private static long number(String value) {
        if (value.isEmpty()) throw malformed();
        long result = 0;
        try {
            for (int i = 0; i < value.length(); i++) {
                char digit = value.charAt(i);
                if (digit < '0' || digit > '9') throw malformed();
                result = Math.addExact(Math.multiplyExact(result, 10), digit - '0');
            }
        } catch (ArithmeticException failure) { throw malformed(); }
        return result;
    }

    private static ApiFailureException malformed() { return ApiFailureException.of(ApiFailureException.Kind.MALFORMED_REQUEST); }
    record Selection(boolean partial, long start, long end) {
        long length() { return Math.addExact(Math.subtractExact(end, start), 1); }
    }
}
