package org.notesknowledge.websupport;

/** Configured per caller; no Product collection size is selected here. */
public record PageLimitPolicy(int defaultLimit, int maximumLimit) {
    public PageLimitPolicy {
        if (defaultLimit < 1 || maximumLimit < defaultLimit || maximumLimit > 10_000) {
            throw new IllegalArgumentException("Invalid page-limit policy");
        }
    }

    public int resolve(Integer requested) {
        if (requested == null) {
            return defaultLimit;
        }
        if (requested < 1 || requested > maximumLimit) {
            throw ApiFailureException.of(ApiFailureException.Kind.MALFORMED_REQUEST);
        }
        return requested;
    }
}
