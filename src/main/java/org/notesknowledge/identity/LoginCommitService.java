package org.notesknowledge.identity;

import java.time.Instant;
import java.util.UUID;

import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@IdentityCoreEnabled
class LoginCommitService {
    private final IdentityPersistence identity;

    LoginCommitService(IdentityPersistence identity) { this.identity = identity; }

    @Transactional
    void commit(UUID userId, String priorVerifier, String replacement, Instant now) {
        if (identity.authenticated(userId, priorVerifier, replacement, now) != 1) {
            throw ApiFailureException.of(ApiFailureException.Kind.INVALID_CREDENTIALS);
        }
        identity.audit(userId, "password_login", "success", now);
    }
}
