CREATE TABLE notes.attachment (
    attachment_id uuid PRIMARY KEY,
    note_id uuid NOT NULL,
    owner_user_id uuid NOT NULL,
    media_kind text NOT NULL CHECK (media_kind IN ('image', 'audio', 'video', 'pdf')),
    object_reference text NOT NULL UNIQUE CHECK (object_reference ~ '^private-attachment/[0-9a-f]{64}$'),
    display_filename text NOT NULL CHECK (char_length(display_filename) BETWEEN 1 AND 255
        AND display_filename !~ '[[:cntrl:]]'),
    media_type text NOT NULL,
    size_bytes bigint NOT NULL CHECK (size_bytes BETWEEN 1 AND 26214400),
    width integer,
    height integer,
    duration_seconds double precision,
    page_count integer,
    storage_state text NOT NULL CHECK (storage_state IN ('pending', 'stored', 'failed')),
    validation_state text NOT NULL CHECK (validation_state IN ('pending', 'accepted', 'quarantined', 'rejected')),
    cleanup_state text NOT NULL CHECK (cleanup_state IN ('retained', 'pending', 'deleted')),
    revision bigint NOT NULL CHECK (revision > 0),
    processing_generation bigint NOT NULL CHECK (processing_generation > 0),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL CHECK (updated_at >= created_at),
    removed_at timestamptz,
    cleaned_at timestamptz,
    CONSTRAINT ux_attachment_owner UNIQUE (attachment_id, note_id, owner_user_id),
    CONSTRAINT fk_attachment_note_owner FOREIGN KEY (note_id, owner_user_id)
        REFERENCES notes.note(note_id, owner_user_id) ON DELETE RESTRICT,
    CONSTRAINT ck_attachment_metadata CHECK (
        (media_kind = 'image' AND media_type IN ('image/png', 'image/jpeg') AND size_bytes <= 5242880
            AND width BETWEEN 1 AND 4096 AND height BETWEEN 1 AND 4096
            AND width::bigint * height <= 8294400 AND duration_seconds IS NULL AND page_count IS NULL)
        OR (media_kind = 'audio' AND media_type = 'audio/wav' AND size_bytes <= 10485760
            AND width IS NULL AND height IS NULL AND duration_seconds > 0 AND duration_seconds <= 300
            AND page_count IS NULL)
        OR (media_kind = 'video' AND media_type = 'video/mp4'
            AND width BETWEEN 1 AND 4096 AND height BETWEEN 1 AND 4096
            AND width::bigint * height <= 8294400 AND duration_seconds > 0 AND duration_seconds <= 300
            AND page_count IS NULL)
        OR (media_kind = 'pdf' AND media_type = 'application/pdf' AND size_bytes <= 10485760
            AND width IS NULL AND height IS NULL AND duration_seconds IS NULL AND page_count BETWEEN 1 AND 100)
    ),
    CONSTRAINT ck_attachment_required_metadata CHECK (
        (media_kind IN ('image', 'video') AND width IS NOT NULL AND height IS NOT NULL
            OR media_kind IN ('audio', 'pdf'))
        AND (media_kind IN ('audio', 'video') AND duration_seconds IS NOT NULL OR media_kind IN ('image', 'pdf'))
        AND (media_kind = 'pdf' AND page_count IS NOT NULL OR media_kind <> 'pdf')
    ),
    CONSTRAINT ck_attachment_cleanup CHECK (
        (cleanup_state = 'retained' AND removed_at IS NULL AND cleaned_at IS NULL)
        OR (cleanup_state = 'pending' AND removed_at >= created_at AND removed_at IS NOT NULL AND cleaned_at IS NULL)
        OR (cleanup_state = 'deleted' AND removed_at >= created_at AND removed_at IS NOT NULL
            AND cleaned_at >= removed_at AND cleaned_at IS NOT NULL)
    ),
    CONSTRAINT ck_attachment_accepted_storage CHECK (validation_state <> 'accepted' OR storage_state = 'stored')
);

CREATE INDEX ix_attachment_owner_state ON notes.attachment(owner_user_id, cleanup_state, validation_state, storage_state);
CREATE INDEX ix_attachment_note ON notes.attachment(note_id, owner_user_id, created_at, attachment_id);

CREATE FUNCTION notes.reject_attachment_identity_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.attachment_id IS DISTINCT FROM OLD.attachment_id OR NEW.note_id IS DISTINCT FROM OLD.note_id
       OR NEW.owner_user_id IS DISTINCT FROM OLD.owner_user_id OR NEW.object_reference IS DISTINCT FROM OLD.object_reference
       OR NEW.display_filename IS DISTINCT FROM OLD.display_filename OR NEW.media_kind IS DISTINCT FROM OLD.media_kind
       OR NEW.media_type IS DISTINCT FROM OLD.media_type OR NEW.size_bytes IS DISTINCT FROM OLD.size_bytes
       OR NEW.width IS DISTINCT FROM OLD.width OR NEW.height IS DISTINCT FROM OLD.height
       OR NEW.duration_seconds IS DISTINCT FROM OLD.duration_seconds OR NEW.page_count IS DISTINCT FROM OLD.page_count
       OR NEW.created_at IS DISTINCT FROM OLD.created_at OR NEW.revision < OLD.revision
       OR NEW.updated_at < OLD.updated_at
       OR (NEW.updated_at IS DISTINCT FROM OLD.updated_at AND NEW.revision <= OLD.revision)
       OR NEW.processing_generation < OLD.processing_generation
       OR ((NEW.storage_state, NEW.validation_state, NEW.cleanup_state)
            IS DISTINCT FROM (OLD.storage_state, OLD.validation_state, OLD.cleanup_state) AND NEW.revision <= OLD.revision)
       OR (OLD.cleanup_state = 'deleted' AND NEW.cleanup_state <> 'deleted')
       OR (OLD.removed_at IS NOT NULL AND NEW.removed_at IS DISTINCT FROM OLD.removed_at)
       OR (OLD.cleaned_at IS NOT NULL AND NEW.cleaned_at IS DISTINCT FROM OLD.cleaned_at) THEN
        RAISE EXCEPTION 'Attachment identity and validated metadata are immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_attachment_identity_immutable BEFORE UPDATE ON notes.attachment
    FOR EACH ROW EXECUTE FUNCTION notes.reject_attachment_identity_change();
