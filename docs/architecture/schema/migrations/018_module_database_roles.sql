-- 018 A database role per module
--     Schema separation only bites if something enforces it. Code review
--     and ModuleBoundaryTest catch a cross-module import; neither catches
--     a cross-module SQL query written inside the right module.
--     Grants do.
--
--     One process, one pool, so the enforcement is per transaction:
--
--         BEGIN;
--         SET LOCAL ROLE waypoint_ops;                -- module identity
--         SET LOCAL app.actor_id = '<uuid>';          -- row identity
--         ...
--         COMMIT;
--
--     waypoint_app is NOINHERIT and a member of each module role, so it
--     holds no privilege until it explicitly assumes one. Forgetting the
--     SET LOCAL ROLE is a permission error, not a silent full-access query.
--     Fail closed, same principle as the unset actor.
-- ---------------------------------------------------------

-- Connection role. Owns nothing. Never BYPASSRLS. NOINHERIT is the point.
DO $$ BEGIN
    CREATE ROLE waypoint_app NOINHERIT NOLOGIN;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

-- Roles are cluster-wide, so CREATE may be skipped for a role that already
-- exists with the WRONG attributes. Creating it is not enough; assert them.
-- An inheriting waypoint_app silently holds every module privilege at once,
-- which defeats the entire point of this migration.
ALTER ROLE waypoint_app NOINHERIT NOLOGIN NOBYPASSRLS NOSUPERUSER;

DO $$
DECLARE r text;
BEGIN
    FOREACH r IN ARRAY ARRAY['waypoint_ref','waypoint_iam','waypoint_ops',
                             'waypoint_ml','waypoint_integration']
    LOOP
        BEGIN
            EXECUTE format('CREATE ROLE %I NOLOGIN', r);
        EXCEPTION WHEN duplicate_object THEN NULL;
        END;
        EXECUTE format('ALTER ROLE %I NOLOGIN NOBYPASSRLS NOSUPERUSER', r);
        EXECUTE format('GRANT %I TO waypoint_app', r);
    END LOOP;
END $$;

-- ref and iam are the shared kernel: every module reads them, no module
-- writes them except their owner. This mirrors the dependency rules in
-- SYSTEM-ARCHITECTURE.md exactly, one layer lower down.
GRANT USAGE ON SCHEMA ref, iam TO
    waypoint_ref, waypoint_iam, waypoint_ops, waypoint_ml, waypoint_integration;
GRANT SELECT ON ALL TABLES IN SCHEMA ref, iam TO
    waypoint_ref, waypoint_iam, waypoint_ops, waypoint_ml, waypoint_integration;
ALTER DEFAULT PRIVILEGES IN SCHEMA ref, iam
    GRANT SELECT ON TABLES TO
    waypoint_ref, waypoint_iam, waypoint_ops, waypoint_ml, waypoint_integration;

-- Each module writes only its own schema.
GRANT USAGE ON SCHEMA ref TO waypoint_ref;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA ref TO waypoint_ref;
ALTER DEFAULT PRIVILEGES IN SCHEMA ref GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO waypoint_ref;

GRANT USAGE ON SCHEMA iam TO waypoint_iam;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA iam TO waypoint_iam;
ALTER DEFAULT PRIVILEGES IN SCHEMA iam GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO waypoint_iam;

GRANT USAGE ON SCHEMA ops TO waypoint_ops;
GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA ops TO waypoint_ops;
ALTER DEFAULT PRIVILEGES IN SCHEMA ops GRANT SELECT, INSERT, UPDATE ON TABLES TO waypoint_ops;

GRANT USAGE ON SCHEMA ml TO waypoint_ml;
GRANT SELECT, INSERT ON ALL TABLES IN SCHEMA ml TO waypoint_ml;
ALTER DEFAULT PRIVILEGES IN SCHEMA ml GRANT SELECT, INSERT ON TABLES TO waypoint_ml;

GRANT USAGE ON SCHEMA integration TO waypoint_integration;
GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA integration TO waypoint_integration;
ALTER DEFAULT PRIVILEGES IN SCHEMA integration GRANT SELECT, INSERT, UPDATE ON TABLES TO waypoint_integration;

-- Every module writes the outbox and the audit log, by design: state
-- change, event and audit row commit in the same transaction.
GRANT USAGE ON SCHEMA integration TO waypoint_ops, waypoint_iam, waypoint_ref, waypoint_ml;
GRANT INSERT ON integration.outbox_events, integration.audit_log TO
    waypoint_ops, waypoint_iam, waypoint_ref, waypoint_ml;
GRANT SELECT, INSERT ON integration.command_receipts TO waypoint_ops, waypoint_iam;

-- Sequences used by IDENTITY columns.
GRANT USAGE ON ALL SEQUENCES IN SCHEMA ref, iam, ops, ml, integration TO
    waypoint_ref, waypoint_iam, waypoint_ops, waypoint_ml, waypoint_integration;

-- Deliberately NOT granted anywhere:
--   DELETE on ops.*            operational records reach terminal states
--   UPDATE/DELETE on audit_log append only
--   BYPASSRLS on any role      it would make every policy in 016 a no-op
--
-- The migration owner is a separate role that owns the tables and runs
-- migrations. It is never the role the connection pool uses.
