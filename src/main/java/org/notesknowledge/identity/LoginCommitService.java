package org.notesknowledge.identity;

import java.time.Instant;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@IdentityCoreEnabled
class LoginCommitService {
    private final IdentityPersistence identity;
    private final MfaChallengeService challenges;
    private final MfaSessionTransitions sessions;
    private final IdentitySessionTransitionCheckpoint checkpoint;

    LoginCommitService(IdentityPersistence identity, MfaChallengeService challenges,
            MfaSessionTransitions sessions, IdentitySessionTransitionCheckpoint checkpoint) {
        this.identity = identity;
        this.challenges = challenges;
        this.sessions = sessions;
        this.checkpoint = checkpoint;
    }

    record LoginResult(boolean mfaRequired, String challengeId) { }

    @Transactional
    LoginResult commit(UUID userId, String priorVerifier, String replacement, Instant now,
            HttpServletRequest request, HttpServletResponse response) {
        if (identity.authenticated(userId, priorVerifier, replacement, now) != 1) {
            throw ApiFailureException.of(ApiFailureException.Kind.INVALID_CREDENTIALS);
        }
        identity.audit(userId, "password_login", "success", now);
        boolean mfaRequired = challenges.active(userId);
        sessions.establish(userId, !mfaRequired, request, response);
        String challengeId = mfaRequired ? challenges.begin(userId, request) : null;
        checkpoint.afterSessionMutation(request);
        return new LoginResult(mfaRequired, challengeId);
    }
}
