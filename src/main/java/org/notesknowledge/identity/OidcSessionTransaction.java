package org.notesknowledge.identity;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

/** One replaceable, session-bound protocol transaction; not an application credential. */
record OidcSessionTransaction(OidcProtocolPort.Action action,
        OAuth2AuthorizationRequest authorization, String sessionId, UUID userId,
        Instant issuedAt, Instant expiresAt) implements Serializable {
    @Serial private static final long serialVersionUID = 1L;
    static final String ATTRIBUTE = "IDENTITY_OIDC_TRANSACTION";
}
