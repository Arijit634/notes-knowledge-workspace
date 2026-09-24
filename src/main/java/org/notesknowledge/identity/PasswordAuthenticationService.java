package org.notesknowledge.identity;

import java.time.Clock;
import java.util.UUID;

import org.notesknowledge.websupport.ApiFailureException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@IdentityCoreEnabled
final class PasswordAuthenticationService {
    private final IdentityPersistence identity;
    private final PasswordEncoder passwords;
    private final Clock clock;
    private final LoginCommitService commit;
    private String dummyVerifier;

    PasswordAuthenticationService(IdentityPersistence identity, PasswordEncoder passwords,
            Clock clock, LoginCommitService commit) {
        this.identity = identity;
        this.passwords = passwords;
        this.clock = clock;
        this.commit = commit;
    }

    UUID authenticate(String email, String password) {
        String canonical = IdentityInput.canonicalEmail(email);
        if (password == null || password.length() > 256) {
            throw ApiFailureException.of(ApiFailureException.Kind.INVALID_INPUT);
        }
        var account = identity.loginAccount(canonical);
        if (account.isEmpty() || account.get().verifier() == null) {
            // Keep a bounded Argon2 cost for unknown account candidates.
            passwords.matches(password, dummyVerifier);
            deny();
        }
        var candidate = account.get();
        boolean matched;
        try {
            matched = passwords.matches(password, candidate.verifier());
        } catch (RuntimeException exception) {
            matched = false;
        }
        if (!matched || !"active".equals(candidate.state()) || candidate.verifiedAt() == null) {
            deny();
        }
        String upgraded = passwords.upgradeEncoding(candidate.verifier())
                ? passwords.encode(password) : null;
        commit.commit(candidate.id(), candidate.verifier(), upgraded, clock.instant());
        return candidate.id();
    }

    void reauthenticate(UUID userId, String password) {
        if (password == null || password.length() > 256) {
            throw ApiFailureException.of(ApiFailureException.Kind.INVALID_INPUT);
        }
        String verifier = identity.currentPasswordVerifier(userId).orElse(null);
        boolean matched = false;
        if (verifier != null) {
            try { matched = passwords.matches(password, verifier); }
            catch (RuntimeException ignored) { matched = false; }
        }
        if (!matched) {
            identity.audit(userId, "password_reauth", "denied", clock.instant());
            throw ApiFailureException.of(ApiFailureException.Kind.INVALID_CREDENTIALS);
        }
        identity.audit(userId, "password_reauth", "success", clock.instant());
    }

    private void deny() {
        identity.audit(null, "password_login", "denied", clock.instant());
        throw ApiFailureException.of(ApiFailureException.Kind.INVALID_CREDENTIALS);
    }

    @jakarta.annotation.PostConstruct
    void initializeDummy() {
        byte[] dummy = new byte[32];
        new java.security.SecureRandom().nextBytes(dummy);
        dummyVerifier = passwords.encode(java.util.Base64.getEncoder().encodeToString(dummy));
    }
}
