package org.notesknowledge.websupport;

import org.springframework.http.HttpStatus;

public final class ApiFailureException extends RuntimeException {

    public enum Kind {
        MALFORMED_REQUEST(HttpStatus.BAD_REQUEST,
                "malformed_request", "Malformed request"),
        INVALID_INPUT(HttpStatus.UNPROCESSABLE_CONTENT,
                "validation_failed", "Request validation failed"),
        INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED,
                "invalid_credentials", "Authentication failed"),
        RECENT_AUTHENTICATION_REQUIRED(HttpStatus.FORBIDDEN,
                "recent_authentication_required", "Recent authentication required"),
        RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND,
                "resource_not_found", "Resource not found"),
        INVALID_LIFECYCLE_TRANSITION(HttpStatus.CONFLICT,
                "invalid_lifecycle_transition", "Request conflicts with current state"),
        STALE_WRITE(HttpStatus.PRECONDITION_FAILED,
                "stale_write", "Resource changed since it was loaded"),
        REQUEST_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE,
                "request_too_large", "Request is too large"),
        UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "unsupported_media_type", "Unsupported media type"),
        PRECONDITION_REQUIRED(HttpStatus.PRECONDITION_REQUIRED,
                "precondition_required", "Required precondition is missing"),
        RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS,
                "rate_limited", "Request rate limit reached"),
        SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE,
                "service_unavailable", "Required service is unavailable");

        private final HttpStatus status;
        private final String code;
        private final String title;

        Kind(HttpStatus status, String code, String title) {
            this.status = status;
            this.code = code;
            this.title = title;
        }
    }

    private static final int MAX_RETRY_AFTER_SECONDS = 86_400;

    private final Kind kind;
    private final Integer retryAfterSeconds;

    private ApiFailureException(Kind kind, Integer retryAfterSeconds) {
        super(kind.code);
        this.kind = kind;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public static ApiFailureException of(Kind kind) {
        if (kind == null || kind == Kind.RATE_LIMITED) {
            throw new IllegalArgumentException("A registered non-rate-limited kind is required");
        }
        return new ApiFailureException(kind, null);
    }

    public static ApiFailureException rateLimited(int retryAfterSeconds) {
        if (retryAfterSeconds < 1 || retryAfterSeconds > MAX_RETRY_AFTER_SECONDS) {
            throw new IllegalArgumentException("retryAfterSeconds is outside the allowed range");
        }
        return new ApiFailureException(Kind.RATE_LIMITED, retryAfterSeconds);
    }

    HttpStatus status() {
        return kind.status;
    }

    String code() {
        return kind.code;
    }

    String title() {
        return kind.title;
    }

    Integer retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
