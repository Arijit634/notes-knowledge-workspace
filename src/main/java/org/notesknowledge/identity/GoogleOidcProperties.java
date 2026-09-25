package org.notesknowledge.identity;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Google client secrets and redirect origins are deployment-supplied, never source-controlled. */
@ConfigurationProperties(prefix = "identity.oidc.google")
record GoogleOidcProperties(boolean enabled, String clientId, String clientSecret,
        String issuer, String loginRedirectUri, String recentRedirectUri,
        Duration transactionLifetime) {
    GoogleOidcProperties {
        if (transactionLifetime == null || transactionLifetime.isNegative()
                || transactionLifetime.isZero() || transactionLifetime.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException("Invalid OIDC transaction lifetime");
        }
        if (enabled) {
            if (clientId == null || clientId.isBlank() || clientSecret == null
                    || clientSecret.isBlank() || !"https://accounts.google.com".equals(issuer)) {
                throw new IllegalArgumentException("Invalid Google OIDC client configuration");
            }
            requireRedirect(loginRedirectUri, "/api/auth/oidc/google/callback");
            requireRedirect(recentRedirectUri, "/api/auth/reauth/oidc/google/callback");
        }
    }

    private static void requireRedirect(String value, String path) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Invalid Google OIDC redirect configuration");
        }
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null
                || !path.equals(uri.getRawPath()) || uri.getRawQuery() != null
                || uri.getRawFragment() != null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("Invalid Google OIDC redirect configuration");
        }
    }
}
