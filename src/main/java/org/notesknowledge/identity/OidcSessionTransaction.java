package org.notesknowledge.identity;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

/** One replaceable, session-bound protocol transaction; not an application credential. */
record OidcSessionTransaction(OidcProtocolPort.Action action,
        OAuth2AuthorizationRequest authorization, String sessionId, UUID userId,
        Instant issuedAt, Instant expiresAt,String intendedDestination) implements Serializable {
    @Serial private static final long serialVersionUID = 2L;
    OidcSessionTransaction(OidcProtocolPort.Action action,OAuth2AuthorizationRequest authorization,String sessionId,
            UUID userId,Instant issuedAt,Instant expiresAt) {
        this(action,authorization,sessionId,userId,issuedAt,expiresAt,null);
    }
    @Override public String toString(){return "OidcSessionTransaction[REDACTED]";}
    static final String ATTRIBUTE = "IDENTITY_OIDC_TRANSACTION";
}
