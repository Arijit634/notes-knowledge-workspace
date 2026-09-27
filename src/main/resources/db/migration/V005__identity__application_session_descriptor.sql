CREATE TABLE identity.application_session_descriptor (
    session_primary_id CHAR(36) PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES identity.account(user_id) ON DELETE RESTRICT,
    client_label varchar(40) NOT NULL,
    created_at timestamptz NOT NULL,
    last_seen_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    CONSTRAINT ck_application_session_descriptor_label CHECK
        (length(client_label) BETWEEN 1 AND 40 AND client_label !~ '[[:cntrl:]]'),
    CONSTRAINT ck_application_session_descriptor_seen CHECK
        (last_seen_at >= created_at),
    CONSTRAINT ck_application_session_descriptor_expiry CHECK
        (expires_at >= created_at),
    CONSTRAINT ck_application_session_descriptor_revoked CHECK
        (revoked_at IS NULL OR revoked_at >= created_at)
);

CREATE INDEX ix_application_session_descriptor_active_owner
    ON identity.application_session_descriptor(user_id, last_seen_at DESC, session_primary_id)
    WHERE revoked_at IS NULL;

CREATE INDEX ix_application_session_descriptor_owner_lifecycle
    ON identity.application_session_descriptor(user_id, revoked_at, expires_at);
