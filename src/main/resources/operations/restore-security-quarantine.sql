-- OFFLINE RECOVERY ONLY, not Flyway or application startup.
-- psql --no-psqlrc --set=ON_ERROR_STOP=1 --set=runtime_role=... \
--   --set=restore_confirmation=invalidate_uncertain_authority --file=...
-- Use the separately protected recovery identity against the isolated RESTORED
-- database. Stop every application/worker first. Never run for ordinary restart
-- or application rollback. CONNECT and external traffic remain quarantined.
BEGIN;
SELECT set_config('nkw.restore_confirmation', :'restore_confirmation', true);
SELECT set_config('nkw.restore_runtime_role', :'runtime_role', true);
DO $$
BEGIN
    IF current_setting('nkw.restore_confirmation') <> 'invalidate_uncertain_authority'
       OR current_user = current_setting('nkw.restore_runtime_role')
       OR has_database_privilege(current_setting('nkw.restore_runtime_role'),current_database(),'CONNECT')
    THEN RAISE EXCEPTION 'Restore maintenance quarantine required'; END IF;
END $$;

-- Identity owns these changes. Uncertain browser and one-use authority must
-- not revive from an older backup; no credentials/MFA configuration are reset.
DELETE FROM identity.spring_session;
UPDATE identity.identity_capability SET revoked_at=clock_timestamp()
WHERE consumed_at IS NULL AND superseded_at IS NULL AND revoked_at IS NULL;
UPDATE identity.security_email_delivery
SET state='obsolete',next_attempt_at=NULL,lease_owner=NULL,lease_token=NULL,lease_until=NULL,
    sealed_token_ciphertext=NULL,sealed_token_nonce=NULL,sealed_token_tag=NULL,token_key_version=NULL,
    sealed_recipient_ciphertext=NULL,sealed_recipient_nonce=NULL,sealed_recipient_tag=NULL,recipient_key_version=NULL,
    submitted_at=NULL,terminal_at=clock_timestamp(),updated_at=clock_timestamp(),last_failure_code='restore_uncertain_authority'
WHERE state IN ('queued','claimed','retry_wait');

-- Knowledge owns lease recovery. A fresh reclaim token and all existing
-- Account/source/AI/policy/generation gates are still mandatory. No work is
-- completed, no result is promoted and no provider call occurs here.
UPDATE knowledge.knowledge_work_intent SET lease_until=LEAST(lease_until,clock_timestamp()),
    updated_at=GREATEST(updated_at,clock_timestamp()) WHERE state='claimed';
COMMIT;

-- STOP: still no CONNECT grant or traffic reopening. Independently reconcile
-- post-backup erasure/suspension/denial evidence, active publication generation,
-- copied public/private object custody, keys and model lineages. Apply the
-- runtime-role template, validate exact Flyway checksums, then perform the
-- separately approved maintenance smoke before choosing to reopen traffic.
