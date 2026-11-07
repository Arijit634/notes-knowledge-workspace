package org.notesknowledge.security;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.session.web.http.CookieSerializer;

@Tag("FAST") @Tag("SECURITY")
class DevelopmentSessionCookieTest {
    @Test void isolatedGoogleInstanceCannotOverwriteOriginalSessionCookie() {
        var e=new MockEnvironment();e.setActiveProfiles("operations","google-oidc-dev");
        assertThat(cookie(e)).startsWith("NKW_OIDC_DEV_SESSION=").contains("Secure","HttpOnly","SameSite=Lax","Path=/").doesNotContain("Domain=");
        e.setActiveProfiles("operations","localhost");assertThat(cookie(e)).startsWith("SESSION=");
        e.setActiveProfiles("operations");assertThat(cookie(e)).startsWith("SESSION=");
    }
    private String cookie(MockEnvironment e) {
        var response=new MockHttpServletResponse();
        new SecurityConfiguration().sessionCookieSerializer(e).writeCookieValue(new CookieSerializer.CookieValue(new MockHttpServletRequest(),response,"synthetic-opaque"));
        return response.getHeader("Set-Cookie");
    }
}
