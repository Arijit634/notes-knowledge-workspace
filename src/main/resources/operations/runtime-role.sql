-- Execute with the separately protected database owner/migration identity.
-- Supply an EXISTING non-superuser role with psql --set=runtime_role=... .
-- No password, user seeding, schema creation, default DDL or hidden ownership changes.
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public,identity,profile,notes,knowledge,publishing,discovery,moderation TO :"runtime_role";
REVOKE CREATE ON SCHEMA public,identity,profile,notes,knowledge,publishing,discovery,moderation FROM :"runtime_role";
GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA identity,profile,notes,knowledge,publishing,discovery,moderation TO :"runtime_role";
GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA identity,profile,notes,knowledge,publishing,discovery,moderation TO :"runtime_role";
GRANT SELECT ON public.flyway_schema_history TO :"runtime_role";
REVOKE INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER ON public.flyway_schema_history FROM :"runtime_role";
REVOKE UPDATE,DELETE,TRUNCATE ON identity.security_audit_fact,publishing.publication_audit_fact,moderation.moderation_decision,moderation.moderation_audit_fact FROM :"runtime_role";
REVOKE UPDATE,TRUNCATE ON notes.note_version FROM :"runtime_role";
-- NoteVersion DELETE is required for the Notes-owned unheld retention path;
-- restrictive version holds/current provenance still veto unsafe deletion.
