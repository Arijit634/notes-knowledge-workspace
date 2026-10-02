package org.notesknowledge.identity;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.UUID;

import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Identity interprets current persisted session proof; consumers receive no session facts. */
@Service
@IdentityCoreEnabled
public class RecentAuthenticationApi {
    private final IdentityPersistence identity;
    private final SpringSessionAuthorityAdapter sessions;
    private final MfaProperties policy;
    private final Clock clock;

    RecentAuthenticationApi(IdentityPersistence identity, SpringSessionAuthorityAdapter sessions,
            MfaProperties policy, Clock clock) {
        this.identity = identity;
        this.sessions = sessions;
        this.policy = policy;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void requireRecent(UUID userId, HttpServletRequest request) {
        var session = request.getSession(false);
        if (!userId.equals(IdentitySessionState.principal("ROLE_USER")) || session == null
                || !identity.lockActiveAccount(userId)) throw unauthenticated();
        var current = sessions.lockCurrent(userId, session.getId());
        if (current == null) throw unauthenticated();
        IdentitySessionState.requireRecent((Object) current.persisted()
                .getAttribute(IdentitySessionState.RECENT_ATTRIBUTE), userId, clock.instant(), policy);
    }

    private static ApiFailureException unauthenticated() {
        return ApiFailureException.of(ApiFailureException.Kind.INVALID_CREDENTIALS);
    }
}
