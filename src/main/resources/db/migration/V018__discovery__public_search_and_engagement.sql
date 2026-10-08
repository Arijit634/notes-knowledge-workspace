-- Public work extends the existing, constrained Knowledge intent; no generic job family.
ALTER TABLE knowledge.knowledge_work_intent
    ALTER COLUMN owner_user_id DROP NOT NULL,
    DROP CONSTRAINT knowledge_work_intent_scope_kind_check,
    ADD COLUMN source_publication_id uuid REFERENCES publishing.publication(publication_id) ON DELETE RESTRICT,
    ADD COLUMN expected_publication_generation bigint,
    ADD COLUMN expected_snapshot_revision bigint;
DO $$ DECLARE original text; BEGIN
    SELECT pg_get_expr(conbin,conrelid) INTO original FROM pg_constraint
        WHERE conrelid='knowledge.knowledge_work_intent'::regclass AND conname='ck_work_family';
    ALTER TABLE knowledge.knowledge_work_intent DROP CONSTRAINT ck_work_family;
    EXECUTE 'ALTER TABLE knowledge.knowledge_work_intent ADD CONSTRAINT ck_work_family CHECK (
        (scope_kind=''private'' AND owner_user_id IS NOT NULL AND source_publication_id IS NULL
         AND expected_publication_generation IS NULL AND expected_snapshot_revision IS NULL AND ('||original||'))
        OR (scope_kind=''public'' AND owner_user_id IS NULL AND work_class=''public_publication_derivation''
         AND source_kind IS NOT NULL AND source_kind=''publication'' AND source_publication_id IS NOT NULL
         AND expected_publication_generation IS NOT NULL AND expected_publication_generation>0
         AND expected_snapshot_revision IS NOT NULL AND expected_snapshot_revision>0
         AND source_note_id IS NULL AND source_attachment_id IS NULL AND expected_revision IS NULL
         AND expected_ai_generation IS NULL AND expected_attachment_generation IS NULL
         AND derivation_class=''text_surrogate'' AND derivation_class IS NOT NULL
         AND target_lineage_id IS NOT NULL AND target_lineage_id ~ ''^[0-9a-f]{64}$''
         AND operation_purpose IS NULL AND operation_metadata IS NULL AND operation_expires_at IS NULL
         AND checkpoint_version=0 AND input_ciphertext IS NULL AND result_ciphertext IS NULL AND state<>''cancelled''))';
END $$;
CREATE UNIQUE INDEX ux_public_work_dedupe ON knowledge.knowledge_work_intent(source_publication_id,dedupe_key)
    WHERE scope_kind='public' AND state IN ('queued','claimed','retry_wait');
CREATE INDEX ix_public_work_ready ON knowledge.knowledge_work_intent(next_attempt_at,created_at,knowledge_work_intent_id)
    WHERE scope_kind='public' AND state IN ('queued','retry_wait');
CREATE INDEX ix_public_work_reclaim ON knowledge.knowledge_work_intent(lease_until,created_at,knowledge_work_intent_id)
    WHERE scope_kind='public' AND state='claimed';
CREATE FUNCTION knowledge.protect_public_work_source() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
    IF (NEW.source_publication_id,NEW.expected_publication_generation,NEW.expected_snapshot_revision)
        IS DISTINCT FROM (OLD.source_publication_id,OLD.expected_publication_generation,OLD.expected_snapshot_revision)
    THEN RAISE EXCEPTION 'Public work source is immutable' USING ERRCODE='23514'; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_public_work_source BEFORE UPDATE ON knowledge.knowledge_work_intent
    FOR EACH ROW EXECUTE FUNCTION knowledge.protect_public_work_source();

