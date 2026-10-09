CREATE SCHEMA IF NOT EXISTS moderation;

CREATE TABLE moderation.report (
    report_id uuid PRIMARY KEY,
    publication_id uuid NOT NULL REFERENCES publishing.publication(publication_id) ON DELETE RESTRICT,
    publication_generation bigint NOT NULL CHECK (publication_generation > 0),
    reporter_user_id uuid REFERENCES identity.account(user_id) ON DELETE RESTRICT,
    category text NOT NULL CHECK (category IN ('harmfulContent','spam','harassment','other')),
    description text NOT NULL CHECK (char_length(description) BETWEEN 1 AND 2000 AND description !~ '[[:cntrl:]]'),
    state text NOT NULL CHECK (state IN ('open','under_review','dismissed','actioned','closed')),
    submitted_at timestamptz NOT NULL,
    reviewed_at timestamptz,
    resolved_at timestamptz,
    UNIQUE (report_id,publication_id),
    CHECK (reviewed_at IS NULL OR reviewed_at >= submitted_at),
    CHECK (resolved_at IS NULL OR resolved_at >= COALESCE(reviewed_at,submitted_at)),
    CHECK ((state='open' AND reviewed_at IS NULL AND resolved_at IS NULL)
        OR (state='under_review' AND reviewed_at IS NOT NULL AND resolved_at IS NULL)
        OR (state IN ('dismissed','actioned') AND reviewed_at IS NOT NULL AND resolved_at IS NOT NULL)
        OR (state='closed' AND reviewed_at IS NULL AND resolved_at IS NOT NULL))
);
CREATE UNIQUE INDEX ux_report_open_reporter ON moderation.report(publication_id,publication_generation,reporter_user_id)
    WHERE state IN ('open','under_review');
CREATE INDEX ix_report_queue ON moderation.report(state,submitted_at DESC,report_id DESC);
CREATE INDEX ix_report_category_queue ON moderation.report(category,state,submitted_at DESC,report_id DESC);
CREATE INDEX ix_report_publication ON moderation.report(publication_id,submitted_at DESC);

CREATE TABLE moderation.moderation_decision (
    moderation_decision_id uuid PRIMARY KEY,
    report_id uuid NOT NULL UNIQUE,
    publication_id uuid NOT NULL,
    actor_user_id uuid NOT NULL REFERENCES identity.account(user_id) ON DELETE RESTRICT,
    outcome_code text NOT NULL CHECK (outcome_code IN ('dismissed','actioned')),
    consequence text NOT NULL CHECK (consequence IN ('none','removePublication','removePublicationAndSuspendResponsibleAccount')),
    reason_code text NOT NULL CHECK (reason_code IN ('noPolicyViolation','insufficientEvidence','confirmedPolicyViolation')),
    evidence_generation bigint NOT NULL CHECK (evidence_generation > 0),
    correlation_id uuid NOT NULL,
    occurred_at timestamptz NOT NULL,
    FOREIGN KEY (report_id,publication_id) REFERENCES moderation.report(report_id,publication_id) ON DELETE RESTRICT,
    UNIQUE (moderation_decision_id,report_id,publication_id),
    CHECK ((outcome_code='dismissed' AND consequence='none' AND reason_code IN ('noPolicyViolation','insufficientEvidence'))
        OR (outcome_code='actioned' AND consequence<>'none' AND reason_code='confirmedPolicyViolation'))
);
CREATE TABLE moderation.moderation_audit_fact (
    audit_fact_id uuid PRIMARY KEY,
    report_id uuid NOT NULL,
    publication_id uuid NOT NULL,
    moderation_decision_id uuid,
    actor_user_id uuid NOT NULL REFERENCES identity.account(user_id) ON DELETE RESTRICT,
    action_code text NOT NULL CHECK (action_code IN ('begin_review','close_unavailable','decision','public_removal','account_suspension','denied')),
    outcome_code text NOT NULL CHECK (outcome_code IN ('committed','denied')),
    reason_code text NOT NULL CHECK (reason_code IN ('review_started','target_unavailable','noPolicyViolation','insufficientEvidence','confirmedPolicyViolation','capability_required')),
    correlation_id uuid NOT NULL,
    occurred_at timestamptz NOT NULL,
    FOREIGN KEY (report_id,publication_id) REFERENCES moderation.report(report_id,publication_id) ON DELETE RESTRICT,
    FOREIGN KEY (moderation_decision_id,report_id,publication_id)
        REFERENCES moderation.moderation_decision(moderation_decision_id,report_id,publication_id) ON DELETE RESTRICT
);
CREATE INDEX ix_moderation_audit_report ON moderation.moderation_audit_fact(report_id,occurred_at,audit_fact_id);

CREATE FUNCTION moderation.guard_report_transition() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.report_id IS DISTINCT FROM OLD.report_id OR NEW.publication_id IS DISTINCT FROM OLD.publication_id
       OR NEW.publication_generation IS DISTINCT FROM OLD.publication_generation
       OR NEW.reporter_user_id IS DISTINCT FROM OLD.reporter_user_id OR NEW.category IS DISTINCT FROM OLD.category
       OR NEW.description IS DISTINCT FROM OLD.description OR NEW.submitted_at IS DISTINCT FROM OLD.submitted_at
       OR NOT ((OLD.state='open' AND NEW.state IN ('under_review','closed'))
          OR (OLD.state='under_review' AND NEW.state IN ('dismissed','actioned') AND NEW.reviewed_at=OLD.reviewed_at)) THEN
        RAISE EXCEPTION 'Invalid report transition' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER trg_report_transition BEFORE UPDATE ON moderation.report FOR EACH ROW EXECUTE FUNCTION moderation.guard_report_transition();

-- Deployment grants the non-owner runtime INSERT/SELECT only on protected facts,
-- never UPDATE/DELETE/TRUNCATE or membership in the DDL/maintenance role.
-- A future retention purge requires separately authorized maintenance authority.
REVOKE UPDATE, DELETE, TRUNCATE ON moderation.moderation_decision,moderation.moderation_audit_fact FROM PUBLIC;
