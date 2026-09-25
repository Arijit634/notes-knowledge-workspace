package org.notesknowledge.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2LoginAuthenticationToken;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.oidc.authentication.OidcAuthorizationCodeAuthenticationProvider;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenDecoderFactory;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationExchange;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationResponse;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.util.ReflectionTestUtils;

@Tag("FAST") @Tag("SECURITY")
class GoogleOidcProtocolAdapterTest {
    private static final String ISSUER = "https://accounts.google.com";
    private static final String CLIENT = "synthetic-oidc-client-id";

    @Test void springSecurity71ComponentsAndS256NonceRequestAreAvailableWithoutNetwork()
            throws Exception {
        var provider = new OidcAuthorizationCodeAuthenticationProvider(
                new RestClientAuthorizationCodeTokenResponseClient(),
                user -> new DefaultOidcUser(List.of(new SimpleGrantedAuthority("OIDC")),
                        user.getIdToken()));
        assertThat(provider.supports(OAuth2LoginAuthenticationToken.class)).isTrue();
        assertThat(new OidcIdTokenDecoderFactory()).isNotNull();

        var adapter = adapter();
        ReflectionTestUtils.setField(adapter, "login", registration("google-login",
                "/api/auth/oidc/google/callback"));
        var request = adapter.begin(OidcProtocolPort.Action.LOGIN);
        assertThat(request.getScopes()).containsExactlyInAnyOrder("openid", "email");
        assertThat(request.getState()).hasSize(43);
        assertThat(request.getAttribute("code_verifier").toString()).hasSizeGreaterThan(40);
        assertThat(request.getAdditionalParameters().get("code_challenge_method"))
                .isEqualTo("S256");
        String rawNonce = request.getAttribute("nonce");
        assertThat(rawNonce).hasSize(43);
        assertThat(request.getAdditionalParameters().get("nonce"))
                .isEqualTo(Base64.getUrlEncoder().withoutPadding().encodeToString(
                        MessageDigest.getInstance("SHA-256")
                                .digest(rawNonce.getBytes(StandardCharsets.US_ASCII))));
        assertThat(request.getAuthorizationRequestUri())
                .contains("response_type=code", "code_challenge=", "nonce=", "state=")
                .doesNotContain("client-secret", "code_verifier");
    }

    @Test void validatedClaimsEnforceIssuerAudienceAuthorizedPartyAndTime() {
        var adapter = adapter();
        var registration = registration("google-login", "/api/auth/oidc/google/callback");
        assertThat(adapter.validatedClaims(user(claims()), registration))
                .extracting(OidcProtocolPort.ValidatedPrincipal::subject,
                        OidcProtocolPort.ValidatedPrincipal::emailVerified)
                .containsExactly("synthetic-subject", true);

        Map<String, Object> wrongIssuer = claims();
        wrongIssuer.put("iss", "https://unexpected.example.test");
        assertThatThrownBy(() -> adapter.validatedClaims(user(wrongIssuer), registration))
                .isInstanceOf(ApiFailureException.class);
        Map<String, Object> wrongAudience = claims();
        wrongAudience.put("aud", List.of("unexpected-client"));
        assertThatThrownBy(() -> adapter.validatedClaims(user(wrongAudience), registration))
                .isInstanceOf(ApiFailureException.class);
        Map<String, Object> wrongAzp = claims();
        wrongAzp.put("aud", List.of(CLIENT, "second-audience"));
        wrongAzp.put("azp", "other-presenter");
        assertThatThrownBy(() -> adapter.validatedClaims(user(wrongAzp), registration))
                .isInstanceOf(ApiFailureException.class);
        Map<String, Object> expired = claims();
        expired.put("iat", Instant.now().minusSeconds(3600));
        expired.put("exp", Instant.now().minusSeconds(30));
        assertThatThrownBy(() -> adapter.validatedClaims(user(expired), registration))
                .isInstanceOf(ApiFailureException.class);
        Map<String, Object> unverified = claims();
        unverified.put("email_verified", false);
        assertThat(adapter.validatedClaims(user(unverified), registration).emailVerified())
                .isFalse();
    }

