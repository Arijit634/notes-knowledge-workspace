package org.notesknowledge.security;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

@Tag("FAST") @Tag("SECURITY")
class PublicViewKeyTest {
    private final PublicReadRateControl control = new PublicReadRateControl(mock(RateControlService.class),
        new RateKeyDeriver("AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI="));

    @Test void anonymousReadWithoutSessionDoesNotCreateTrackingState() {
        var request = new MockHttpServletRequest();
        assertThat(control.viewKey(request, null)).isNull();
        assertThat(request.getSession(false)).isNull();
    }

    @Test void existingAnonymousSessionsAreBoundedOpaqueHintsNotIpIdentities() {
        var request = new MockHttpServletRequest();
        request.setSession(new MockHttpSession(null, "synthetic-anonymous-browser-a"));
        request.setRemoteAddr("192.0.2.10");
        var first = control.viewKey(request, null);
        request.setRemoteAddr("192.0.2.11");
        assertThat(control.viewKey(request, null).value()).isEqualTo(first.value());
        request.setSession(new MockHttpSession(null, "synthetic-anonymous-browser-b"));
        assertThat(control.viewKey(request, null).value()).isNotEqualTo(first.value());
        assertThat(first.value()).doesNotContain("synthetic-anonymous-browser-a", "192.0.2.10");
        assertThat(first.toString()).isEqualTo("OpaqueKey[REDACTED]");
    }

    @Test void authenticatedHintsUseAccountNotSessionOrAddressAndNeverRawIdentity() {
        var request = new MockHttpServletRequest();
        UUID user = new UUID(0, 7);
        var first = control.viewKey(request, user);
        request.setSession(new MockHttpSession(null, "synthetic-other-session"));
        request.setRemoteAddr("192.0.2.20");
        assertThat(control.viewKey(request, user).value()).isEqualTo(first.value());
        assertThat(control.viewKey(request, new UUID(0, 8)).value()).isNotEqualTo(first.value());
        assertThat(first.value()).doesNotContain(user.toString(), "synthetic-other-session");
    }
}
