package org.notesknowledge.identity;

import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2LoginAuthenticationToken;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.oidc.authentication.OidcAuthorizationCodeAuthenticationProvider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationExchange;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationResponse;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

/** Spring Security performs the code exchange, signature/JWK and OIDC token validation. */
@Component
@IdentityCoreEnabled
final class GoogleOidcProtocolAdapter implements OidcProtocolPort {
    private final GoogleOidcProperties properties;
    private final SecureRandom random = new SecureRandom();
    private final OidcAuthorizationCodeAuthenticationProvider provider;
    private volatile ClientRegistration login;
    private volatile ClientRegistration recent;

    @Autowired
    GoogleOidcProtocolAdapter(GoogleOidcProperties properties) {
        this(properties, new OidcAuthorizationCodeAuthenticationProvider(
                new RestClientAuthorizationCodeTokenResponseClient(),
                user -> new DefaultOidcUser(
                        List.of(new SimpleGrantedAuthority("OIDC")), user.getIdToken())),
                null, null);
    }

    GoogleOidcProtocolAdapter(GoogleOidcProperties properties,
            OidcAuthorizationCodeAuthenticationProvider provider,
            ClientRegistration login, ClientRegistration recent) {
        this.properties = properties;
        this.provider = provider;
        this.login = login;
        this.recent = recent;
    }

    @Override
    public OAuth2AuthorizationRequest begin(Action action) {
        ClientRegistration registration = registration(action);
        String nonce = randomValue();
        var builder = OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri(registration.getProviderDetails().getAuthorizationUri())
                .clientId(registration.getClientId())
                .redirectUri(registration.getRedirectUri())
                .scopes(registration.getScopes())
                .state(randomValue())
                .attributes(attributes -> attributes.put("nonce", nonce))
                .additionalParameters(parameters -> {
                    parameters.put("nonce", sha256(nonce));
                    if (action == Action.RECENT_AUTH) {
                        // Google documents this narrow claims request for ID-token auth_time.
                        parameters.put("max_age", "0");
                        parameters.put("claims",
                                "{\"id_token\":{\"auth_time\":{\"essential\":true}}}");
                    }
                });
        OAuth2AuthorizationRequestCustomizers.withPkce().accept(builder);
        OAuth2AuthorizationRequest authorization = builder.build();
        if (!"S256".equals(authorization.getAdditionalParameters().get("code_challenge_method"))
                || authorization.getAttribute("code_verifier") == null) {
            throw ApiFailureException.of(ApiFailureException.Kind.SERVICE_UNAVAILABLE);
        }
        return authorization;
    }

    @Override
    public ValidatedPrincipal verify(Action action, OAuth2AuthorizationRequest authorization,
            String code, String returnedState) {
        ClientRegistration registration = registration(action);
        try {
            OAuth2AuthorizationResponse response = OAuth2AuthorizationResponse.success(code)
                    .state(returnedState).redirectUri(registration.getRedirectUri()).build();
            var authenticated = (OAuth2LoginAuthenticationToken) provider.authenticate(
                    new OAuth2LoginAuthenticationToken(registration,
                            new OAuth2AuthorizationExchange(authorization, response)));
            if (authenticated == null || !(authenticated.getPrincipal() instanceof OidcUser user)) {
                throw ApiFailureException.of(ApiFailureException.Kind.INVALID_CREDENTIALS);
            }
            return validatedClaims(user, registration);
        } catch (ApiFailureException rejected) {
            throw rejected;
        } catch (OAuth2AuthenticationException rejected) {
            throw ApiFailureException.of(transportFailure(rejected)
                    ? ApiFailureException.Kind.SERVICE_UNAVAILABLE
                    : ApiFailureException.Kind.INVALID_CREDENTIALS);
        } catch (RestClientException unavailable) {
            throw ApiFailureException.of(ApiFailureException.Kind.SERVICE_UNAVAILABLE);
        } catch (RuntimeException unavailable) {
            // Never let provider exception messages or token-response bodies reach Problem Details.
            throw ApiFailureException.of(ApiFailureException.Kind.SERVICE_UNAVAILABLE);
        }
    }

