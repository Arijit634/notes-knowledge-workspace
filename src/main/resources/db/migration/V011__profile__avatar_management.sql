CREATE TABLE profile.avatar_asset (
    avatar_asset_id uuid PRIMARY KEY,
    profile_id uuid NOT NULL REFERENCES profile.profile(profile_id) ON DELETE RESTRICT,
    object_reference text NOT NULL UNIQUE,
    state text NOT NULL,
    media_type text NOT NULL CHECK (media_type IN ('image/png', 'image/jpeg')),
    byte_size bigint NOT NULL CHECK (byte_size BETWEEN 1 AND 5242880),
    width integer NOT NULL CHECK (width BETWEEN 1 AND 4096),
    height integer NOT NULL CHECK (height BETWEEN 1 AND 4096),
    display_filename text NOT NULL CHECK (char_length(display_filename) <= 255
        AND display_filename !~ '[[:cntrl:]]'),
    created_at timestamptz NOT NULL,
    removed_at timestamptz,
    cleaned_at timestamptz,
    CONSTRAINT ux_avatar_owner UNIQUE (profile_id, avatar_asset_id),
    CONSTRAINT ck_avatar_reference CHECK (object_reference ~ '^private-avatar/[0-9a-f]{64}$'),
    CONSTRAINT ck_avatar_pixels CHECK (width::bigint * height <= 4194304),
    CONSTRAINT ck_avatar_lifecycle CHECK (
        (state = 'validated' AND removed_at IS NULL AND cleaned_at IS NULL)
        OR (state = 'removed' AND removed_at IS NOT NULL AND removed_at >= created_at
            AND (cleaned_at IS NULL OR cleaned_at >= removed_at)))
);

ALTER TABLE profile.profile ADD COLUMN selected_avatar_id uuid;
ALTER TABLE profile.profile ADD CONSTRAINT fk_profile_selected_avatar_owner
    FOREIGN KEY (profile_id, selected_avatar_id)
    REFERENCES profile.avatar_asset(profile_id, avatar_asset_id) ON DELETE RESTRICT;

CREATE INDEX ix_avatar_cleanup ON profile.avatar_asset(removed_at, avatar_asset_id)
    WHERE state = 'removed' AND cleaned_at IS NULL;

-- Deferred checks allow a short swap to select the new asset and retire the old
-- one in either SQL order, but no commit may expose a removed selected asset.
CREATE FUNCTION profile.check_selected_avatar() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM profile.profile p JOIN profile.avatar_asset a
          ON a.avatar_asset_id = p.selected_avatar_id AND a.profile_id = p.profile_id
        WHERE p.profile_id = NEW.profile_id AND a.state <> 'validated'
    ) THEN
        RAISE EXCEPTION 'Selected avatar must be validated' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER trg_profile_selected_avatar_validated
    AFTER INSERT OR UPDATE ON profile.profile DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION profile.check_selected_avatar();
CREATE CONSTRAINT TRIGGER trg_avatar_selected_validated
    AFTER UPDATE ON profile.avatar_asset DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION profile.check_selected_avatar();

CREATE FUNCTION profile.reject_avatar_identity_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.avatar_asset_id IS DISTINCT FROM OLD.avatar_asset_id
       OR NEW.profile_id IS DISTINCT FROM OLD.profile_id
       OR NEW.object_reference IS DISTINCT FROM OLD.object_reference
       OR NEW.media_type IS DISTINCT FROM OLD.media_type
       OR NEW.byte_size IS DISTINCT FROM OLD.byte_size
       OR NEW.width IS DISTINCT FROM OLD.width OR NEW.height IS DISTINCT FROM OLD.height
       OR NEW.display_filename IS DISTINCT FROM OLD.display_filename
       OR NEW.created_at IS DISTINCT FROM OLD.created_at
       OR (OLD.state = 'removed' AND NEW.state <> 'removed')
       OR (OLD.removed_at IS NOT NULL AND NEW.removed_at IS DISTINCT FROM OLD.removed_at)
       OR (OLD.cleaned_at IS NOT NULL AND NEW.cleaned_at IS DISTINCT FROM OLD.cleaned_at) THEN
        RAISE EXCEPTION 'Avatar identity and validated metadata are immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_avatar_identity_immutable BEFORE UPDATE ON profile.avatar_asset
    FOR EACH ROW EXECUTE FUNCTION profile.reject_avatar_identity_change();
