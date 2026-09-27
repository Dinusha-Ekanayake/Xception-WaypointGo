-- 004 Database roles.
--
--     One process, one pool, so module identity is set per transaction:
--
--       BEGIN;
--       SET LOCAL ROLE waypoint_ref;                -- which tables this code may touch
--       SET LOCAL app.actor_id = '<user uuid>';     -- which rows this person may see
--       ...
--       COMMIT;
--
--     waypoint_app is NOINHERIT and owns nothing, so forgetting SET LOCAL ROLE is
--     a permission error rather than a silent full-access query. That failure is
--     the point of the design.
--
--     Row-level policies arrive with the ops schema, where rows are scoped by
--     depot and outlet. In iam the protection is the role boundary itself: only
--     the identity module runs as waypoint_iam. A row policy on iam.users would
--     also have to be unwound for login, which happens before an actor exists.
--
--     Roles are cluster-wide, so attributes are asserted with ALTER ROLE rather
--     than assumed from CREATE ROLE, and the migration stays repeatable.
-- ---------------------------------------------------------------------

DO $$
DECLARE r text;
BEGIN
    FOREACH r IN ARRAY ARRAY['waypoint_app','waypoint_ref','waypoint_iam'] LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r) THEN
            EXECUTE format('CREATE ROLE %I NOLOGIN', r);
        END IF;
    END LOOP;
END $$;

ALTER ROLE waypoint_app NOINHERIT NOLOGIN NOBYPASSRLS NOSUPERUSER NOCREATEDB NOCREATEROLE;
ALTER ROLE waypoint_ref NOINHERIT NOLOGIN NOBYPASSRLS NOSUPERUSER;
ALTER ROLE waypoint_iam NOINHERIT NOLOGIN NOBYPASSRLS NOSUPERUSER;

-- waypoint_app may ADOPT a module role but, being NOINHERIT, holds none of their
-- privileges until it does.
GRANT waypoint_ref TO waypoint_app;
GRANT waypoint_iam TO waypoint_app;

-- ---- reference module -------------------------------------------------

GRANT USAGE ON SCHEMA ref TO waypoint_ref;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA ref TO waypoint_ref;
ALTER DEFAULT PRIVILEGES IN SCHEMA ref
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO waypoint_ref;

-- ---- identity module --------------------------------------------------

GRANT USAGE ON SCHEMA iam TO waypoint_iam;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA iam TO waypoint_iam;
ALTER DEFAULT PRIVILEGES IN SCHEMA iam
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO waypoint_iam;

-- Identity reads the reference kernel to validate scope targets: a depot grant
-- must name a depot that exists. It never writes there.
GRANT USAGE ON SCHEMA ref TO waypoint_iam;
GRANT SELECT ON ALL TABLES IN SCHEMA ref TO waypoint_iam;
ALTER DEFAULT PRIVILEGES IN SCHEMA ref GRANT SELECT ON TABLES TO waypoint_iam;

-- Reference must not read identity. The arrow points one way.
REVOKE ALL ON SCHEMA iam FROM waypoint_ref;