    private boolean transportFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof RestClientException) return true;
        }
        return false;
    }

    ValidatedPrincipal validatedClaims(OidcUser user, ClientRegistration registration) {
        var idToken = user.getIdToken();
        String issuer = idToken.getIssuer() == null ? null : idToken.getIssuer().toString();
        String subject = idToken.getSubject();
        String authorizedParty = idToken.getClaimAsString("azp");
        if (!properties.issuer().equals(issuer) || subject == null || subject.isBlank()
                || subject.length() > 255 || idToken.getAudience() == null
                || !idToken.getAudience().contains(registration.getClientId())
                || (idToken.getAudience().size() > 1 && authorizedParty == null)
                || (authorizedParty != null && !registration.getClientId().equals(authorizedParty))
                || idToken.getExpiresAt() == null
                || !idToken.getExpiresAt().isAfter(Instant.now())) {
            throw ApiFailureException.of(ApiFailureException.Kind.INVALID_CREDENTIALS);
        }
        Object hd = idToken.getClaim("hd");
        return new ValidatedPrincipal(issuer, subject, idToken.getClaimAsString("email"),
                Boolean.TRUE.equals(idToken.getClaimAsBoolean("email_verified")),
                hd instanceof String domain ? domain : null, trustedAuthTime(idToken.getClaim("auth_time")));
    }

    private Instant trustedAuthTime(Object claim) {
        try {
            if (claim instanceof Instant instant) return instant;
            if (claim instanceof Long seconds) return Instant.ofEpochSecond(seconds);
            if (claim instanceof Integer seconds) return Instant.ofEpochSecond(seconds);
        } catch (DateTimeException invalid) {
            return null;
        }
        return null;
    }

    private ClientRegistration registration(Action action) {
        if (!properties.enabled()) {
            throw ApiFailureException.of(ApiFailureException.Kind.SERVICE_UNAVAILABLE);
        }
        ClientRegistration cached = action == Action.LOGIN ? login : recent;
        if (cached != null) return cached;
        synchronized (this) {
            cached = action == Action.LOGIN ? login : recent;
            if (cached != null) return cached;
            try {
                var builder = ClientRegistrations.fromOidcIssuerLocation(properties.issuer());
                cached = builder.registrationId(action == Action.LOGIN ? "google-login" : "google-recent")
                        .clientId(properties.clientId())
                        .clientSecret(properties.clientSecret())
                        .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                        .scope("openid", "email")
                        .clientSettings(ClientRegistration.ClientSettings.builder()
                                .requireProofKey(true).build())
                        .redirectUri(action == Action.LOGIN ? properties.loginRedirectUri()
                                : properties.recentRedirectUri())
                        .build();
                if (!properties.issuer().equals(cached.getProviderDetails().getIssuerUri())
                        || !https(cached.getProviderDetails().getAuthorizationUri())
                        || !https(cached.getProviderDetails().getTokenUri())
                        || !https(cached.getProviderDetails().getJwkSetUri())) {
                    throw ApiFailureException.of(ApiFailureException.Kind.SERVICE_UNAVAILABLE);
                }
            } catch (RuntimeException unavailable) {
                throw ApiFailureException.of(ApiFailureException.Kind.SERVICE_UNAVAILABLE);
            }
            if (action == Action.LOGIN) login = cached; else recent = cached;
            return cached;
        }
    }

    private boolean https(String value) {
        try {
            URI uri = URI.create(value);
            return "https".equals(uri.getScheme()) && uri.getHost() != null
                    && uri.getUserInfo() == null && uri.getRawFragment() == null;
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private String randomValue() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String sha256(String value) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest
                    .getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }
}
