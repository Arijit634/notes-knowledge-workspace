package org.notesknowledge.websupport;

import jakarta.servlet.http.HttpServletRequest;

import java.security.SecureRandom;
import java.util.HexFormat;

import org.springframework.stereotype.Component;

@Component
public final class RequestTraceContext {

    static final String REQUEST_ATTRIBUTE = RequestTraceContext.class.getName() + ".traceId";
    static final String MDC_KEY = "trace.id";

    private static final int TRACE_BYTES = 16;

    private final SecureRandom secureRandom = new SecureRandom();

    public String establish(HttpServletRequest request) {
        String traceId = generate();
        request.setAttribute(REQUEST_ATTRIBUTE, traceId);
        return traceId;
    }

    public String currentOrCreate(HttpServletRequest request) {
        Object current = request.getAttribute(REQUEST_ATTRIBUTE);
        if (current instanceof String traceId && !traceId.isBlank()) {
            return traceId;
        }
        return establish(request);
    }

    private String generate() {
        byte[] bytes = new byte[TRACE_BYTES];
        secureRandom.nextBytes(bytes);
        return "tr_" + HexFormat.of().formatHex(bytes);
    }
}
