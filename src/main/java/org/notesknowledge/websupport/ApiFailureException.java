package org.notesknowledge.websupport;

import org.springframework.http.HttpStatus;

public final class ApiFailureException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final String title;
    private final Integer retryAfterSeconds;

    private ApiFailureException(
            HttpStatus status,
            String code,
            String title,
            Integer retryAfterSeconds) {
        super(code);
        this.status = status;
        this.code = code;
        this.title = title;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public static ApiFailureException of(HttpStatus status, String code, String title) {
        return new ApiFailureException(status, code, title, null);
    }

    public static ApiFailureException rateLimited(int retryAfterSeconds) {
        if (retryAfterSeconds < 1) {
            throw new IllegalArgumentException("retryAfterSeconds must be positive");
        }
        return new ApiFailureException(
                HttpStatus.TOO_MANY_REQUESTS,
                "rate_limited",
                "Request rate limit reached",
                retryAfterSeconds);
    }

    HttpStatus status() {
        return status;
    }

    String code() {
        return code;
    }

    String title() {
        return title;
    }

    Integer retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
