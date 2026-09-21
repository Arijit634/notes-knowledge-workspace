package org.notesknowledge.websupport;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import tools.jackson.databind.exc.UnrecognizedPropertyException;

@RestControllerAdvice
public final class ApiProblemHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiProblemHandler.class);

    private final ApiProblemWriter problemWriter;

    public ApiProblemHandler(ApiProblemWriter problemWriter) {
        this.problemWriter = problemWriter;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> unreadable(
            HttpMessageNotReadableException exception,
            HttpServletRequest request) {
        if (hasCause(exception, UnrecognizedPropertyException.class)) {
            return response(problemWriter.create(request, HttpStatus.BAD_REQUEST,
                    "unknown_property", "Unknown request property"));
        }
        return response(problemWriter.create(request, HttpStatus.BAD_REQUEST,
                "malformed_json", "Malformed JSON request"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> invalidArgument(
            MethodArgumentNotValidException exception,
            HttpServletRequest request) {
        List<ApiProblemWriter.ValidationError> errors = exception.getBindingResult()
                .getFieldErrors()
                .stream()
                .sorted(Comparator.comparing(FieldError::getField))
                .map(this::safeValidationError)
                .toList();
        return response(problemWriter.create(request, HttpStatus.UNPROCESSABLE_CONTENT,
                "validation_failed", "Request validation failed", errors));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ProblemDetail> unsupportedMediaType(
            HttpMediaTypeNotSupportedException exception,
            HttpServletRequest request) {
        return response(problemWriter.create(request, HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "unsupported_media_type", "Unsupported media type"));
    }

    @ExceptionHandler(ServletRequestBindingException.class)
    ResponseEntity<ProblemDetail> malformedRequest(
            ServletRequestBindingException exception,
            HttpServletRequest request) {
        return response(problemWriter.create(request, HttpStatus.BAD_REQUEST,
                "malformed_request", "Malformed request"));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ProblemDetail> malformedArgumentType(
            MethodArgumentTypeMismatchException exception,
            HttpServletRequest request) {
        return response(problemWriter.create(request, HttpStatus.BAD_REQUEST,
                "malformed_request", "Malformed request"));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ProblemDetail> resourceNotFound(
            NoResourceFoundException exception,
            HttpServletRequest request) {
        return response(problemWriter.create(request, HttpStatus.NOT_FOUND,
                "resource_not_found", "Resource not found"));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ProblemDetail> requestTooLarge(
            MaxUploadSizeExceededException exception,
            HttpServletRequest request) {
        return response(problemWriter.create(request, HttpStatus.CONTENT_TOO_LARGE,
                "request_too_large", "Request is too large"));
    }

    @ExceptionHandler(ApiFailureException.class)
    ResponseEntity<ProblemDetail> typedFailure(
            ApiFailureException exception,
            HttpServletRequest request) {
        ResponseEntity.BodyBuilder response = ResponseEntity
                .status(exception.status())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .header(HttpHeaders.CACHE_CONTROL, "no-store");
        if (exception.retryAfterSeconds() != null) {
            response.header(HttpHeaders.RETRY_AFTER,
                    Integer.toString(exception.retryAfterSeconds()));
        }
        return response.body(problemWriter.create(
                request,
                exception.status(),
                exception.code(),
                exception.title()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(
            Exception exception,
            HttpServletRequest request) {
        LOGGER.error("Request failed with internal_error");
        return response(problemWriter.create(request, HttpStatus.INTERNAL_SERVER_ERROR,
                "internal_error", "Request could not be completed"));
    }

    private ResponseEntity<ProblemDetail> response(ProblemDetail problem) {
        return ResponseEntity.status(problem.getStatus())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(problem);
    }

    private ApiProblemWriter.ValidationError safeValidationError(FieldError error) {
        String field = error.getField();
        if (field.length() > 128 || !field.matches("[A-Za-z0-9_.\\[\\]-]+")) {
            field = "request";
        }
        return switch (error.getCode() == null ? "" : error.getCode()) {
            case "NotBlank", "NotEmpty", "NotNull" ->
                    new ApiProblemWriter.ValidationError(
                            field, "required", "Value is required.");
            case "Size" -> new ApiProblemWriter.ValidationError(
                    field, "invalid_length", "Value length is outside the allowed range.");
            case "Pattern", "Email" -> new ApiProblemWriter.ValidationError(
                    field, "invalid_format", "Value format is invalid.");
            default -> new ApiProblemWriter.ValidationError(
                    field, "invalid_value", "Value is invalid.");
        };
    }

    private boolean hasCause(Throwable exception, Class<? extends Throwable> type) {
        Throwable current = exception;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
