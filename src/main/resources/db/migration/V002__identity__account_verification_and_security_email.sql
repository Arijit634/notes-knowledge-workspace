CREATE TABLE identity.account (
    user_id uuid PRIMARY KEY,
    canonical_email text NOT NULL UNIQUE,
    display_email text NOT NULL,
    email_verified_at timestamptz,
    password_verifier text,
    account_state text NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    last_authenticated_at timestamptz,
    CONSTRAINT ck_account_state CHECK (account_state IN
        ('pending_verification', 'active', 'suspended', 'deletion_requested', 'logically_deleted')),
    CONSTRAINT ck_account_password_verifier CHECK
        (password_verifier IS NULL OR password_verifier LIKE '{argon2id}%'),
    CONSTRAINT ck_account_email CHECK
        (length(canonical_email) BETWEEN 3 AND 320 AND canonical_email = lower(canonical_email)
         AND length(display_email) BETWEEN 3 AND 320),
    CONSTRAINT ck_account_verified_state CHECK
        (account_state <> 'active' OR email_verified_at IS NOT NULL)
);
CREATE INDEX ix_account_eligible_state ON identity.account (account_state, user_id)
    WHERE account_state IN ('pending_verification', 'active');

CREATE TABLE identity.identity_capability (
    capability_id uuid PRIMARY KEY,
    user_id uuid REFERENCES identity.account(user_id) ON DELETE RESTRICT,
    candidate_canonical_email text,
    purpose text NOT NULL,
    verifier_digest bytea NOT NULL,
    issued_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    superseded_at timestamptz,
    revoked_at timestamptz,
    CONSTRAINT ck_identity_capability_purpose CHECK
        (purpose IN ('email_verification', 'email_change', 'password_reset')),
    CONSTRAINT ck_identity_capability_digest CHECK (octet_length(verifier_digest) = 32),
    CONSTRAINT ck_identity_capability_expiry CHECK (expires_at > issued_at),
    CONSTRAINT ck_identity_capability_candidate CHECK
        ((purpose = 'email_change') = (candidate_canonical_email IS NOT NULL)),
    CONSTRAINT ck_identity_capability_endpoints CHECK
        (num_nonnulls(consumed_at, superseded_at, revoked_at) <= 1)
);
CREATE UNIQUE INDEX ux_identity_capability_current
    ON identity.identity_capability (user_id, purpose)
    WHERE consumed_at IS NULL AND superseded_at IS NULL AND revoked_at IS NULL;
CREATE INDEX ix_identity_capability_user ON identity.identity_capability (user_id, purpose);

