CREATE SCHEMA IF NOT EXISTS notes;

CREATE TABLE notes.note_preferences (
    user_id uuid PRIMARY KEY REFERENCES identity.account(user_id) ON DELETE RESTRICT,
    default_ai_enabled boolean NOT NULL DEFAULT false,
    updated_at timestamptz NOT NULL
);

CREATE TABLE notes.note (
    note_id uuid PRIMARY KEY,
    owner_user_id uuid NOT NULL REFERENCES identity.account(user_id) ON DELETE RESTRICT,
    title text NOT NULL,
    markdown text NOT NULL,
    lifecycle_state text NOT NULL,
    pre_trash_state text,
    pinned boolean NOT NULL DEFAULT false,
    revision bigint NOT NULL,
    ai_enabled boolean NOT NULL,
    ai_generation bigint NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    trashed_at timestamptz,
    deleted_at timestamptz,
    CONSTRAINT ux_note_id_owner UNIQUE (note_id, owner_user_id),
    CONSTRAINT ck_note_lifecycle CHECK
        (lifecycle_state IN ('active', 'archived', 'trashed', 'logically_deleted')),
    CONSTRAINT ck_note_pre_trash CHECK
        ((lifecycle_state = 'trashed' AND pre_trash_state IN ('active', 'archived'))
         OR (lifecycle_state <> 'trashed' AND pre_trash_state IS NULL)),
    CONSTRAINT ck_note_revision CHECK (revision > 0 AND ai_generation > 0),
    CONSTRAINT ck_note_time_order CHECK (updated_at >= created_at),
    CONSTRAINT ck_note_trash_time CHECK ((lifecycle_state = 'trashed') = (trashed_at IS NOT NULL)),
    CONSTRAINT ck_note_delete_time CHECK
        ((lifecycle_state = 'logically_deleted') = (deleted_at IS NOT NULL))
);

CREATE INDEX ix_note_owner_state_updated
    ON notes.note (owner_user_id, lifecycle_state, updated_at DESC, note_id DESC);
CREATE INDEX ix_note_owner_updated
    ON notes.note (owner_user_id, updated_at DESC, note_id DESC);

CREATE FUNCTION notes.reject_note_identity_change() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.note_id IS DISTINCT FROM OLD.note_id
       OR NEW.owner_user_id IS DISTINCT FROM OLD.owner_user_id THEN
        RAISE EXCEPTION 'Note identity and owner are immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_note_identity_immutable
    BEFORE UPDATE ON notes.note
    FOR EACH ROW EXECUTE FUNCTION notes.reject_note_identity_change();