    @Test void springProviderIntegrationEnforcesStateNonceAndDecoderFailuresWithoutInternet() {
        var registration = registration("google-login", "/api/auth/oidc/google/callback");
        var tokenClient = (OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest>)
                grant -> OAuth2AccessTokenResponse.withToken("synthetic-access-token")
                        .tokenType(OAuth2AccessToken.TokenType.BEARER)
                        .scopes(java.util.Set.of("openid", "email"))
                        .additionalParameters(Map.of("id_token", "synthetic-id-token"))
                        .build();
        var provider = new OidcAuthorizationCodeAuthenticationProvider(tokenClient,
                user -> new DefaultOidcUser(List.of(new SimpleGrantedAuthority("OIDC")),
                        user.getIdToken()));
        var adapter = new GoogleOidcProtocolAdapter(properties(), provider, registration, null);
        var authorization = adapter.begin(OidcProtocolPort.Action.LOGIN);
        String nonceHash = authorization.getAdditionalParameters().get("nonce").toString();
        provider.setJwtDecoderFactory(ignored -> encoded -> jwt(nonceHash));
        provider.authenticate(new OAuth2LoginAuthenticationToken(registration,
                new OAuth2AuthorizationExchange(authorization,
                        OAuth2AuthorizationResponse.success("synthetic-code")
                                .state(authorization.getState())
                                .redirectUri(registration.getRedirectUri()).build())));
        var accepted = adapter.verify(OidcProtocolPort.Action.LOGIN, authorization,
                "synthetic-code", authorization.getState());
        assertThat(accepted.subject()).isEqualTo("synthetic-subject");
        assertThat(accepted.emailVerified()).isTrue();
        assertThatThrownBy(() -> adapter.verify(OidcProtocolPort.Action.LOGIN, authorization,
                "synthetic-code", "wrong-state"))
                .isInstanceOf(ApiFailureException.class).hasMessage("invalid_credentials");
        provider.setJwtDecoderFactory(ignored -> encoded -> jwt("wrong-nonce"));
        assertThatThrownBy(() -> adapter.verify(OidcProtocolPort.Action.LOGIN, authorization,
                "synthetic-code", authorization.getState()))
                .isInstanceOf(ApiFailureException.class).hasMessage("invalid_credentials");
        provider.setJwtDecoderFactory(ignored -> encoded -> {
            throw new BadJwtException("synthetic invalid signature");
        });
        assertThatThrownBy(() -> adapter.verify(OidcProtocolPort.Action.LOGIN, authorization,
                "synthetic-code", authorization.getState()))
                .isInstanceOf(ApiFailureException.class).hasMessage("invalid_credentials");
    }

    @Test void disabledModeFailsBoundedlyWithoutCredentialsOrNetwork() {
        var disabled = new GoogleOidcProtocolAdapter(new GoogleOidcProperties(false,
                "", "", ISSUER, "", "", Duration.ofMinutes(5)));
        assertThatThrownBy(() -> disabled.begin(OidcProtocolPort.Action.LOGIN))
                .isInstanceOf(ApiFailureException.class)
                .hasMessage("service_unavailable");
    }

    private GoogleOidcProtocolAdapter adapter() {
        return new GoogleOidcProtocolAdapter(properties());
    }

    private GoogleOidcProperties properties() {
        return new GoogleOidcProperties(true, CLIENT,
                "synthetic-client-secret", ISSUER,
                "https://example.test/api/auth/oidc/google/callback",
                "https://example.test/api/auth/reauth/oidc/google/callback",
                Duration.ofMinutes(5));
    }

    private Jwt jwt(String nonce) {
        return Jwt.withTokenValue("synthetic-id-token")
                .header("alg", "RS256")
                .issuer(ISSUER).subject("synthetic-subject")
                .audience(List.of(CLIENT))
                .issuedAt(Instant.now().minusSeconds(10))
                .expiresAt(Instant.now().plusSeconds(3600))
                .claim("nonce", nonce)
                .claim("email", "synthetic@example.test")
                .claim("email_verified", true).build();
    }

    private ClientRegistration registration(String id, String path) {
        return ClientRegistration.withRegistrationId(id)
                .clientId(CLIENT).clientSecret("synthetic-client-secret")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                .tokenUri("https://oauth2.googleapis.com/token")
                .jwkSetUri("https://www.googleapis.com/oauth2/v3/certs")
                .issuerUri(ISSUER)
                .redirectUri("https://example.test" + path)
                .scope("openid", "email").build();
    }

    private Map<String, Object> claims() {
        Map<String, Object> claims = new HashMap<>();
        claims.put("iss", ISSUER);
        claims.put("sub", "synthetic-subject");
        claims.put("aud", List.of(CLIENT));
        claims.put("email", "synthetic@example.test");
        claims.put("email_verified", true);
        claims.put("iat", Instant.now().minusSeconds(10));
        claims.put("exp", Instant.now().plusSeconds(3600));
        return claims;
    }

    private DefaultOidcUser user(Map<String, Object> claims) {
        var token = new OidcIdToken("synthetic-not-a-signed-token",
                (Instant) claims.get("iat"), (Instant) claims.get("exp"), claims);
        return new DefaultOidcUser(List.of(new SimpleGrantedAuthority("OIDC")), token);
    }
}
