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
    private final io.micrometer.core.instrument.MeterRegistry meters;
    private final java.util.Map<String,java.util.concurrent.atomic.AtomicInteger> inflight=new java.util.HashMap<>();

    public RequestCorrelationFilter(RequestTraceContext traceContext) {
        this(traceContext,null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RequestCorrelationFilter(RequestTraceContext traceContext,
            org.springframework.beans.factory.ObjectProvider<io.micrometer.core.instrument.MeterRegistry> registries) {
        this.traceContext = traceContext;
        this.meters=registries==null?null:registries.getIfAvailable();
        for(String module:java.util.List.of("identity","profile","notes","knowledge","publishing","discovery","moderation")){
            var count=new java.util.concurrent.atomic.AtomicInteger();inflight.put(module,count);
            try{if(meters!=null)io.micrometer.core.instrument.Gauge.builder("notes.workspace.http.inflight",count,java.util.concurrent.atomic.AtomicInteger::get)
                .tag("module",module).register(meters);}catch(RuntimeException unavailable){/* diagnostic only */}
        }
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
        var count=inflight.get(module(request.getRequestURI()));if(count!=null)count.incrementAndGet();
        try {
            filterChain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException exception) {
            failedBeforeResponse = true;
            throw exception;
        } finally {
            try {
                logCompletion(request, response, traceId, startedAt, failedBeforeResponse);
            } finally {
                if(count!=null)count.decrementAndGet();
                MDC.remove(RequestTraceContext.MDC_KEY);
                request.removeAttribute(RequestTraceContext.REQUEST_ATTRIBUTE);
                request.removeAttribute(ResponseStreamInterruptedException.REQUEST_ATTRIBUTE);
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
        boolean interrupted = Boolean.TRUE.equals(request.getAttribute(ResponseStreamInterruptedException.REQUEST_ATTRIBUTE));
        if (failedBeforeResponse && status < 400 && !interrupted) {
            status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
        }
        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        boolean successful = status < 400 && !interrupted;
        String outcome = successful ? "success" : "failure";
        LoggingEventBuilder event = successful ? LOGGER.atInfo() : LOGGER.atWarn();
        event.addKeyValue("event.name", "request.completed")
                .addKeyValue("http.route", resolvedRoute(request))
                .addKeyValue("http.method", safeMethod(request.getMethod()))
                .addKeyValue("http.status_code", status)
                .addKeyValue("durationMs", durationMs)
                .addKeyValue("outcome", outcome)
                .log("request.completed");
        commandEvent(request,status,System.nanoTime()-startedAt);
    }

    private void commandEvent(HttpServletRequest request,int status,long nanos){
        String route=resolvedRoute(request),method=safeMethod(request.getMethod());
        boolean oidcCallback=method.equals("GET")&&route.contains("/oidc/google/callback");
        if((method.equals("GET")||method.equals("HEAD"))&&!oidcCallback)return;
        org.notesknowledge.BackendTelemetry.Event event=null;
        org.notesknowledge.BackendTelemetry.Operation operation=null;
        if(route.startsWith("/api/auth/")||route.startsWith("/api/me/security/")){
            event=org.notesknowledge.BackendTelemetry.Event.AUTH;
            operation=route.contains("/oidc/")?org.notesknowledge.BackendTelemetry.Operation.OIDC:
                route.contains("/mfa/")?org.notesknowledge.BackendTelemetry.Operation.MFA:
                route.contains("/reauth/")?org.notesknowledge.BackendTelemetry.Operation.RECENT_AUTH:
                route.contains("password-reset")?org.notesknowledge.BackendTelemetry.Operation.RECOVERY:
                route.contains("registrations")?org.notesknowledge.BackendTelemetry.Operation.REGISTRATION:
                route.contains("email-verification")?org.notesknowledge.BackendTelemetry.Operation.VERIFICATION:
                route.contains("login/password")?org.notesknowledge.BackendTelemetry.Operation.PASSWORD_LOGIN:
                route.contains("logout")?org.notesknowledge.BackendTelemetry.Operation.LOGOUT:org.notesknowledge.BackendTelemetry.Operation.SECURITY_SETTINGS;
        }else if((route.equals("/api/notes")||route.startsWith("/api/notes/{noteId}"))
                &&!route.contains("attachments")&&!route.contains("publication")&&!route.endsWith("/related")&&!route.endsWith("/organization-suggestions")){
            event=org.notesknowledge.BackendTelemetry.Event.NOTE;
            operation=route.contains("/tags")?org.notesknowledge.BackendTelemetry.Operation.TAGS:
                route.contains("/pin")?org.notesknowledge.BackendTelemetry.Operation.PIN:
                route.contains("/versions")?org.notesknowledge.BackendTelemetry.Operation.VERSIONS:
                route.contains("ai-access")?org.notesknowledge.BackendTelemetry.Operation.AI_ACCESS:
                route.equals("/api/notes")?org.notesknowledge.BackendTelemetry.Operation.CREATE:
                method.equals("PUT")?org.notesknowledge.BackendTelemetry.Operation.SAVE:org.notesknowledge.BackendTelemetry.Operation.LIFECYCLE;
        }
        if(event!=null)org.notesknowledge.BackendTelemetry.record(meters,event,operation,
            org.notesknowledge.BackendTelemetry.httpOutcome(status),nanos);
    }

    private static String module(String path){
        if(path==null)return "identity";
        if(path.startsWith("/api/notes"))return "notes";
        if(path.startsWith("/api/knowledge")||path.startsWith("/api/ai"))return "knowledge";
        if(path.startsWith("/api/moderation"))return "moderation";
        if(path.startsWith("/api/me/publications")||path.startsWith("/api/public/publications"))return "publishing";
        if(path.startsWith("/api/public"))return "discovery";
        if(path.startsWith("/api/me/profile")||path.startsWith("/api/me/public-profile"))return "profile";
        return "identity";
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
