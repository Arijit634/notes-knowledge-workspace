CREATE SCHEMA IF NOT EXISTS knowledge;

CREATE TABLE knowledge.processing_policy (
    processing_policy_id uuid PRIMARY KEY DEFAULT uuidv7(),
    policy_code varchar(64) NOT NULL CHECK (policy_code ~ '^[a-z][a-z0-9_.-]{0,63}$'),
    policy_version bigint NOT NULL CHECK (policy_version > 0),
    policy_fingerprint varchar(64) NOT NULL CHECK (policy_fingerprint ~ '^[0-9a-f]{64}$'),
    disclosure_revision varchar(64) NOT NULL CHECK (disclosure_revision ~ '^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$'),
    effective_at timestamptz NOT NULL,
    retired_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (policy_code,policy_version),
    CHECK (retired_at IS NULL OR retired_at >= effective_at)
);
CREATE INDEX ix_processing_policy_current ON knowledge.processing_policy(policy_code,policy_version DESC);

-- Operator policy rotation and exact-version acknowledgement share this short
-- transaction lock. A new version cannot slip between current selection and commit.
CREATE FUNCTION knowledge.protect_policy_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Policy identity is immutable' USING ERRCODE='23514'; END IF;
    PERFORM pg_advisory_xact_lock(hashtextextended(NEW.policy_code, 719));
    IF TG_OP='UPDATE' AND (
        (NEW.processing_policy_id,NEW.policy_code,NEW.policy_version,NEW.policy_fingerprint,NEW.disclosure_revision,NEW.effective_at,NEW.created_at)
        IS DISTINCT FROM
        (OLD.processing_policy_id,OLD.policy_code,OLD.policy_version,OLD.policy_fingerprint,OLD.disclosure_revision,OLD.effective_at,OLD.created_at)
        OR (OLD.retired_at IS NOT NULL AND NEW.retired_at IS DISTINCT FROM OLD.retired_at))
    THEN RAISE EXCEPTION 'Policy identity is immutable' USING ERRCODE='23514'; END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_policy_identity BEFORE INSERT OR UPDATE OR DELETE ON knowledge.processing_policy
FOR EACH ROW EXECUTE FUNCTION knowledge.protect_policy_identity();

CREATE TABLE knowledge.processing_policy_acknowledgement (
    user_id uuid NOT NULL REFERENCES identity.account(user_id) ON DELETE RESTRICT,
    processing_policy_id uuid NOT NULL REFERENCES knowledge.processing_policy(processing_policy_id) ON DELETE RESTRICT,
    acknowledged_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    disclosure_revision varchar(64) NOT NULL CHECK (disclosure_revision ~ '^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$'),
    PRIMARY KEY (user_id,processing_policy_id)
);
CREATE INDEX ix_policy_ack_policy ON knowledge.processing_policy_acknowledgement(processing_policy_id);
CREATE FUNCTION knowledge.protect_acknowledgement() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Acknowledgement is immutable' USING ERRCODE='23514'; END;
$$;
CREATE TRIGGER trg_ack_immutable BEFORE UPDATE OR DELETE ON knowledge.processing_policy_acknowledgement
FOR EACH ROW EXECUTE FUNCTION knowledge.protect_acknowledgement();
CREATE FUNCTION knowledge.check_ack_disclosure() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM knowledge.processing_policy
        WHERE processing_policy_id=NEW.processing_policy_id AND disclosure_revision=NEW.disclosure_revision)
    THEN RAISE EXCEPTION 'Acknowledgement disclosure must match policy' USING ERRCODE='23514'; END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_ack_disclosure BEFORE INSERT ON knowledge.processing_policy_acknowledgement
FOR EACH ROW EXECUTE FUNCTION knowledge.check_ack_disclosure();

