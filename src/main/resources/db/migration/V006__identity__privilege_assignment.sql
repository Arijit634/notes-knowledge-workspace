CREATE TABLE identity.privilege_assignment (
    privilege_assignment_id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES identity.account(user_id) ON DELETE RESTRICT,
    capability_code text NOT NULL,
    scope_kind text NOT NULL,
    scope_id uuid,
    assigned_by_user_id uuid NOT NULL REFERENCES identity.account(user_id) ON DELETE RESTRICT,
    granted_at timestamptz NOT NULL,
    revoked_at timestamptz,
    CONSTRAINT ck_privilege_assignment_capability CHECK
        (capability_code IN ('moderation.review', 'moderation.enforce')),
    CONSTRAINT ck_privilege_assignment_scope CHECK
        (scope_kind = 'public_report'),
    CONSTRAINT ck_privilege_assignment_not_self_assigned CHECK
        (assigned_by_user_id <> user_id),
    CONSTRAINT ck_privilege_assignment_revocation CHECK
        (revoked_at IS NULL OR revoked_at >= granted_at)
);

-- NULL scope_id means all public reports; a UUID limits the assignment to one report.
-- Neither shape conveys access to a private source Note or any Account secret.
CREATE UNIQUE INDEX ux_privilege_assignment_active_scope
    ON identity.privilege_assignment
        (user_id, capability_code, scope_kind, scope_id) NULLS NOT DISTINCT
    WHERE revoked_at IS NULL;

CREATE INDEX ix_privilege_assignment_user_history
    ON identity.privilege_assignment (user_id, granted_at DESC);
