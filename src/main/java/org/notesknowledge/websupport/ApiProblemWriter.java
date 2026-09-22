package org.notesknowledge.websupport;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.stereotype.Component;

import org.notesknowledge.security.SecurityControlRejectionEvents;

import tools.jackson.databind.ObjectMapper;

@Component
public final class ApiProblemWriter {

    private static final URI ABOUT_BLANK = URI.create("about:blank");
    private static final URI SAFE_FALLBACK_INSTANCE = URI.create("/api");

    private final ObjectMapper objectMapper;
    private final RequestTraceContext traceContext;
    private final SecurityControlRejectionEvents rejectionEvents;

    public ApiProblemWriter(ObjectMapper objectMapper, RequestTraceContext traceContext,
            SecurityControlRejectionEvents rejectionEvents) {
        this.objectMapper = objectMapper;
        this.traceContext = traceContext;
        this.rejectionEvents = rejectionEvents;
    }

    public ProblemDetail create(
            HttpServletRequest request,
            HttpStatus status,
            String code,
            String title) {
        return create(request, status, code, title, List.of());
    }

    public ProblemDetail create(
            HttpServletRequest request,
            HttpStatus status,
            String code,
            String title,
            List<ValidationError> errors) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setType(ABOUT_BLANK);
        problem.setTitle(title);
        problem.setInstance(safeInstance(request));
        problem.setProperty("code", code);
        problem.setProperty("traceId", traceContext.currentOrCreate(request));
        if (!errors.isEmpty()) {
            problem.setProperty("errors", List.copyOf(errors));
        }
        return problem;
    }

    public void writeAuthenticationRequired(
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        write(request, response, HttpStatus.UNAUTHORIZED,
                "authentication_required", "Authentication required");
    }

    public void writeAccessDenied(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException exception) throws IOException {
        if (exception instanceof CsrfException) {
            rejectionEvents.rejected(SecurityControlRejectionEvents.Control.CSRF,
                    SecurityControlRejectionEvents.SafeReasonClass.INVALID_CSRF);
            write(request, response, HttpStatus.FORBIDDEN,
                    "csrf_invalid", "CSRF validation failed");
            return;
        }
        rejectionEvents.rejected(SecurityControlRejectionEvents.Control.AUTHORIZATION,
                SecurityControlRejectionEvents.SafeReasonClass.ACCESS_DENIED);
        write(request, response, HttpStatus.FORBIDDEN,
                "access_denied", "Access denied");
    }

    private void write(
            HttpServletRequest request,
            HttpServletResponse response,
            HttpStatus status,
            String code,
            String title) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.resetBuffer();
        response.setStatus(status.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        objectMapper.writeValue(response.getOutputStream(),
                create(request, status, code, title));
    }

    private URI safeInstance(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null
                || path.isBlank()
                || path.length() > 512
                || !path.matches("/[A-Za-z0-9._~!$&'()*+,;=:@%/-]*")) {
            return SAFE_FALLBACK_INSTANCE;
        }
        try {
            return URI.create(path);
        } catch (IllegalArgumentException exception) {
            return SAFE_FALLBACK_INSTANCE;
        }
    }

    public record ValidationError(String field, String code, String message) {
    }
}
