package org.notesknowledge.websupport;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.env.Environment;
import java.nio.charset.StandardCharsets;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/** Same-deployable delivery only when an actual production SPA was packaged.
 * Client routes never fall through from /api, management, errors or arbitrary paths.
 * Serving a private route's shell grants no authority to its backend resources.
 */
@Controller
@ConditionalOnResource(resources = "classpath:/static/index.html")
public final class ApplicationStaticController implements RequestMatcher {
    private static final Set<String> PAGES = Set.of("/", "/signup", "/verify-email", "/login", "/mfa",
        "/forgot-password", "/reset-password", "/auth/complete", "/reauth", "/notes", "/notes/new", "/ask",
        "/settings/security", "/settings/security/mfa", "/settings/security/sessions", "/settings/profile",
        "/settings/privacy", "/settings/public-profile", "/publications", "/explore", "/explore/search", "/moderation/reports");
    private static final String UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final Pattern RESOURCE_PAGE = Pattern.compile("^/(?:notes|publications|publication|p|moderation/reports)/" + UUID + "$");
    private static final Pattern AUTHOR_PAGE = Pattern.compile("^/profile/[A-Za-z][A-Za-z0-9_]{2,29}$");
    private static final Pattern ASSET = Pattern.compile("^[A-Za-z0-9_-]+-[A-Za-z0-9_-]{8,}\\.(?:js|css|woff2?|ttf|svg|png|jpe?g)$");
    private static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
        + "img-src 'self' blob: data:; media-src 'self' blob:; font-src 'self'; connect-src 'self'; "
        + "object-src 'none'; frame-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'";
    private final Resource index;
    private final String presentation;

    @Autowired
    public ApplicationStaticController(Environment environment) {
        this(new ClassPathResource("static/index.html"),
            environment.getProperty("identity.oidc.google.enabled", Boolean.class, false),
            environment.matchesProfiles("localhost"));
    }
    ApplicationStaticController(Resource index) { this(index, false, false); }
    ApplicationStaticController(Resource index, boolean googleEnabled, boolean syntheticEmailOnly) {
        this.index = index;
        // Non-secret deployment presentation only. Never session/Account authority,
        // provider identifiers, credentials, arbitrary strings or an inline script.
        presentation = "google=" + (googleEnabled ? "enabled" : "disabled")
            + ";email=" + (syntheticEmailOnly ? "synthetic" : "ordinary");
    }

    static boolean page(String path) {
        return path != null && (PAGES.contains(path) || RESOURCE_PAGE.matcher(path).matches() || AUTHOR_PAGE.matcher(path).matches());
    }

    @Override public boolean matches(HttpServletRequest request) {
        if (!Set.of("GET", "HEAD").contains(request.getMethod())) return false;
        String path = path(request);
        return page(path) || (path.startsWith("/assets/") && ASSET.matcher(path.substring(8)).matches());
    }

    @GetMapping({"/", "/signup", "/verify-email", "/login", "/mfa", "/forgot-password", "/reset-password",
        "/auth/complete", "/reauth", "/notes", "/notes/new", "/notes/{noteId}", "/ask", "/settings/security",
        "/settings/security/mfa", "/settings/security/sessions", "/settings/profile", "/settings/privacy",
        "/settings/public-profile", "/publications", "/publications/{publicationId}", "/explore", "/explore/search",
        "/profile/{handle}", "/publication/{publicationId}", "/p/{publicationId}", "/moderation/reports", "/moderation/reports/{reportId}"})
    ResponseEntity<Resource> shell(HttpServletRequest request) throws java.io.IOException {
        if (!page(path(request)) || !index.exists()) return missing();
        var content = index.getContentAsString(StandardCharsets.UTF_8)
            .replace("name=\"nkw-presentation\" content=\"unconfigured\"", "name=\"nkw-presentation\" content=\"" + presentation + "\"");
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).cacheControl(CacheControl.noCache().mustRevalidate())
            .header("Content-Security-Policy", CSP).body(new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)));
    }

    @GetMapping("/assets/{file}")
    ResponseEntity<Resource> asset(@PathVariable String file) {
        if (!ASSET.matcher(file).matches()) return missing();
        var resource = new ClassPathResource("static/assets/" + file);
        if (!resource.exists() || !resource.isReadable()) return missing();
        String type = file.endsWith(".js") ? "text/javascript" : file.endsWith(".css") ? "text/css"
            : file.endsWith(".woff2") ? "font/woff2" : file.endsWith(".woff") ? "font/woff" : file.endsWith(".ttf") ? "font/ttf"
            : file.endsWith(".svg") ? "image/svg+xml" : file.endsWith(".png") ? "image/png" : "image/jpeg";
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(type))
            .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable()).body(resource);
    }
    private static ResponseEntity<Resource> missing() { return ResponseEntity.notFound().cacheControl(CacheControl.noStore()).build(); }
    private static String path(HttpServletRequest request) {
        String servlet = request.getServletPath();
        // Root DispatcherServlet requests in MockMvc may have an empty servletPath.
        // The container context is removed; encoded/dot paths still fail our allowlist.
        if (servlet != null && !servlet.isEmpty()) return servlet;
        String uri = request.getRequestURI(), context = request.getContextPath();
        return uri != null && uri.startsWith(context) ? uri.substring(context.length()) : "";
    }
}
