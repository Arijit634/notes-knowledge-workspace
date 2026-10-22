package org.notesknowledge.websupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@Tag("FAST") @Tag("SECURITY")
class ApplicationStaticControllerTest {
    private static final String ID = "01990a55-9e12-7ac4-8f5b-31aa4a91d401";
    private final ApplicationStaticController controller = new ApplicationStaticController(new ByteArrayResource(
        "<!doctype html><html><body>Synthetic application shell</body></html>".getBytes(StandardCharsets.UTF_8)));

    @Test void onlyExistingClientPathsAndHashedAssetsArePublicShellCandidates() {
        for (String path : java.util.List.of("/", "/login", "/notes", "/notes/new", "/notes/" + ID, "/ask",
                "/settings/security/mfa", "/settings/privacy", "/publications/" + ID, "/explore/search",
                "/profile/sample_writer", "/publication/" + ID, "/p/" + ID, "/moderation/reports/" + ID,
                "/assets/index-Abcd1234.js", "/assets/KaTeX_Main-Regular-Abcd1234.woff2")) {
            assertThat(matches("GET", path)).as(path).isTrue();
            assertThat(matches("HEAD", path)).as(path).isTrue();
            assertThat(matches("POST", path)).as(path).isFalse();
        }
        for (String path : java.util.List.of("/api", "/api/notes", "/api/not-implemented", "/actuator", "/error",
                "/.well-known/x", "/admin", "/notes/not-a-uuid", "/notes/../login", "//login", "/login/", "/index.html",
                "/assets/../index.html", "/assets/unhashed.js", "/assets/chunk-Abcd1234.js.map", "/assets/x/part-Abcd1234.js",
                "/profile/invalid-handle", "/profile/a", "/settings/security/secrets")) {
            assertThat(matches("GET", path)).as(path).isFalse();
        }
    }

    @Test void shellIsRevalidatedAndNeverMakesAnApiFallback() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(get("/notes/" + ID).servletPath("/notes/" + ID))
            .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("text/html"))
            .andExpect(header().string("Cache-Control", "no-cache, must-revalidate"))
            .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.containsString("object-src 'none'")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Synthetic application shell")));
        mvc.perform(get("/api/notes").servletPath("/api/notes")).andExpect(status().isNotFound());
        mvc.perform(get("/assets/missing-Abcd1234.js").servletPath("/assets/missing-Abcd1234.js"))
            .andExpect(status().isNotFound()).andExpect(header().string("Cache-Control", "no-store"));
    }
    @Test void defaultDispatcherServletAndContextPathUseTheSameClosedAllowlist() {
        var page = new MockHttpServletRequest("GET", "/workspace/notes"); page.setContextPath("/workspace");
        assertThat(controller.matches(page)).isTrue();
        var api = new MockHttpServletRequest("GET", "/workspace/api/notes"); api.setContextPath("/workspace");
        assertThat(controller.matches(api)).isFalse();
        assertThat(controller.matches(new MockHttpServletRequest("GET", "/notes%2f" + ID))).isFalse();
    }
    private boolean matches(String method, String path) {
        var request = new MockHttpServletRequest(method, path); request.setServletPath(path); return controller.matches(request);
    }
}
