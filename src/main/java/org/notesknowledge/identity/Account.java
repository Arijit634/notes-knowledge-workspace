package org.notesknowledge.identity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Identity-owned Account root; conditional security transitions use JDBC predicates. */
@Entity
@Table(name = "account", schema = "identity")
class Account {
    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "canonical_email", nullable = false)
    private String canonicalEmail;

    @Column(name = "display_email", nullable = false)
    private String displayEmail;

    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    @Column(name = "password_verifier")
    private String passwordVerifier;

    @Column(name = "account_state", nullable = false)
    private String accountState;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "last_authenticated_at")
    private Instant lastAuthenticatedAt;

    protected Account() { }

    Account(UUID userId, String canonicalEmail, String displayEmail,
            String passwordVerifier, Instant now) {
        this.userId = userId;
        this.canonicalEmail = canonicalEmail;
        this.displayEmail = displayEmail;
        this.passwordVerifier = passwordVerifier;
        this.accountState = "pending_verification";
        this.createdAt = now;
        this.updatedAt = now;
    }

    UUID userId() { return userId; }
    String canonicalEmail() { return canonicalEmail; }
    String displayEmail() { return displayEmail; }
    String passwordVerifier() { return passwordVerifier; }
    String accountState() { return accountState; }
    Instant emailVerifiedAt() { return emailVerifiedAt; }
}
