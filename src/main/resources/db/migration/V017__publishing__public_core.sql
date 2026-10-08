CREATE SCHEMA IF NOT EXISTS publishing;
CREATE SCHEMA IF NOT EXISTS discovery;

ALTER TABLE profile.profile ADD CONSTRAINT ux_profile_identity_owner UNIQUE (profile_id,user_id);
CREATE TABLE profile.public_profile_projection (
    public_profile_projection_id uuid PRIMARY KEY,
    profile_id uuid NOT NULL UNIQUE,
    user_id uuid NOT NULL,
    handle text COLLATE "C" NOT NULL CHECK (handle ~ '^[a-z][a-z0-9_]{2,29}$'),
    display_name text NOT NULL CHECK (char_length(display_name) <= 100),
    biography text NOT NULL CHECK (char_length(biography) <= 500),
    projection_generation bigint NOT NULL CHECK (projection_generation > 0),
    active boolean NOT NULL,
    activated_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL CHECK (updated_at >= activated_at),
    public_avatar_id uuid UNIQUE,
    source_avatar_id uuid,
    public_avatar_object_reference text UNIQUE,
    avatar_media_type text,
    avatar_byte_size bigint,
    avatar_width integer,
    avatar_height integer,
    CONSTRAINT fk_public_profile_owner FOREIGN KEY (profile_id,user_id)
        REFERENCES profile.profile(profile_id,user_id) ON DELETE RESTRICT,
    CONSTRAINT ux_public_profile_owner UNIQUE (public_profile_projection_id,user_id),
    CONSTRAINT ck_public_avatar_shape CHECK (
        (public_avatar_id IS NULL AND source_avatar_id IS NULL AND public_avatar_object_reference IS NULL AND avatar_media_type IS NULL
            AND avatar_byte_size IS NULL AND avatar_width IS NULL AND avatar_height IS NULL)
        OR (public_avatar_id IS NOT NULL AND public_avatar_object_reference IS NOT NULL
            AND public_avatar_object_reference ~ '^public-profile-avatar/[0-9a-f]{64}$'
            AND avatar_media_type IS NOT NULL AND avatar_media_type IN ('image/png','image/jpeg')
            AND avatar_byte_size IS NOT NULL AND avatar_byte_size BETWEEN 1 AND 5242880
            AND avatar_width IS NOT NULL AND avatar_width BETWEEN 1 AND 4096
            AND avatar_height IS NOT NULL AND avatar_height BETWEEN 1 AND 4096
            AND avatar_width::bigint * avatar_height <= 4194304))
);
CREATE UNIQUE INDEX ux_public_profile_active_handle ON profile.public_profile_projection(handle) WHERE active;
CREATE INDEX ix_public_profile_user ON profile.public_profile_projection(user_id);