CREATE TABLE knowledge.public_derived_representation (
    derived_representation_id uuid PRIMARY KEY DEFAULT uuidv7(),
    publication_id uuid NOT NULL REFERENCES publishing.publication(publication_id) ON DELETE RESTRICT,
    snapshot_revision bigint NOT NULL CHECK(snapshot_revision>0),
    publication_generation bigint NOT NULL CHECK(publication_generation>0),
    derivation_class text NOT NULL CHECK(derivation_class='text_surrogate'),
    lineage_id text NOT NULL CHECK(lineage_id ~ '^[0-9a-f]{64}$'),
    state text NOT NULL CHECK(state IN ('current','obsolete')),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp() CHECK(updated_at>=created_at),
    UNIQUE(derived_representation_id,publication_id),
    UNIQUE(derived_representation_id,publication_id,snapshot_revision,publication_generation,lineage_id)
);
CREATE UNIQUE INDEX ux_public_representation_current ON knowledge.public_derived_representation(publication_id,derivation_class,lineage_id)
    WHERE state='current';
CREATE INDEX ix_public_representation_generation ON knowledge.public_derived_representation(publication_id,publication_generation,snapshot_revision);
CREATE TABLE knowledge.public_derived_segment (
    derived_segment_id uuid PRIMARY KEY DEFAULT uuidv7(),
    derived_representation_id uuid NOT NULL,
    publication_id uuid NOT NULL,
    snapshot_revision bigint NOT NULL,
    publication_generation bigint NOT NULL,
    lineage_id text NOT NULL,
    segment_order integer NOT NULL CHECK(segment_order BETWEEN 0 AND 511),
    location_kind text NOT NULL CHECK(location_kind='public_text'),
    heading text NOT NULL CHECK(char_length(heading)<=1024),
    source_start integer NOT NULL CHECK(source_start>=0),
    source_end integer NOT NULL CHECK(source_end>source_start AND source_end<=1000502),
    text_content text NOT NULL CHECK(char_length(text_content) BETWEEN 1 AND 3600),
    FOREIGN KEY(derived_representation_id,publication_id) REFERENCES knowledge.public_derived_representation(derived_representation_id,publication_id) ON DELETE RESTRICT,
    FOREIGN KEY(derived_representation_id,publication_id,snapshot_revision,publication_generation,lineage_id)
        REFERENCES knowledge.public_derived_representation(derived_representation_id,publication_id,snapshot_revision,publication_generation,lineage_id) ON DELETE RESTRICT,
    UNIQUE(derived_representation_id,segment_order)
);
CREATE FUNCTION knowledge.protect_public_representation() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
    IF (to_jsonb(NEW)-'state'-'updated_at') IS DISTINCT FROM (to_jsonb(OLD)-'state'-'updated_at')
       OR NEW.updated_at<OLD.updated_at OR (OLD.state='obsolete' AND NEW.state<>'obsolete')
    THEN RAISE EXCEPTION 'Public representation lineage is immutable' USING ERRCODE='23514'; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_public_representation BEFORE UPDATE ON knowledge.public_derived_representation
    FOR EACH ROW EXECUTE FUNCTION knowledge.protect_public_representation();

CREATE TABLE discovery.publication_like (
    user_id uuid NOT NULL REFERENCES identity.account(user_id) ON DELETE RESTRICT,
    publication_id uuid NOT NULL REFERENCES publishing.publication(publication_id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(user_id,publication_id)
);
CREATE INDEX ix_publication_like_target ON discovery.publication_like(publication_id);
CREATE INDEX ix_discovery_latest ON discovery.publication_projection(published_at DESC,publication_id DESC) WHERE active;
CREATE INDEX ix_discovery_public_fts ON discovery.publication_projection USING gin
    ((to_tsvector('simple',title||' '||markdown)||to_tsvector('english',title||' '||markdown))) WHERE active;
CREATE INDEX ix_discovery_public_title_trgm ON discovery.publication_projection USING gin(title gin_trgm_ops) WHERE active;
CREATE INDEX ix_discovery_public_tags ON discovery.publication_projection USING gin(tags) WHERE active;
