CREATE TABLE identity.external_identity_link (
    external_identity_link_id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES identity.account(user_id) ON DELETE RESTRICT,
    issuer text NOT NULL,
    subject text NOT NULL,
    linked_at timestamptz NOT NULL,
    revoked_at timestamptz,
    CONSTRAINT ck_external_identity_link_issuer CHECK
        (length(issuer) BETWEEN 1 AND 255 AND issuer !~ '[[:cntrl:]]'),
    CONSTRAINT ck_external_identity_link_subject CHECK
        (length(subject) BETWEEN 1 AND 255 AND subject !~ '[[:cntrl:]]'),
    CONSTRAINT ck_external_identity_link_revoked CHECK
        (revoked_at IS NULL OR revoked_at >= linked_at),
    CONSTRAINT ux_external_identity_link_principal UNIQUE (issuer, subject)
);
CREATE INDEX ix_external_identity_link_active_user
    ON identity.external_identity_link(user_id) WHERE revoked_at IS NULL;
