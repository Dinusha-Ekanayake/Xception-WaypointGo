-- 007 Row level security.
--
--     The second half of the composition rule. Policies decide which actions an
--     actor may take; these decide which rows they may see. Effective access is
--     the intersection, and a policy can never widen someone's reach past their
--     scope because the rows simply are not returned.
--
--     The actor is set per transaction by platform/db/Database:
--       SET LOCAL ROLE waypoint_<module>;
--       SELECT set_config('app.actor_id', '<uuid>', true);
--     SET LOCAL, never plain SET, or a pooled connection carries one request's
--     identity into the next borrower.
--
--     Scope of this migration: the helper predicates, and the scope tables that
--     ops will read. Full application to the operational tables lands with the
--     ops schema, because there is nothing else to scope yet. Login and session
--     resolution happen before an actor exists, so iam.users and iam.sessions
--     stay protected by the module role rather than by a row policy.
-- ---------------------------------------------------------------------

CREATE SCHEMA IF NOT EXISTS app;
GRANT USAGE ON SCHEMA app TO PUBLIC;

-- Null when no actor is set, which is how a trusted command such as migrate or
-- account-create runs. Every policy below treats null as "no rows", so an
-- unset actor fails closed rather than seeing everything.
CREATE OR REPLACE FUNCTION app.current_actor() RETURNS uuid
LANGUAGE sql STABLE AS $$
    SELECT NULLIF(current_setting('app.actor_id', true), '')::uuid
$$;

COMMENT ON FUNCTION app.current_actor() IS
  'The actor for this transaction, or null when none is set. Null means no rows.';

-- The two predicates every operational policy will be built from. Defining them
-- once means a change to how scope works is a change in one place, not in every
-- policy that happens to have copied the subquery.
CREATE OR REPLACE FUNCTION app.actor_has_depot(depot text) RETURNS boolean
LANGUAGE sql STABLE AS $$
    SELECT EXISTS (
        SELECT 1 FROM iam.user_depot_access
        WHERE user_id = app.current_actor() AND depot_code = depot
    )
$$;

CREATE OR REPLACE FUNCTION app.actor_has_outlet(outlet text) RETURNS boolean
LANGUAGE sql STABLE AS $$
    SELECT EXISTS (
        SELECT 1 FROM iam.user_outlet_access
        WHERE user_id = app.current_actor() AND outlet_id = outlet
    )
$$;

-- The operations module role, created here because it is the first consumer of
-- the scope tables. Its schema and grants arrive with the ops migration.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'waypoint_ops') THEN
        CREATE ROLE waypoint_ops NOLOGIN;
    END IF;
END $$;

ALTER ROLE waypoint_ops NOINHERIT NOLOGIN NOBYPASSRLS NOSUPERUSER;
GRANT waypoint_ops TO waypoint_app;

GRANT USAGE ON SCHEMA iam TO waypoint_ops;
GRANT SELECT ON iam.user_depot_access, iam.user_outlet_access TO waypoint_ops;

-- ---- scope tables -----------------------------------------------------
-- A table owner bypasses row level security by default, which would make every
-- policy below decorative. FORCE closes that.

ALTER TABLE iam.user_depot_access  ENABLE ROW LEVEL SECURITY;
ALTER TABLE iam.user_depot_access  FORCE ROW LEVEL SECURITY;
ALTER TABLE iam.user_outlet_access ENABLE ROW LEVEL SECURITY;
ALTER TABLE iam.user_outlet_access FORCE ROW LEVEL SECURITY;

-- Identity owns these tables and administers them, so it sees all rows. It is
-- reached only by the identity module, which is itself behind the policy engine.
CREATE POLICY depot_access_owner ON iam.user_depot_access
    FOR ALL TO waypoint_iam USING (true) WITH CHECK (true);

CREATE POLICY outlet_access_owner ON iam.user_outlet_access
    FOR ALL TO waypoint_iam USING (true) WITH CHECK (true);

-- Every other module sees only the current actor's own grants. A forgotten
-- WHERE clause in a future query therefore leaks nothing: the database has
-- already narrowed the rows.
CREATE POLICY depot_access_self ON iam.user_depot_access
    FOR SELECT TO waypoint_ops
    USING (user_id = app.current_actor());

CREATE POLICY outlet_access_self ON iam.user_outlet_access
    FOR SELECT TO waypoint_ops
    USING (user_id = app.current_actor());
