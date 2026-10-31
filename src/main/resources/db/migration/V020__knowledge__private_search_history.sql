-- Separately approved, bounded owner history; operational work keeps its 24h contract.
ALTER TABLE knowledge.knowledge_work_intent ADD CONSTRAINT uq_work_history_owner
    UNIQUE (knowledge_work_intent_id, owner_user_id);
CREATE TABLE knowledge.search_history_entry (
    history_id uuid PRIMARY KEY CHECK (uuid_extract_version(history_id)=7),
    owner_user_id uuid NOT NULL REFERENCES identity.account(user_id) ON DELETE RESTRICT,
    work_id uuid,
    state text NOT NULL DEFAULT 'pending' CHECK (state IN ('pending','completed','failed','obsolete')),
    question_ciphertext bytea NOT NULL CHECK (octet_length(question_ciphertext) BETWEEN 17 AND 16400),
    question_nonce bytea NOT NULL CHECK (octet_length(question_nonce)=12),
    question_key_version text NOT NULL CHECK (question_key_version ~ '^[A-Za-z0-9_.-]{1,32}$'),
    result_ciphertext bytea,
    result_nonce bytea,
    result_key_version text,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    FOREIGN KEY (work_id,owner_user_id) REFERENCES knowledge.knowledge_work_intent(knowledge_work_intent_id,owner_user_id) ON DELETE RESTRICT,
    CHECK (expires_at>created_at AND expires_at<=created_at+interval '30 days'),
    CHECK ((state='completed' AND result_ciphertext IS NOT NULL AND result_nonce IS NOT NULL AND result_key_version IS NOT NULL
                AND octet_length(result_ciphertext) BETWEEN 17 AND 1048592 AND octet_length(result_nonce)=12
                AND result_key_version ~ '^[A-Za-z0-9_.-]{1,32}$')
        OR (state<>'completed' AND result_ciphertext IS NULL AND result_nonce IS NULL AND result_key_version IS NULL))
);
CREATE INDEX ix_search_history_owner_page ON knowledge.search_history_entry(owner_user_id,created_at DESC,history_id DESC);
CREATE INDEX ix_search_history_expiry ON knowledge.search_history_entry(expires_at,history_id);
CREATE UNIQUE INDEX ux_search_history_work ON knowledge.search_history_entry(work_id) WHERE work_id IS NOT NULL;
