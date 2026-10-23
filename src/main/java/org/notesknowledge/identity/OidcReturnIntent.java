package org.notesknowledge.identity;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/** Single-use navigation carried only inside the current persisted MFA challenge. */
record OidcReturnIntent(OidcProtocolPort.Action action,UUID userId,String sessionId,String challengeId,
        Instant expiresAt,String target) implements Serializable {
    @Serial private static final long serialVersionUID=1L;
    String resolve(UUID user,String session,String challenge,Instant now) {
        return action==OidcProtocolPort.Action.LOGIN&&user.equals(userId)&&session.equals(sessionId)
            &&challenge.equals(challengeId)&&expiresAt!=null&&expiresAt.isAfter(now)
            ?OidcIntendedDestination.safe(target):"/notes";
    }
    @Override public String toString(){return "OidcReturnIntent[REDACTED]";}
}
