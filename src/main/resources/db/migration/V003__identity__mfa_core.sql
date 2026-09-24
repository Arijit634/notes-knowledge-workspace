CREATE TABLE identity.mfa_configuration (
    user_id uuid PRIMARY KEY REFERENCES identity.account(user_id) ON DELETE RESTRICT,
    state text NOT NULL,
    seed_ciphertext bytea NOT NULL,
    seed_nonce bytea NOT NULL,
    seed_tag bytea NOT NULL,
    key_version text NOT NULL,
    last_accepted_timestep bigint,
    current_recovery_generation bigint NOT NULL DEFAULT 0,
    enrolled_at timestamptz NOT NULL,
    activated_at timestamptz,
    CONSTRAINT ck_mfa_state CHECK (state IN ('enrollment_pending', 'active')),
    CONSTRAINT ck_mfa_seed_shape CHECK (octet_length(seed_ciphertext) = 20
        AND octet_length(seed_nonce) = 12 AND octet_length(seed_tag) = 16),
    CONSTRAINT ck_mfa_key_version CHECK (length(key_version) BETWEEN 1 AND 32
        AND key_version ~ '^[A-Za-z0-9_-]+$'),
    CONSTRAINT ck_mfa_timestep CHECK (last_accepted_timestep IS NULL
        OR last_accepted_timestep >= 0),
    CONSTRAINT ck_mfa_generation CHECK (current_recovery_generation >= 0),
    CONSTRAINT ck_mfa_active_shape CHECK
        ((state = 'enrollment_pending' AND activated_at IS NULL
            AND last_accepted_timestep IS NULL AND current_recovery_generation = 0)
         OR (state = 'active' AND activated_at IS NOT NULL
            AND last_accepted_timestep IS NOT NULL AND current_recovery_generation >= 1))
);

CREATE TABLE identity.mfa_recovery_code (
    recovery_code_id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES identity.mfa_configuration(user_id) ON DELETE RESTRICT,
    set_generation bigint NOT NULL,
    verifier_digest bytea NOT NULL,
    issued_at timestamptz NOT NULL,
    consumed_at timestamptz,
    revoked_at timestamptz,
    CONSTRAINT ck_mfa_recovery_generation CHECK (set_generation >= 1),
    CONSTRAINT ck_mfa_recovery_digest CHECK (octet_length(verifier_digest) = 32),
    CONSTRAINT ck_mfa_recovery_end_state CHECK
        (num_nonnulls(consumed_at, revoked_at) <= 1)
);
CREATE UNIQUE INDEX ux_mfa_recovery_digest_set
    ON identity.mfa_recovery_code(user_id, set_generation, verifier_digest);
CREATE INDEX ix_mfa_recovery_current
    ON identity.mfa_recovery_code(user_id, set_generation)
    WHERE consumed_at IS NULL AND revoked_at IS NULL;
