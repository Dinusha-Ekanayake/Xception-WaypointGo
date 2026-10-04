-- Schema per module (decision D-B, 2026-09-30).
--
-- ADR-002 first placed every operational module in one shared "ops" schema
-- under one waypoint_ops role. That made the per-module role meaningless: any
-- module could read any other module's tables, and a boundary violation would
-- only be caught in review. Each module now owns a schema and a role granted
-- that schema alone, so reading another module's tables is a permission error.
-- Modules refer to each other by id with no foreign key; consistency between
-- them comes from events. Foreign keys into ref and iam remain allowed.
--
-- This file creates the empty schemas and the roles. Each module creates its
-- own tables in its own migration.
--
-- Migration files from here on are named YYYYMMDDTHHMM_<module>_<what>.sql.
-- They sort after 001-009, and parallel branches never collide on a number.

-- ---- schemas -----------------------------------------------------------

CREATE SCHEMA IF NOT EXISTS ordering;
CREATE SCHEMA IF NOT EXISTS planning;
CREATE SCHEMA IF NOT EXISTS loading;
CREATE SCHEMA IF NOT EXISTS execution;
CREATE SCHEMA IF NOT EXISTS receipt;
CREATE SCHEMA IF NOT EXISTS issues;
CREATE SCHEMA IF NOT EXISTS notification;
CREATE SCHEMA IF NOT EXISTS sync;
CREATE SCHEMA IF NOT EXISTS warehouse;
CREATE SCHEMA IF NOT EXISTS ml;

-- ---- roles and grants --------------------------------------------------
-- Roles are cluster-wide, so a role may already exist from another database
-- on the same cluster. Attributes are asserted with ALTER ROLE rather than
-- assumed from CREATE ROLE.

DO $$
DECLARE
    m text;
    r text;
BEGIN
    FOREACH m IN ARRAY ARRAY['ordering','planning','loading','execution','receipt',
                             'issues','notification','sync','warehouse','ml'] LOOP
        r := 'waypoint_' || m;

        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r) THEN
            EXECUTE format('CREATE ROLE %I NOLOGIN', r);
        END IF;
        EXECUTE format('ALTER ROLE %I NOINHERIT NOLOGIN NOBYPASSRLS NOSUPERUSER', r);
        EXECUTE format('GRANT %I TO waypoint_app', r);

        -- Its own schema. No DELETE: operational records are never deleted,
        -- they reach terminal states (FOUNDATION-PLAN B5).
        EXECUTE format('GRANT USAGE ON SCHEMA %I TO %I', m, r);
        EXECUTE format('ALTER DEFAULT PRIVILEGES IN SCHEMA %I GRANT SELECT, INSERT, UPDATE ON TABLES TO %I', m, r);
        EXECUTE format('ALTER DEFAULT PRIVILEGES IN SCHEMA %I GRANT USAGE, SELECT ON SEQUENCES TO %I', m, r);

        -- Read on the reference kernel.
        EXECUTE format('GRANT USAGE ON SCHEMA ref TO %I', r);
        EXECUTE format('GRANT SELECT ON ALL TABLES IN SCHEMA ref TO %I', r);
        EXECUTE format('ALTER DEFAULT PRIVILEGES IN SCHEMA ref GRANT SELECT ON TABLES TO %I', r);

        -- The scope tables, which app.actor_has_depot and app.actor_has_outlet
        -- read as the invoking role. Row-level security limits each role to
        -- the actor's own grants, exactly as 007 does for waypoint_ops.
        EXECUTE format('GRANT USAGE ON SCHEMA iam TO %I', r);
        EXECUTE format('GRANT SELECT ON iam.user_depot_access, iam.user_outlet_access TO %I', r);
        EXECUTE format(
            'CREATE POLICY %I ON iam.user_depot_access FOR SELECT TO %I USING (user_id = app.current_actor())',
            'p_depot_access_self_' || m, r);
        EXECUTE format(
            'CREATE POLICY %I ON iam.user_outlet_access FOR SELECT TO %I USING (user_id = app.current_actor())',
            'p_outlet_access_self_' || m, r);

        -- Every command commits its receipt, its audit row and its events in the
        -- same transaction as the change, under the module's role.
        EXECUTE format('GRANT USAGE ON SCHEMA integration TO %I', r);
        EXECUTE format('GRANT INSERT ON integration.audit_log, integration.outbox_events TO %I', r);
        EXECUTE format('GRANT SELECT, INSERT ON integration.command_receipts TO %I', r);
    END LOOP;
END $$;

COMMENT ON ROLE waypoint_ops IS
  'Retired by 20260930T1200_platform_module_schemas: replaced by one role per module. Kept because roles are cluster-wide.';
