CREATE EXTENSION IF NOT EXISTS vector;

-- Existing requests have no target model/config identity. Preserve their history,
-- but never execute them as though they had been approved for a real provider.
UPDATE knowledge.knowledge_work_intent SET state='obsolete', next_attempt_at=NULL,
    lease_owner=NULL, lease_token=NULL, lease_until=NULL, updated_at=clock_timestamp()
WHERE state IN ('queued','claimed','retry_wait');
ALTER TABLE knowledge.knowledge_work_intent
    ADD COLUMN derivation_class varchar(32) NOT NULL DEFAULT 'text_surrogate',
    ADD COLUMN target_lineage_id varchar(64) NOT NULL DEFAULT 'legacy_unassigned',
    ADD CONSTRAINT ck_work_derivation_class CHECK (derivation_class='text_surrogate'),
    ADD CONSTRAINT ck_work_target_lineage CHECK (target_lineage_id='legacy_unassigned' OR target_lineage_id ~ '^[0-9a-f]{64}$');
ALTER TABLE knowledge.knowledge_work_intent DROP CONSTRAINT knowledge_work_intent_failure_code_check;
ALTER TABLE knowledge.knowledge_work_intent ADD CONSTRAINT ck_work_failure CHECK
    (failure_code IN ('transient_dependency','invalid_source','policy_blocked','attempts_exhausted',
        'quota','provider_unavailable','invalid_output','budget_exceeded','lineage_obsolete'));
CREATE INDEX ix_knowledge_work_target ON knowledge.knowledge_work_intent
    (owner_user_id,source_note_id,source_attachment_id,target_lineage_id,created_at DESC);

CREATE OR REPLACE FUNCTION knowledge.protect_work_lineage() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.knowledge_work_intent_id,NEW.scope_kind,NEW.owner_user_id,NEW.work_class,NEW.source_kind,
        NEW.source_note_id,NEW.source_attachment_id,NEW.expected_revision,NEW.expected_ai_generation,
        NEW.expected_attachment_generation,NEW.max_attempts,NEW.dedupe_key,NEW.created_at,
        NEW.derivation_class,NEW.target_lineage_id)
        IS DISTINCT FROM
        (OLD.knowledge_work_intent_id,OLD.scope_kind,OLD.owner_user_id,OLD.work_class,OLD.source_kind,
        OLD.source_note_id,OLD.source_attachment_id,OLD.expected_revision,OLD.expected_ai_generation,
        OLD.expected_attachment_generation,OLD.max_attempts,OLD.dedupe_key,OLD.created_at,
        OLD.derivation_class,OLD.target_lineage_id)
        OR NEW.attempt_count<OLD.attempt_count OR NEW.updated_at<OLD.updated_at
        OR (OLD.state IN ('completed','failed','obsolete') AND NEW.state<>OLD.state)
    THEN RAISE EXCEPTION 'Work lineage and terminal state are immutable' USING ERRCODE='23514'; END IF;
    RETURN NEW;
END;
$$;

CREATE TABLE knowledge.private_derived_representation (
    derived_representation_id uuid PRIMARY KEY DEFAULT uuidv7(),
    owner_user_id uuid NOT NULL REFERENCES identity.account(user_id) ON DELETE RESTRICT,
    source_kind varchar(16) NOT NULL CHECK (source_kind IN ('note','attachment')),
    source_note_id uuid NOT NULL,
    source_attachment_id uuid,
    source_revision bigint NOT NULL CHECK (source_revision>0),
    processing_generation bigint NOT NULL CHECK (processing_generation>0),
    attachment_generation bigint CHECK (attachment_generation>0),
    derivation_class varchar(32) NOT NULL CHECK (derivation_class='text_surrogate'),
    processing_policy_id uuid NOT NULL REFERENCES knowledge.processing_policy(processing_policy_id) ON DELETE RESTRICT,
    lineage_id varchar(64) NOT NULL CHECK (lineage_id ~ '^[0-9a-f]{64}$'),
    lineage_configuration varchar(4096) NOT NULL CHECK (length(lineage_configuration) BETWEEN 1 AND 4096),
    modality varchar(16) NOT NULL CHECK (modality IN ('note','image','audio','video','pdf')),
    embedding_dimension integer NOT NULL CHECK (embedding_dimension BETWEEN 1 AND 16000),
    distance_operator varchar(16) NOT NULL CHECK (distance_operator IN ('cosine','l2','inner_product')),
    state varchar(16) NOT NULL DEFAULT 'ready' CHECK (state IN ('ready','obsolete')),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    current_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    obsolete_at timestamptz,
    UNIQUE (derived_representation_id,owner_user_id),
    UNIQUE (derived_representation_id,owner_user_id,lineage_id,embedding_dimension),
    FOREIGN KEY (source_note_id,owner_user_id) REFERENCES notes.note(note_id,owner_user_id) ON DELETE RESTRICT,
    FOREIGN KEY (source_attachment_id,source_note_id,owner_user_id)
        REFERENCES notes.attachment(attachment_id,note_id,owner_user_id) ON DELETE RESTRICT,
    CHECK ((source_kind='note' AND modality='note' AND source_attachment_id IS NULL AND attachment_generation IS NULL)
        OR (source_kind='attachment' AND modality<>'note' AND source_attachment_id IS NOT NULL AND attachment_generation IS NOT NULL)),
    CHECK (current_at>=created_at),
    CHECK ((state='ready' AND obsolete_at IS NULL) OR (state='obsolete' AND obsolete_at>=current_at))
);
CREATE UNIQUE INDEX ux_private_representation_current ON knowledge.private_derived_representation
    (owner_user_id,source_note_id,source_attachment_id,derivation_class,lineage_id) NULLS NOT DISTINCT WHERE state='ready';
