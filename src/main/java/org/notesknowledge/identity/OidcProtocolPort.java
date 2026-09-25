package org.notesknowledge.identity;

import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

/** The only provider-facing seam; validated identity claims never carry provider tokens onward. */
interface OidcProtocolPort {
    enum Action { LOGIN, RECENT_AUTH }

    record ValidatedPrincipal(String issuer, String subject, String email,
            boolean emailVerified) { }

    OAuth2AuthorizationRequest begin(Action action);

    ValidatedPrincipal verify(Action action, OAuth2AuthorizationRequest authorization,
            String code, String returnedState);
}
