package org.notesknowledge.websupport;

import static org.mockito.Mockito.*;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.notesknowledge.PrivatePreviewConfiguration.PrivatePreview;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@Tag("FAST") @Tag("SECURITY")
class PrivatePreviewFilterTest {
    @Test void disabledFeaturesNeverReachTheirControllers() throws Exception {
        for (String path : List.of("/api/knowledge/query", "/api/public/explore", "/api/moderation/reports",
                "/api/me/publications", "/api/auth/registrations", "/api/auth/password-reset/requests",
                "/api/me/security/password", "/api/notes/synthetic/publication", "/api/notes/synthetic/publication-preview")) {
            var problems = mock(ApiProblemWriter.class);
            var request = request(path); var response = new MockHttpServletResponse();
            var chain = mock(jakarta.servlet.FilterChain.class);
            new PrivatePreviewFilter(new PrivatePreview("preview.example.test"), problems).doFilter(request, response, chain);
            verify(problems).writeServiceUnavailable(request, response); verifyNoInteractions(chain);
        }
    }
    @Test void hostTransportAndEncodedPathsCannotBypassTheBoundary() throws Exception {
        for (String mode : List.of("host", "transport", "encoding")) {
            var problems = mock(ApiProblemWriter.class);var request=request("/api/notes");
            if (mode.equals("host")) request.setServerName("untrusted.example.test");
            if (mode.equals("transport")) request.setSecure(false);
            if (mode.equals("encoding")) request.setRequestURI("/api/%70ublic/explore");
            var response=new MockHttpServletResponse();var chain=mock(jakarta.servlet.FilterChain.class);
            new PrivatePreviewFilter(new PrivatePreview("preview.example.test"),problems).doFilter(request,response,chain);
            verify(problems).writeMalformedRequest(request,response);verifyNoInteractions(chain);
        }
    }
    @Test void coreProductRequestsStillTraverseExistingSecurity() throws Exception {
        for (String path : List.of("/api/auth/csrf", "/api/auth/session", "/api/auth/logout",
                "/api/auth/oidc/google/callback", "/api/notes", "/api/notes/search",
                "/api/notes/synthetic/attachments", "/api/me/profile", "/api/me/security/sessions")) {
            var problems=mock(ApiProblemWriter.class);var request=request(path);var response=new MockHttpServletResponse();
            var chain=mock(jakarta.servlet.FilterChain.class);
            new PrivatePreviewFilter(new PrivatePreview("preview.example.test"),problems).doFilter(request,response,chain);
            verify(chain).doFilter(request,response);verifyNoInteractions(problems);
        }
    }
    private MockHttpServletRequest request(String path) {
        var r=new MockHttpServletRequest("GET",path);r.setLocalPort(8080);r.setServerName("preview.example.test");r.setSecure(true);return r;
    }
}
