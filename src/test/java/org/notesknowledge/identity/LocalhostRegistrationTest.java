package org.notesknowledge.identity;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.notesknowledge.security.RateControlService;
import org.notesknowledge.security.RateKeyDeriver;
import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;

@Tag("FAST") @Tag("SECURITY")
class LocalhostRegistrationTest {
    private final RegistrationService registration = mock(RegistrationService.class);
    private final RateControlService rates = mock(RateControlService.class);
    private AuthController controller(boolean local) {
        var environment = new MockEnvironment();
        if (local) environment.setActiveProfiles("operations", "localhost");
        return new AuthController(registration, null, null, null, rates,
            new RateKeyDeriver(java.util.Base64.getEncoder().encodeToString(new byte[32])), null, null, environment);
    }
    @Test void unsupportedLocalAddressIsRejectedBeforeAnyAccountLookupOrMutation() {
        var controller = controller(true);
        for (String address : java.util.List.of("synthetic@example.org", "synthetic@example.test.invalid", "synthetic@notexample.test")) {
            assertThatThrownBy(() -> controller.register(new AuthController.RegistrationInput(address, "Synthetic-password-only-123!"), new MockHttpServletRequest()))
                .isInstanceOf(ApiFailureException.class);
        }
        verifyNoInteractions(registration);
        verify(rates, atLeastOnce()).check(any(), eq(RateControlService.Policy.SECURITY_CRITICAL));
    }
    @Test void localSupportedAddressesKeepGenericAcceptanceIncludingCanonicalCaseAndWhitespace() {
        var input = new AuthController.RegistrationInput(" Synthetic@EXAMPLE.TEST ", "Synthetic-password-only-123!");
        assertThat(controller(true).register(input, new MockHttpServletRequest()).getStatusCode().value()).isEqualTo(202);
        verify(registration).begin(input.email(), input.password());
    }
    @Test void normalDeploymentRetainsOrdinaryEmailRegistration() {
        var input = new AuthController.RegistrationInput("synthetic@example.org", "Synthetic-password-only-123!");
        assertThat(controller(false).register(input, new MockHttpServletRequest()).getStatusCode().value()).isEqualTo(202);
        verify(registration).begin(input.email(), input.password());
    }
}
