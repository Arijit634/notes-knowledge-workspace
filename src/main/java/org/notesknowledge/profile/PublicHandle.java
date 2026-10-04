package org.notesknowledge.profile;

import java.util.Locale;
import org.notesknowledge.websupport.ApiFailureException;

/** Routing presentation only: ASCII, 3-30 characters, leading letter, case-insensitive equality. */
record PublicHandle(String original, String normalized) {
    static PublicHandle from(String value) {
        if (value == null) return null;
        if (!value.matches("[A-Za-z][A-Za-z0-9_]{2,29}")) {
            throw ApiFailureException.of(ApiFailureException.Kind.INVALID_INPUT);
        }
        return new PublicHandle(value, value.toLowerCase(Locale.ROOT));
    }

    @Override public String toString() { return "PublicHandle[REDACTED]"; }
}