CREATE TABLE publishing.publication (
    publication_id uuid PRIMARY KEY,
    owner_user_id uuid NOT NULL REFERENCES identity.account(user_id) ON DELETE RESTRICT,
    source_note_id uuid NOT NULL,
    source_note_version_id uuid NOT NULL,
    source_revision bigint NOT NULL CHECK (source_revision > 0),
    public_profile_projection_id uuid NOT NULL,
    title text NOT NULL CHECK (char_length(title) <= 500),
    markdown text NOT NULL CHECK (char_length(markdown) <= 1000000),
    snapshot_revision bigint NOT NULL CHECK (snapshot_revision > 0),
    publication_generation bigint NOT NULL CHECK (publication_generation > 0),
    availability text NOT NULL CHECK (availability IN ('active','unpublished','removed')),
    reason_code text NOT NULL CHECK (reason_code IN ('owner_publish','owner_update','owner_unpublish','owner_republish','source_retired','account_deleted','policy_removed')),
    published_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL CHECK (updated_at >= published_at),
    unpublished_at timestamptz,
    removed_at timestamptz,
    CONSTRAINT ux_publication_source UNIQUE (owner_user_id,source_note_id),
    CONSTRAINT ux_publication_snapshot UNIQUE (publication_id,snapshot_revision),
    CONSTRAINT fk_publication_public_author FOREIGN KEY (public_profile_projection_id,owner_user_id)
        REFERENCES profile.public_profile_projection(public_profile_projection_id,user_id) ON DELETE RESTRICT,
    CONSTRAINT ck_publication_availability CHECK (
        (availability='active' AND unpublished_at IS NULL AND removed_at IS NULL)
        OR (availability='unpublished' AND unpublished_at IS NOT NULL AND removed_at IS NULL)
        OR (availability='removed' AND removed_at IS NOT NULL)),
    CONSTRAINT ck_publication_times CHECK ((unpublished_at IS NULL OR unpublished_at >= published_at)
        AND (removed_at IS NULL OR removed_at >= published_at))
);
CREATE INDEX ix_publication_owner_page ON publishing.publication(owner_user_id,updated_at DESC,publication_id DESC);
CREATE INDEX ix_publication_public_author ON publishing.publication(public_profile_projection_id,published_at DESC,publication_id DESC) WHERE availability='active';
CREATE TABLE publishing.publication_snapshot_tag (
    publication_id uuid NOT NULL,
    snapshot_revision bigint NOT NULL,
    normalized_label text COLLATE "C" NOT NULL CHECK (char_length(normalized_label) BETWEEN 1 AND 100),
    display_label text NOT NULL CHECK (char_length(display_label) BETWEEN 1 AND 100 AND display_label !~ '[[:cntrl:]]'),
    PRIMARY KEY (publication_id,snapshot_revision,normalized_label),
    FOREIGN KEY (publication_id,snapshot_revision) REFERENCES publishing.publication(publication_id,snapshot_revision) ON DELETE RESTRICT
);
CREATE TABLE publishing.publication_public_media (
    public_media_id uuid PRIMARY KEY,
    publication_id uuid NOT NULL,
    snapshot_revision bigint NOT NULL,
    publication_generation bigint NOT NULL CHECK (publication_generation > 0),
    source_attachment_id uuid,
    public_object_reference text NOT NULL UNIQUE CHECK (public_object_reference ~ '^public-publication-media/[0-9a-f]{64}$'),
    media_kind text NOT NULL CHECK (media_kind IN ('image','audio','video','pdf')),
    media_type text NOT NULL,
    display_name text NOT NULL CHECK (char_length(display_name) BETWEEN 1 AND 255 AND display_name !~ '[[:cntrl:]]'),
    byte_size bigint NOT NULL CHECK (byte_size BETWEEN 1 AND 26214400),
    width integer CHECK (width > 0), height integer CHECK (height > 0),
    duration_seconds numeric CHECK (duration_seconds >= 0), page_count integer CHECK (page_count > 0),
    media_order integer NOT NULL CHECK (media_order BETWEEN 0 AND 19),
    state text NOT NULL CHECK (state IN ('current','removed')),
    UNIQUE (publication_id,snapshot_revision,media_order),
    FOREIGN KEY (publication_id,snapshot_revision) REFERENCES publishing.publication(publication_id,snapshot_revision) ON DELETE RESTRICT,
    CONSTRAINT ck_public_media_type CHECK (
        (media_kind='image' AND media_type IN ('image/png','image/jpeg') AND byte_size<=5242880
            AND width IS NOT NULL AND height IS NOT NULL AND width BETWEEN 1 AND 4096 AND height BETWEEN 1 AND 4096
            AND width::bigint*height<=8294400 AND duration_seconds IS NULL AND page_count IS NULL)
        OR (media_kind='audio' AND media_type='audio/wav' AND byte_size<=10485760 AND width IS NULL AND height IS NULL
            AND duration_seconds IS NOT NULL AND duration_seconds>0 AND duration_seconds<=300 AND page_count IS NULL)
        OR (media_kind='video' AND media_type='video/mp4' AND width IS NOT NULL AND height IS NOT NULL
            AND width BETWEEN 1 AND 4096 AND height BETWEEN 1 AND 4096 AND width::bigint*height<=8294400
            AND duration_seconds IS NOT NULL AND duration_seconds>0 AND duration_seconds<=300 AND page_count IS NULL)
        OR (media_kind='pdf' AND media_type='application/pdf' AND byte_size<=10485760 AND width IS NULL AND height IS NULL
            AND duration_seconds IS NULL AND page_count IS NOT NULL AND page_count BETWEEN 1 AND 100))
);
CREATE TABLE publishing.publication_audit_fact (
    audit_fact_id uuid PRIMARY KEY,
    publication_id uuid NOT NULL REFERENCES publishing.publication(publication_id) ON DELETE RESTRICT,
    actor_user_id uuid NOT NULL REFERENCES identity.account(user_id) ON DELETE RESTRICT,
    action_code text NOT NULL CHECK (action_code IN ('create','update','unpublish','republish','source_retired','account_deleted','remove')),
    outcome_code text NOT NULL CHECK (outcome_code='committed'),
    reason_code text NOT NULL CHECK (reason_code IN ('owner_publish','owner_update','owner_unpublish','owner_republish','source_retired','account_deleted','policy_removed')),
    snapshot_revision bigint NOT NULL CHECK (snapshot_revision > 0),
    publication_generation bigint NOT NULL CHECK (publication_generation > 0),
    occurred_at timestamptz NOT NULL
);
CREATE INDEX ix_publication_audit_time ON publishing.publication_audit_fact(publication_id,occurred_at);

