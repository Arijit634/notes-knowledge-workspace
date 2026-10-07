-- Distinct constrained row families on existing durable work, not a generic job store.
ALTER TABLE knowledge.knowledge_work_intent
    ALTER COLUMN source_kind DROP NOT NULL,
    ALTER COLUMN source_note_id DROP NOT NULL,
    ALTER COLUMN expected_revision DROP NOT NULL,
    ALTER COLUMN expected_ai_generation DROP NOT NULL,
    ALTER COLUMN derivation_class DROP NOT NULL,
    ALTER COLUMN target_lineage_id DROP NOT NULL,
    ADD COLUMN operation_purpose text,
    ADD COLUMN operation_metadata jsonb,
    ADD COLUMN operation_expires_at timestamptz,
    ADD COLUMN checkpoint_version bigint NOT NULL DEFAULT 0 CHECK (checkpoint_version>=0),
    ADD COLUMN input_ciphertext bytea,
    ADD COLUMN input_nonce bytea,
    ADD COLUMN input_key_version text,
    ADD COLUMN result_ciphertext bytea,
    ADD COLUMN result_nonce bytea,
    ADD COLUMN result_key_version text;
ALTER TABLE knowledge.knowledge_work_intent
    DROP CONSTRAINT knowledge_work_intent_work_class_check,
    DROP CONSTRAINT knowledge_work_intent_source_kind_check,
    DROP CONSTRAINT knowledge_work_intent_state_check,
    DROP CONSTRAINT ck_work_derivation_class,
    DROP CONSTRAINT ck_work_target_lineage;
DO $$ DECLARE c record; BEGIN
    FOR c IN SELECT conname FROM pg_constraint WHERE conrelid='knowledge.knowledge_work_intent'::regclass
        AND contype='c' AND pg_get_constraintdef(oid) LIKE '%source_kind%' AND pg_get_constraintdef(oid) LIKE '%work_class%'
    LOOP EXECUTE format('ALTER TABLE knowledge.knowledge_work_intent DROP CONSTRAINT %I',c.conname); END LOOP;
END $$;
ALTER TABLE knowledge.knowledge_work_intent
    ADD CONSTRAINT ck_work_state CHECK (state IN ('queued','claimed','retry_wait','completed','failed','obsolete','cancelled')),
    ADD CONSTRAINT ck_work_family CHECK (
        (work_class IN ('private_note_derivation','private_attachment_derivation')
         AND source_note_id IS NOT NULL AND expected_revision>0 AND expected_revision IS NOT NULL
         AND expected_ai_generation>0 AND expected_ai_generation IS NOT NULL
         AND derivation_class='text_surrogate' AND derivation_class IS NOT NULL
         AND target_lineage_id IS NOT NULL AND (target_lineage_id='legacy_unassigned' OR target_lineage_id ~ '^[0-9a-f]{64}$')
         AND ((source_kind='note' AND work_class='private_note_derivation' AND source_attachment_id IS NULL AND expected_attachment_generation IS NULL)
           OR (source_kind='attachment' AND work_class='private_attachment_derivation' AND source_attachment_id IS NOT NULL AND expected_attachment_generation IS NOT NULL))
         AND source_kind IS NOT NULL AND operation_purpose IS NULL AND operation_metadata IS NULL AND operation_expires_at IS NULL
         AND checkpoint_version=0 AND input_ciphertext IS NULL AND result_ciphertext IS NULL AND state<>'cancelled')
        OR
        (work_class='private_knowledge_operation'
         AND source_kind IS NULL AND source_note_id IS NULL AND source_attachment_id IS NULL
         AND expected_revision IS NULL AND expected_ai_generation IS NULL AND expected_attachment_generation IS NULL
         AND derivation_class IS NULL AND target_lineage_id IS NULL
         AND operation_purpose IN ('semantic_corpus','deterministic_corpus') AND operation_purpose IS NOT NULL
         AND operation_metadata IS NOT NULL AND jsonb_typeof(operation_metadata)='object'
         AND octet_length(operation_metadata::text)<=65536
         AND operation_expires_at IS NOT NULL AND operation_expires_at>created_at AND operation_expires_at<=created_at+interval '24 hours'
         AND ((state IN ('queued','claimed','retry_wait') AND input_ciphertext IS NOT NULL)
              OR (state IN ('completed','failed','obsolete','cancelled') AND input_ciphertext IS NULL))
         AND (state NOT IN ('failed','obsolete','cancelled') OR result_ciphertext IS NULL))),
    ADD CONSTRAINT ck_work_input_frame CHECK (
        (input_ciphertext IS NULL AND input_nonce IS NULL AND input_key_version IS NULL)
        OR (input_ciphertext IS NOT NULL AND octet_length(input_ciphertext) BETWEEN 17 AND 16400
            AND input_nonce IS NOT NULL AND octet_length(input_nonce)=12
            AND input_key_version IS NOT NULL AND input_key_version ~ '^[A-Za-z0-9_.-]{1,32}$')),
    ADD CONSTRAINT ck_work_result_frame CHECK (
        (result_ciphertext IS NULL AND result_nonce IS NULL AND result_key_version IS NULL)
        OR (result_ciphertext IS NOT NULL AND octet_length(result_ciphertext) BETWEEN 17 AND 1048592
            AND result_nonce IS NOT NULL AND octet_length(result_nonce)=12
            AND result_key_version IS NOT NULL AND result_key_version ~ '^[A-Za-z0-9_.-]{1,32}$'));
