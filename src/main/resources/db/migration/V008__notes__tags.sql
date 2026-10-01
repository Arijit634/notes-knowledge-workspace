CREATE TABLE notes.note_tag (
    note_id uuid NOT NULL,
    owner_user_id uuid NOT NULL,
    normalized_label text NOT NULL,
    display_label text NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (note_id, normalized_label),
    CONSTRAINT fk_note_tag_owner FOREIGN KEY (note_id, owner_user_id)
        REFERENCES notes.note (note_id, owner_user_id) ON DELETE RESTRICT,
    CONSTRAINT ck_note_tag_label CHECK
        (char_length(normalized_label) BETWEEN 1 AND 100
         AND char_length(display_label) BETWEEN 1 AND 100)
);

CREATE INDEX ix_note_tag_owner_label ON notes.note_tag
    (owner_user_id, normalized_label, note_id);