CREATE INDEX ix_private_representation_candidate ON knowledge.private_derived_representation
    (owner_user_id,lineage_id,processing_policy_id) WHERE state='ready';
CREATE INDEX ix_private_representation_source ON knowledge.private_derived_representation
    (owner_user_id,source_note_id,source_attachment_id);
CREATE FUNCTION knowledge.protect_private_representation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (to_jsonb(NEW)-'state'-'obsolete_at') IS DISTINCT FROM (to_jsonb(OLD)-'state'-'obsolete_at')
        OR (OLD.state='obsolete' AND (NEW.state,NEW.obsolete_at) IS DISTINCT FROM (OLD.state,OLD.obsolete_at))
    THEN RAISE EXCEPTION 'Representation provenance is immutable' USING ERRCODE='23514'; END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_private_representation_identity BEFORE UPDATE ON knowledge.private_derived_representation
    FOR EACH ROW EXECUTE FUNCTION knowledge.protect_private_representation();

CREATE TABLE knowledge.private_derived_segment (
    derived_segment_id uuid PRIMARY KEY DEFAULT uuidv7(),
    parent_id uuid NOT NULL,
    owner_user_id uuid NOT NULL,
    ordinal integer NOT NULL CHECK (ordinal BETWEEN 0 AND 511),
    surrogate_text varchar(12000) NOT NULL CHECK (length(surrogate_text) BETWEEN 1 AND 12000),
    segment_kind varchar(32) NOT NULL CHECK (segment_kind IN ('note_text','pdf_text','whole_image','image_region','transcript','video_scene')),
    heading_ancestry varchar(1024) NOT NULL DEFAULT '',
    source_start integer CHECK (source_start>=0),
    source_end integer CHECK (source_end>=source_start AND source_end<=1000000),
    page_number integer CHECK (page_number BETWEEN 1 AND 500),
    time_start double precision CHECK (time_start>=0 AND time_start<'Infinity'::float8),
    time_end double precision CHECK (time_end>=time_start AND time_end<'Infinity'::float8),
    region_x double precision, region_y double precision, region_width double precision, region_height double precision,
    lineage_id varchar(64) NOT NULL,
    embedding_dimension integer NOT NULL CHECK (embedding_dimension BETWEEN 1 AND 16000),
    embedding vector NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (parent_id,ordinal),
    FOREIGN KEY (parent_id,owner_user_id) REFERENCES knowledge.private_derived_representation(derived_representation_id,owner_user_id) ON DELETE RESTRICT,
    FOREIGN KEY (parent_id,owner_user_id,lineage_id,embedding_dimension)
        REFERENCES knowledge.private_derived_representation(derived_representation_id,owner_user_id,lineage_id,embedding_dimension) ON DELETE RESTRICT,
    CHECK (vector_dims(embedding)=embedding_dimension AND vector_norm(embedding)>0),
    CHECK ((source_start IS NULL)=(source_end IS NULL)),
    CHECK ((time_start IS NULL)=(time_end IS NULL)),
    CHECK ((region_x IS NULL AND region_y IS NULL AND region_width IS NULL AND region_height IS NULL)
        OR (region_x>=0 AND region_y>=0 AND region_width>0 AND region_height>0
            AND region_x+region_width<=1 AND region_y+region_height<=1
            AND region_x IS NOT NULL AND region_y IS NOT NULL AND region_width IS NOT NULL AND region_height IS NOT NULL)),
    CHECK ((segment_kind='note_text' AND source_start IS NOT NULL AND page_number IS NULL AND time_start IS NULL AND region_x IS NULL)
        OR (segment_kind='pdf_text' AND page_number IS NOT NULL AND time_start IS NULL AND region_x IS NULL)
        OR (segment_kind='whole_image' AND source_start IS NULL AND page_number IS NULL AND time_start IS NULL AND region_x IS NULL)
        OR (segment_kind='image_region' AND source_start IS NULL AND region_x IS NOT NULL AND page_number IS NULL AND time_start IS NULL)
        OR (segment_kind IN ('transcript','video_scene') AND source_start IS NULL AND time_start IS NOT NULL AND page_number IS NULL AND region_x IS NULL))
);
CREATE INDEX ix_private_segment_scope ON knowledge.private_derived_segment(owner_user_id,lineage_id,parent_id);
CREATE FUNCTION knowledge.protect_private_segment() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Segment is immutable' USING ERRCODE='23514'; END;
$$;
CREATE TRIGGER trg_private_segment_identity BEFORE UPDATE ON knowledge.private_derived_segment
    FOR EACH ROW EXECUTE FUNCTION knowledge.protect_private_segment();
