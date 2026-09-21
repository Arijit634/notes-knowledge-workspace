package org.notesknowledge.websupport;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class RequestCorrelationFilter extends OncePerRequestFilter {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(RequestCorrelationFilter.class);
    private static final String UNKNOWN_ROUTE = "UNMATCHED";

    private final RequestTraceContext traceContext;

    public RequestCorrelationFilter(RequestTraceContext traceContext) {
        this.traceContext = traceContext;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        long startedAt = System.nanoTime();
        String traceId = traceContext.establish(request);
        boolean failedBeforeResponse = false;
        MDC.put(RequestTraceContext.MDC_KEY, traceId);
        try {
            filterChain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException exception) {
            failedBeforeResponse = true;
            throw exception;
        } finally {
            try {
                logCompletion(request, response, traceId, startedAt, failedBeforeResponse);
            } finally {
                MDC.remove(RequestTraceContext.MDC_KEY);
                request.removeAttribute(RequestTraceContext.REQUEST_ATTRIBUTE);
            }
        }
    }

    private void logCompletion(
            HttpServletRequest request,
            HttpServletResponse response,
            String traceId,
            long startedAt,
            boolean failedBeforeResponse) {
        int status = response.getStatus();
        if (failedBeforeResponse && status < 400) {
            status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
        }
        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        String outcome = status < 400 ? "success" : "failure";
        LoggingEventBuilder event = status < 400 ? LOGGER.atInfo() : LOGGER.atWarn();
        event.addKeyValue("event.name", "request.completed")
                .addKeyValue("http.route", resolvedRoute(request))
                .addKeyValue("http.method", safeMethod(request.getMethod()))
                .addKeyValue("http.status_code", status)
                .addKeyValue("durationMs", durationMs)
                .addKeyValue("outcome", outcome)
                .log("request.completed");
    }

    private String resolvedRoute(HttpServletRequest request) {
        Object route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (route == null) {
            return UNKNOWN_ROUTE;
        }
        String value = route.toString();
        if (value.length() > 160 || !value.matches("/[A-Za-z0-9_{}.*:/-]*")) {
            return UNKNOWN_ROUTE;
        }
        return value;
    }

    private String safeMethod(String method) {
        if (method == null || !method.matches("[A-Za-z]{3,10}")) {
            return "UNKNOWN";
        }
        return method.toUpperCase(Locale.ROOT);
    }
}