-- Private-only foundation. Public scope is deliberately deferred until Publishing exists.
-- No source content, provider/model identity, prompt, capability material or vector.
CREATE TABLE knowledge.knowledge_work_intent (
    knowledge_work_intent_id uuid PRIMARY KEY DEFAULT uuidv7(),
    scope_kind varchar(16) NOT NULL DEFAULT 'private' CHECK (scope_kind='private'),
    owner_user_id uuid NOT NULL REFERENCES identity.account(user_id) ON DELETE RESTRICT,
    work_class varchar(32) NOT NULL CHECK (work_class IN ('private_note_derivation','private_attachment_derivation')),
    source_kind varchar(16) NOT NULL CHECK (source_kind IN ('note','attachment')),
    source_note_id uuid NOT NULL,
    source_attachment_id uuid,
    expected_revision bigint NOT NULL CHECK (expected_revision>0),
    expected_ai_generation bigint NOT NULL CHECK (expected_ai_generation>0),
    expected_attachment_generation bigint CHECK (expected_attachment_generation>0),
    state varchar(16) NOT NULL DEFAULT 'queued' CHECK (state IN ('queued','claimed','retry_wait','completed','failed','obsolete')),
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count BETWEEN 0 AND 10),
    max_attempts integer NOT NULL CHECK (max_attempts BETWEEN 1 AND 10),
    next_attempt_at timestamptz,
    lease_owner varchar(64),
    lease_token uuid,
    lease_until timestamptz,
    dedupe_key varchar(64) NOT NULL CHECK (dedupe_key ~ '^[0-9a-f]{64}$'),
    failure_code varchar(32) CHECK (failure_code IN ('transient_dependency','invalid_source','policy_blocked','attempts_exhausted')),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (source_note_id,owner_user_id) REFERENCES notes.note(note_id,owner_user_id) ON DELETE RESTRICT,
    FOREIGN KEY (source_attachment_id,source_note_id,owner_user_id) REFERENCES notes.attachment(attachment_id,note_id,owner_user_id) ON DELETE RESTRICT,
    CHECK (attempt_count<=max_attempts),
    CHECK ((source_kind='note' AND work_class='private_note_derivation' AND source_attachment_id IS NULL AND expected_attachment_generation IS NULL)
        OR (source_kind='attachment' AND work_class='private_attachment_derivation' AND source_attachment_id IS NOT NULL AND expected_attachment_generation IS NOT NULL)),
    CHECK ((state='claimed' AND attempt_count>0 AND lease_owner IS NOT NULL AND lease_owner ~ '^[A-Za-z][A-Za-z0-9_-]{0,63}$' AND lease_token IS NOT NULL AND uuid_extract_version(lease_token)=7 AND lease_until IS NOT NULL AND next_attempt_at IS NULL)
        OR (state<>'claimed' AND lease_owner IS NULL AND lease_token IS NULL AND lease_until IS NULL)),
    CHECK ((state IN ('queued','retry_wait') AND next_attempt_at IS NOT NULL) OR (state NOT IN ('queued','retry_wait') AND next_attempt_at IS NULL)),
    CHECK (updated_at>=created_at)
);
CREATE UNIQUE INDEX ux_knowledge_work_active_dedupe ON knowledge.knowledge_work_intent(owner_user_id,dedupe_key)
WHERE state IN ('queued','claimed','retry_wait');
CREATE INDEX ix_knowledge_work_ready ON knowledge.knowledge_work_intent(next_attempt_at,created_at,knowledge_work_intent_id)
WHERE state IN ('queued','retry_wait');
CREATE INDEX ix_knowledge_work_reclaim ON knowledge.knowledge_work_intent(lease_until,created_at,knowledge_work_intent_id)
WHERE state='claimed';
CREATE INDEX ix_knowledge_work_source ON knowledge.knowledge_work_intent(owner_user_id,source_note_id,source_attachment_id);
CREATE FUNCTION knowledge.protect_work_lineage() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.knowledge_work_intent_id,NEW.scope_kind,NEW.owner_user_id,NEW.work_class,NEW.source_kind,
        NEW.source_note_id,NEW.source_attachment_id,NEW.expected_revision,NEW.expected_ai_generation,
        NEW.expected_attachment_generation,NEW.max_attempts,NEW.dedupe_key,NEW.created_at)
        IS DISTINCT FROM
        (OLD.knowledge_work_intent_id,OLD.scope_kind,OLD.owner_user_id,OLD.work_class,OLD.source_kind,
        OLD.source_note_id,OLD.source_attachment_id,OLD.expected_revision,OLD.expected_ai_generation,
        OLD.expected_attachment_generation,OLD.max_attempts,OLD.dedupe_key,OLD.created_at)
        OR NEW.attempt_count<OLD.attempt_count OR NEW.updated_at<OLD.updated_at
        OR (OLD.state IN ('completed','failed','obsolete') AND NEW.state<>OLD.state)
    THEN RAISE EXCEPTION 'Work lineage and terminal state are immutable' USING ERRCODE='23514'; END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_work_lineage BEFORE UPDATE ON knowledge.knowledge_work_intent
FOR EACH ROW EXECUTE FUNCTION knowledge.protect_work_lineage();
