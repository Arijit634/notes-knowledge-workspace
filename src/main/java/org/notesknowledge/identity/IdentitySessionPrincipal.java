package org.notesknowledge.identity;

import java.io.Serial;
import java.io.Serializable;
import java.security.Principal;
import java.util.UUID;

/** Only the immutable UserId is persisted in the server-side session. */
public record IdentitySessionPrincipal(UUID userId) implements Principal, Serializable {
    @Serial private static final long serialVersionUID = 1L;
    @Override public String getName() { return userId.toString(); }
    @Override public String toString() { return "IdentitySessionPrincipal[REDACTED]"; }
}