CREATE INDEX ix_knowledge_operation_owner ON knowledge.knowledge_work_intent(owner_user_id,knowledge_work_intent_id)
    WHERE work_class='private_knowledge_operation';
CREATE INDEX ix_knowledge_operation_expiry ON knowledge.knowledge_work_intent(operation_expires_at,knowledge_work_intent_id)
    WHERE work_class='private_knowledge_operation';
CREATE INDEX ix_knowledge_operation_ready ON knowledge.knowledge_work_intent(next_attempt_at,created_at,knowledge_work_intent_id)
    WHERE work_class='private_knowledge_operation' AND state IN ('queued','retry_wait');
CREATE INDEX ix_knowledge_operation_reclaim ON knowledge.knowledge_work_intent(lease_until,created_at,knowledge_work_intent_id)
    WHERE work_class='private_knowledge_operation' AND state='claimed';

CREATE OR REPLACE FUNCTION knowledge.protect_work_lineage() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.knowledge_work_intent_id,NEW.scope_kind,NEW.owner_user_id,NEW.work_class,NEW.source_kind,
        NEW.source_note_id,NEW.source_attachment_id,NEW.expected_revision,NEW.expected_ai_generation,
        NEW.expected_attachment_generation,NEW.max_attempts,NEW.dedupe_key,NEW.created_at,
        NEW.derivation_class,NEW.target_lineage_id,NEW.operation_purpose,NEW.operation_expires_at)
        IS DISTINCT FROM
        (OLD.knowledge_work_intent_id,OLD.scope_kind,OLD.owner_user_id,OLD.work_class,OLD.source_kind,
        OLD.source_note_id,OLD.source_attachment_id,OLD.expected_revision,OLD.expected_ai_generation,
        OLD.expected_attachment_generation,OLD.max_attempts,OLD.dedupe_key,OLD.created_at,
        OLD.derivation_class,OLD.target_lineage_id,OLD.operation_purpose,OLD.operation_expires_at)
        OR NEW.attempt_count<OLD.attempt_count OR NEW.updated_at<OLD.updated_at OR NEW.checkpoint_version<OLD.checkpoint_version
        OR (OLD.state IN ('completed','failed','obsolete','cancelled') AND NEW.state<>OLD.state
            AND NOT (OLD.work_class='private_knowledge_operation' AND OLD.state='completed' AND NEW.state='obsolete'))
        OR (OLD.state IN ('completed','failed','obsolete','cancelled')
            AND ((NEW.operation_metadata IS DISTINCT FROM OLD.operation_metadata)
              OR NEW.input_ciphertext IS NOT NULL
              OR (NEW.result_ciphertext IS NOT NULL AND (NEW.result_ciphertext,NEW.result_nonce,NEW.result_key_version,NEW.checkpoint_version)
                    IS DISTINCT FROM (OLD.result_ciphertext,OLD.result_nonce,OLD.result_key_version,OLD.checkpoint_version))))
    THEN RAISE EXCEPTION 'Work lineage and terminal material are immutable' USING ERRCODE='23514'; END IF;
    RETURN NEW;
END $$;

CREATE FUNCTION knowledge.valid_tag_proposal(value jsonb) RETURNS boolean LANGUAGE sql IMMUTABLE AS $$
    SELECT CASE WHEN jsonb_typeof(value)<>'array' THEN false ELSE
        jsonb_array_length(value) BETWEEN 1 AND 20 AND octet_length(value::text)<=4096
        AND NOT EXISTS (SELECT 1 FROM jsonb_array_elements(value) element
            WHERE jsonb_typeof(element)<>'string' OR length(element#>>'{}') NOT BETWEEN 1 AND 64
                OR btrim(element#>>'{}')='' OR (element#>>'{}') ~ '[[:cntrl:]<>]') END
$$;
CREATE TABLE knowledge.organization_suggestion (
    suggestion_id uuid PRIMARY KEY DEFAULT uuidv7(),
    owner_user_id uuid NOT NULL REFERENCES identity.account(user_id) ON DELETE RESTRICT,
    source_note_id uuid NOT NULL,
    source_revision bigint NOT NULL CHECK (source_revision>0),
    processing_generation bigint NOT NULL CHECK (processing_generation>0),
    proposal_kind text NOT NULL CHECK (proposal_kind='tags'),
    proposal_values jsonb NOT NULL CHECK (knowledge.valid_tag_proposal(proposal_values)),
    state text NOT NULL DEFAULT 'pending' CHECK (state IN ('pending','accepted','rejected','obsolete')),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    resolved_at timestamptz,
    FOREIGN KEY (source_note_id,owner_user_id) REFERENCES notes.note(note_id,owner_user_id) ON DELETE RESTRICT,
    CHECK ((state='pending' AND resolved_at IS NULL) OR (state<>'pending' AND resolved_at>=created_at))
);
CREATE INDEX ix_suggestion_current_source ON knowledge.organization_suggestion(owner_user_id,source_note_id,source_revision,processing_generation)
    WHERE state='pending';
CREATE FUNCTION knowledge.protect_suggestion() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (to_jsonb(NEW)-'state'-'resolved_at') IS DISTINCT FROM (to_jsonb(OLD)-'state'-'resolved_at')
        OR (OLD.state<>'pending' AND NEW IS DISTINCT FROM OLD)
    THEN RAISE EXCEPTION 'Suggestion provenance is immutable' USING ERRCODE='23514'; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_suggestion_identity BEFORE UPDATE ON knowledge.organization_suggestion
    FOR EACH ROW EXECUTE FUNCTION knowledge.protect_suggestion();