CREATE TABLE identity.security_email_delivery (
    security_email_delivery_id uuid PRIMARY KEY,
    delivery_kind text NOT NULL,
    capability_id uuid REFERENCES identity.identity_capability(capability_id) ON DELETE RESTRICT,
    subject_user_id uuid REFERENCES identity.account(user_id) ON DELETE RESTRICT,
    security_event_id uuid,
    notice_kind text,
    state text NOT NULL,
    attempt_count integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz,
    lease_owner text,
    lease_token uuid,
    lease_until timestamptz,
    last_attempt_at timestamptz,
    last_failure_code text,
    sealed_token_ciphertext bytea,
    sealed_token_nonce bytea,
    sealed_token_tag bytea,
    token_key_version text,
    sealed_recipient_ciphertext bytea,
    sealed_recipient_nonce bytea,
    sealed_recipient_tag bytea,
    recipient_key_version text,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    submitted_at timestamptz,
    terminal_at timestamptz,
    CONSTRAINT ck_security_email_delivery_kind CHECK
        (delivery_kind IN ('capability_link', 'security_notice')),
    CONSTRAINT ck_security_email_delivery_kind_shape CHECK
        ((delivery_kind = 'capability_link' AND capability_id IS NOT NULL
          AND subject_user_id IS NULL AND security_event_id IS NULL AND notice_kind IS NULL)
         OR (delivery_kind = 'security_notice' AND capability_id IS NULL
          AND subject_user_id IS NOT NULL AND security_event_id IS NOT NULL AND notice_kind IS NOT NULL)),
    CONSTRAINT ck_security_email_delivery_notice_kind CHECK
        (notice_kind IS NULL OR notice_kind IN
         ('password_reset_completed', 'email_change_old_address', 'email_change_new_address',
          'mfa_disabled', 'mfa_reset', 'google_oidc_linked', 'google_oidc_unlinked')),
    CONSTRAINT ck_security_email_delivery_state CHECK
        (state IN ('queued', 'claimed', 'retry_wait', 'submitted', 'failed', 'obsolete')),
    CONSTRAINT ck_security_email_delivery_attempt CHECK (attempt_count >= 0),
    CONSTRAINT ck_security_email_delivery_failure_code CHECK
        (last_failure_code IS NULL OR (length(last_failure_code) <= 48
          AND last_failure_code ~ '^[a-z][a-z0-9_]*$')),
    CONSTRAINT ck_security_email_delivery_lease_owner CHECK
        (lease_owner IS NULL OR (length(lease_owner) BETWEEN 1 AND 64
          AND lease_owner ~ '^[A-Za-z][A-Za-z0-9_-]*$')),
    CONSTRAINT ck_security_email_delivery_key_versions CHECK
        ((token_key_version IS NULL OR (length(token_key_version) BETWEEN 1 AND 32
          AND token_key_version ~ '^[A-Za-z0-9_-]+$'))
         AND (recipient_key_version IS NULL OR (length(recipient_key_version) BETWEEN 1 AND 32
          AND recipient_key_version ~ '^[A-Za-z0-9_-]+$'))),
    CONSTRAINT ck_security_email_delivery_schedule_shape CHECK
        ((state IN ('queued', 'retry_wait') AND next_attempt_at IS NOT NULL
          AND lease_owner IS NULL AND lease_token IS NULL AND lease_until IS NULL
          AND terminal_at IS NULL AND submitted_at IS NULL)
         OR (state = 'claimed' AND next_attempt_at IS NULL
          AND lease_owner IS NOT NULL AND lease_token IS NOT NULL AND lease_until IS NOT NULL
          AND terminal_at IS NULL AND submitted_at IS NULL)
         OR (state IN ('submitted', 'failed', 'obsolete') AND next_attempt_at IS NULL
          AND lease_owner IS NULL AND lease_token IS NULL AND lease_until IS NULL
          AND terminal_at IS NOT NULL AND (state = 'submitted') = (submitted_at IS NOT NULL))),
    CONSTRAINT ck_security_email_delivery_token_envelope_shape CHECK
        ((delivery_kind = 'capability_link' AND state IN ('queued', 'claimed', 'retry_wait')
          AND sealed_token_ciphertext IS NOT NULL AND sealed_token_nonce IS NOT NULL
          AND sealed_token_tag IS NOT NULL AND token_key_version IS NOT NULL)
         OR ((delivery_kind = 'security_notice' OR state IN ('submitted', 'failed', 'obsolete'))
          AND sealed_token_ciphertext IS NULL AND sealed_token_nonce IS NULL
          AND sealed_token_tag IS NULL AND token_key_version IS NULL)),
    CONSTRAINT ck_security_email_delivery_recipient_envelope_shape CHECK
        ((delivery_kind = 'security_notice'
          AND notice_kind IN ('email_change_old_address', 'email_change_new_address')
          AND state IN ('queued', 'claimed', 'retry_wait')
          AND sealed_recipient_ciphertext IS NOT NULL AND sealed_recipient_nonce IS NOT NULL
          AND sealed_recipient_tag IS NOT NULL AND recipient_key_version IS NOT NULL)
         OR (NOT (delivery_kind = 'security_notice'
          AND notice_kind IN ('email_change_old_address', 'email_change_new_address')
          AND state IN ('queued', 'claimed', 'retry_wait'))
          AND sealed_recipient_ciphertext IS NULL AND sealed_recipient_nonce IS NULL
          AND sealed_recipient_tag IS NULL AND recipient_key_version IS NULL))
);
CREATE UNIQUE INDEX ux_security_email_delivery_capability
    ON identity.security_email_delivery(capability_id) WHERE delivery_kind = 'capability_link';
CREATE UNIQUE INDEX ux_security_email_delivery_event_notice
    ON identity.security_email_delivery(security_event_id, notice_kind) WHERE delivery_kind = 'security_notice';
CREATE INDEX ix_security_email_delivery_ready
    ON identity.security_email_delivery(next_attempt_at, created_at, security_email_delivery_id)
    WHERE state IN ('queued', 'retry_wait');
CREATE INDEX ix_security_email_delivery_reclaim
    ON identity.security_email_delivery(lease_until, created_at, security_email_delivery_id)
    WHERE state = 'claimed';
CREATE INDEX ix_security_email_delivery_subject_state
    ON identity.security_email_delivery(subject_user_id, state)
    WHERE subject_user_id IS NOT NULL;

CREATE TABLE identity.security_audit_fact (
    audit_fact_id uuid PRIMARY KEY,
    actor_user_id uuid,
    target_user_id uuid,
    event_category text NOT NULL,
    outcome_code text NOT NULL,
    reason_code text,
    correlation_id uuid,
    occurred_at timestamptz NOT NULL,
    CONSTRAINT ck_security_audit_fact_codes CHECK
        (length(event_category) BETWEEN 1 AND 48 AND event_category ~ '^[a-z][a-z0-9_]*$'
         AND length(outcome_code) BETWEEN 1 AND 48 AND outcome_code ~ '^[a-z][a-z0-9_]*$'
         AND (reason_code IS NULL OR (length(reason_code) <= 48
           AND reason_code ~ '^[a-z][a-z0-9_]*$')))
);
CREATE INDEX ix_security_audit_fact_occurred ON identity.security_audit_fact(occurred_at);
CREATE INDEX ix_security_audit_fact_actor ON identity.security_audit_fact(actor_user_id, occurred_at)
    WHERE actor_user_id IS NOT NULL;
CREATE INDEX ix_security_audit_fact_target ON identity.security_audit_fact(target_user_id, occurred_at)
    WHERE target_user_id IS NOT NULL;
