package org.notesknowledge.websupport;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/** Validates one server-issued strong validator after the caller has authorized the resource. */
@Component
public final class IfMatchPrecondition {

    private static final Pattern SUPPORTED_STRONG_TAG =
            Pattern.compile("\"[A-Za-z0-9_-]{1,128}\"");

    public void requireCurrent(String ifMatch, String currentEtag) {
        Objects.requireNonNull(currentEtag, "currentEtag");
        if (ifMatch == null) {
            throw ApiFailureException.of(ApiFailureException.Kind.PRECONDITION_REQUIRED);
        }
        // One bounded, ASCII-safe strong tag is supported. Other well-formed tags
        // are stale; wildcards, weak tags, lists, and unsafe syntax are unsupported.
        if (!SUPPORTED_STRONG_TAG.matcher(ifMatch).matches()) {
            throw ApiFailureException.of(ApiFailureException.Kind.MALFORMED_REQUEST);
        }
        if (!MessageDigest.isEqual(ifMatch.getBytes(StandardCharsets.US_ASCII),
                currentEtag.getBytes(StandardCharsets.US_ASCII))) {
            throw ApiFailureException.of(ApiFailureException.Kind.STALE_WRITE);
        }
    }
}
