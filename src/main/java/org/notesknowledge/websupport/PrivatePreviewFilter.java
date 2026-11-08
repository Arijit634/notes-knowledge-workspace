package org.notesknowledge.websupport;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.notesknowledge.PrivatePreviewConfiguration.PrivatePreview;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Preview availability boundary only. Allowed requests still traverse all existing security. */
@Component
@Profile("private-preview")
@Order(Ordered.HIGHEST_PRECEDENCE + 3)
final class PrivatePreviewFilter extends OncePerRequestFilter {
    private final PrivatePreview preview;
    private final ApiProblemWriter problems;

    PrivatePreviewFilter(PrivatePreview preview, ApiProblemWriter problems) {
        this.preview = preview;
        this.problems = problems;
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        if (request.getLocalPort() == 8081 && "127.0.0.1".equals(request.getRemoteAddr())) {
            chain.doFilter(request, response); return;
        }
        if (!preview.host().equalsIgnoreCase(request.getServerName()) || !request.isSecure()) {
            problems.writeMalformedRequest(request, response); return;
        }
        String path = request.getRequestURI();
        if (path.indexOf('%') >= 0 || path.indexOf('\\') >= 0 || path.contains("//")) {
            problems.writeMalformedRequest(request, response); return;
        }
        if (path.startsWith("/api/") && unavailable(path)) {
            problems.writeServiceUnavailable(request, response); return;
        }
        chain.doFilter(request, response);
    }

    static boolean unavailable(String path) {
        return path.startsWith("/api/knowledge/") || path.startsWith("/api/public/")
            || path.startsWith("/api/moderation/") || path.startsWith("/api/me/publications")
            || path.startsWith("/api/me/public-profile") || path.matches("/api/notes/[^/]+/publication(?:-preview)?(?:/.*)?")
            || path.startsWith("/api/auth/registrations") || path.startsWith("/api/auth/email-verification/")
            || path.startsWith("/api/auth/password-reset/") || path.equals("/api/auth/login/password")
            || path.startsWith("/api/me/security/email") || path.startsWith("/api/me/security/password")
            || path.startsWith("/api/me/security/mfa") || path.startsWith("/api/auth/reauth/");
    }
}
