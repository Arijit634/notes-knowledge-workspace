CREATE SCHEMA IF NOT EXISTS profile;

CREATE TABLE profile.profile (
    profile_id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES identity.account(user_id) ON DELETE RESTRICT,
    display_name text NOT NULL,
    biography text NOT NULL,
    public_handle_original text,
    public_handle_normalized text COLLATE "C",
    updated_at timestamptz NOT NULL,
    CONSTRAINT ux_profile_user UNIQUE (user_id),
    CONSTRAINT ck_profile_display_name CHECK (char_length(display_name) <= 100),
    CONSTRAINT ck_profile_biography CHECK (char_length(biography) <= 500),
    CONSTRAINT ck_profile_handle CHECK (
        (public_handle_original IS NULL AND public_handle_normalized IS NULL)
        OR (public_handle_original IS NOT NULL AND public_handle_normalized IS NOT NULL
            AND public_handle_original ~ '^[A-Za-z][A-Za-z0-9_]{2,29}$'
            AND public_handle_normalized = lower(public_handle_original COLLATE "C")))
);

CREATE UNIQUE INDEX ux_profile_normalized_handle
    ON profile.profile (public_handle_normalized) WHERE public_handle_normalized IS NOT NULL;

-- An avatar FK is added only when its owner relation exists in the avatar package.
CREATE FUNCTION profile.reject_profile_identity_change() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.profile_id IS DISTINCT FROM OLD.profile_id
       OR NEW.user_id IS DISTINCT FROM OLD.user_id THEN
        RAISE EXCEPTION 'Profile identity and owner are immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_profile_identity_immutable
    BEFORE UPDATE ON profile.profile
    FOR EACH ROW EXECUTE FUNCTION profile.reject_profile_identity_change();
