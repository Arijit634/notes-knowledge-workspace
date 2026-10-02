CREATE TABLE notes.note_version (
    note_version_id uuid PRIMARY KEY,
    note_id uuid NOT NULL,
    owner_user_id uuid NOT NULL,
    title text NOT NULL,
    markdown text NOT NULL,
    source_revision bigint NOT NULL,
    checkpoint_kind text NOT NULL,
    created_at timestamptz(3) NOT NULL,
    CONSTRAINT fk_note_version_owner FOREIGN KEY (note_id, owner_user_id)
        REFERENCES notes.note (note_id, owner_user_id) ON DELETE RESTRICT,
    CONSTRAINT ux_note_version_scope UNIQUE (note_version_id, note_id, owner_user_id),
    CONSTRAINT ux_note_version_lineage UNIQUE (note_id, source_revision, note_version_id),
    CONSTRAINT ux_note_version_source_revision UNIQUE (note_id, source_revision),
    CONSTRAINT ck_note_version_revision CHECK (source_revision > 0),
    CONSTRAINT ck_note_version_kind CHECK (checkpoint_kind IN ('policy', 'pre_restore', 'publication')),
    CONSTRAINT ck_note_version_content CHECK
        (char_length(title) BETWEEN 1 AND 500 AND btrim(title) <> '' AND char_length(markdown) <= 1000000)
);

CREATE INDEX ix_note_version_owner_created ON notes.note_version
    (owner_user_id, note_id, created_at DESC, note_version_id DESC);

CREATE TABLE notes.note_version_hold (
    note_version_id uuid NOT NULL,
    note_id uuid NOT NULL,
    owner_user_id uuid NOT NULL,
    holder_kind text NOT NULL,
    holder_id uuid NOT NULL,
    created_at timestamptz(3) NOT NULL,
    PRIMARY KEY (note_version_id, holder_kind, holder_id),
    CONSTRAINT fk_note_version_hold_scope FOREIGN KEY (note_version_id, note_id, owner_user_id)
        REFERENCES notes.note_version (note_version_id, note_id, owner_user_id) ON DELETE RESTRICT,
    CONSTRAINT ck_note_version_holder CHECK (holder_kind = 'publication')
);

CREATE FUNCTION notes.reject_checkpoint_update() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Retained checkpoints and holds are immutable' USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER trg_note_version_immutable BEFORE UPDATE ON notes.note_version
    FOR EACH ROW EXECUTE FUNCTION notes.reject_checkpoint_update();
CREATE TRIGGER trg_note_version_hold_immutable BEFORE UPDATE ON notes.note_version_hold
    FOR EACH ROW EXECUTE FUNCTION notes.reject_checkpoint_update();