CREATE TABLE discovery.publication_projection (
    publication_id uuid PRIMARY KEY REFERENCES publishing.publication(publication_id) ON DELETE RESTRICT,
    public_profile_projection_id uuid NOT NULL REFERENCES profile.public_profile_projection(public_profile_projection_id) ON DELETE RESTRICT,
    publication_generation bigint NOT NULL CHECK (publication_generation > 0),
    active boolean NOT NULL,
    title text NOT NULL CHECK (char_length(title) <= 500),
    markdown text NOT NULL CHECK (char_length(markdown) <= 1000000),
    tags text[] NOT NULL CHECK (cardinality(tags) <= 50),
    published_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
    like_count bigint NOT NULL DEFAULT 0 CHECK (like_count >= 0),
    view_count bigint NOT NULL DEFAULT 0 CHECK (view_count >= 0)
);
CREATE INDEX ix_discovery_author_page ON discovery.publication_projection(public_profile_projection_id,published_at DESC,publication_id DESC) WHERE active;
CREATE TABLE discovery.publication_view_aggregate (
    publication_id uuid NOT NULL REFERENCES publishing.publication(publication_id) ON DELETE RESTRICT,
    bucket_date date NOT NULL,
    view_count bigint NOT NULL CHECK (view_count >= 0),
    updated_at timestamptz NOT NULL,
    PRIMARY KEY (publication_id,bucket_date)
);
CREATE INDEX ix_publication_view_bucket ON discovery.publication_view_aggregate(bucket_date,publication_id);

CREATE FUNCTION discovery.guard_public_projection() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.publication_id IS DISTINCT FROM OLD.publication_id OR NEW.publication_generation < OLD.publication_generation
       OR NEW.updated_at < OLD.updated_at
       OR ((NEW.active IS DISTINCT FROM OLD.active OR NEW.title IS DISTINCT FROM OLD.title
            OR NEW.markdown IS DISTINCT FROM OLD.markdown OR NEW.tags IS DISTINCT FROM OLD.tags
            OR NEW.public_profile_projection_id IS DISTINCT FROM OLD.public_profile_projection_id)
           AND NEW.publication_generation <= OLD.publication_generation) THEN
        RAISE EXCEPTION 'Invalid discovery projection transition' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER trg_discovery_public_transition BEFORE UPDATE ON discovery.publication_projection
    FOR EACH ROW EXECUTE FUNCTION discovery.guard_public_projection();

CREATE FUNCTION profile.guard_public_projection() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.public_profile_projection_id IS DISTINCT FROM OLD.public_profile_projection_id
       OR NEW.profile_id IS DISTINCT FROM OLD.profile_id OR NEW.user_id IS DISTINCT FROM OLD.user_id
       OR NEW.projection_generation <= OLD.projection_generation OR NEW.updated_at < OLD.updated_at THEN
        RAISE EXCEPTION 'Invalid public projection transition' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER trg_public_profile_transition BEFORE UPDATE ON profile.public_profile_projection
    FOR EACH ROW EXECUTE FUNCTION profile.guard_public_projection();
CREATE FUNCTION publishing.guard_publication() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.publication_id IS DISTINCT FROM OLD.publication_id OR NEW.owner_user_id IS DISTINCT FROM OLD.owner_user_id
       OR NEW.source_note_id IS DISTINCT FROM OLD.source_note_id OR NEW.published_at IS DISTINCT FROM OLD.published_at
       OR NEW.publication_generation <= OLD.publication_generation OR NEW.snapshot_revision < OLD.snapshot_revision
       OR NEW.updated_at < OLD.updated_at OR (OLD.availability='removed' AND NEW.availability<>'removed')
       OR ((NEW.title IS DISTINCT FROM OLD.title OR NEW.markdown IS DISTINCT FROM OLD.markdown
            OR NEW.source_note_version_id IS DISTINCT FROM OLD.source_note_version_id
            OR NEW.source_revision IS DISTINCT FROM OLD.source_revision) AND NEW.snapshot_revision <= OLD.snapshot_revision) THEN
        RAISE EXCEPTION 'Invalid publication transition' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER trg_publication_transition BEFORE UPDATE ON publishing.publication
    FOR EACH ROW EXECUTE FUNCTION publishing.guard_publication();
CREATE FUNCTION publishing.reject_audit_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Publication audit is append only' USING ERRCODE='23514'; END; $$;
CREATE TRIGGER trg_publication_audit_immutable BEFORE UPDATE OR DELETE ON publishing.publication_audit_fact
    FOR EACH ROW EXECUTE FUNCTION publishing.reject_audit_mutation();
