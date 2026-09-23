package org.notesknowledge.identity;

import java.util.Locale;
import java.util.regex.Pattern;

import org.notesknowledge.websupport.ApiFailureException;

final class IdentityInput {
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]{1,64}@[^\\s@]{1,255}$");

    private IdentityInput() { }

    static String canonicalEmail(String raw) {
        if (raw == null || raw.length() > 320) {
            throw ApiFailureException.of(ApiFailureException.Kind.INVALID_INPUT);
        }
        String trimmed = raw.trim();
        if (trimmed.length() < 3 || !EMAIL.matcher(trimmed).matches()) {
            throw ApiFailureException.of(ApiFailureException.Kind.INVALID_INPUT);
        }
        // Case-fold only. Never rewrite dots, plus-addressing, or provider-specific local parts.
        return trimmed.toLowerCase(Locale.ROOT);
    }

    static void password(String password) {
        if (password == null || password.length() < 12 || password.length() > 256) {
            throw ApiFailureException.of(ApiFailureException.Kind.INVALID_INPUT);
        }
    }
}
