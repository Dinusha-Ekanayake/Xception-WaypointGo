-- Depot-wide reads of Execution are for the depot's staff, not its drivers
-- (issue #12, EXE-13).
--
--     The read policies of 20261002T0100 let anyone with a depot grant read
--     every vehicle's stops in that depot. Drivers hold depot grants too
--     (demo-accounts gives one), so a driver could read another driver's run
--     sheet and its recipients. A driver's scope is one vehicle on one date
--     (app.actor_drives); the depot branch now also asks for a role that
--     oversees the depot.
--
--     Module roles cannot read iam.user_roles, and should not. This function
--     answers one yes or no about the current actor's own roles and nothing
--     else, the same shape as app.policy_generation(). It reads only
--     iam.user_roles, which has no row-level security, so it answers the same
--     whether its owner is a superuser or not. The depot itself is still checked
--     by app.actor_has_depot, as the caller.

CREATE OR REPLACE FUNCTION app.actor_holds_role(roles text[]) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog AS $$
    SELECT EXISTS (
        SELECT 1 FROM iam.user_roles
        WHERE user_id = app.current_actor() AND role_code = ANY (roles)
    )
$$;

COMMENT ON FUNCTION app.actor_holds_role(text[]) IS
  'True when the current actor holds any of the roles. Says nothing about anyone else.';

REVOKE ALL ON FUNCTION app.actor_holds_role(text[]) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app.actor_holds_role(text[]) TO waypoint_execution;

CREATE OR REPLACE FUNCTION app.actor_oversees_depot(depot text) RETURNS boolean
LANGUAGE sql STABLE AS $$
    SELECT app.actor_has_depot(depot)
       AND app.actor_holds_role(ARRAY['dispatcher', 'admin', 'auditor'])
$$;

COMMENT ON FUNCTION app.actor_oversees_depot(text) IS
  'The depot is in the current actor''s scope and they hold a role that oversees it. A driver''s depot grant does not widen their scope.';

DROP POLICY trips_read ON execution.trips;
CREATE POLICY trips_read ON execution.trips
    FOR SELECT TO waypoint_execution
    USING (app.actor_is_system() OR app.actor_drives(vehicle_id, service_date)
           OR app.actor_oversees_depot(depot_code));

DROP POLICY delivery_read ON execution.delivery_records;
CREATE POLICY delivery_read ON execution.delivery_records
    FOR SELECT TO waypoint_execution
    USING (app.actor_is_system() OR app.actor_drives(vehicle_id, service_date)
           OR app.actor_oversees_depot(depot_code) OR app.actor_has_outlet(outlet_id));

DROP POLICY vehicle_reports_read ON execution.vehicle_reports;
CREATE POLICY vehicle_reports_read ON execution.vehicle_reports
    FOR SELECT TO waypoint_execution
    USING (app.actor_is_system() OR app.actor_drives(vehicle_id, service_date)
           OR app.actor_oversees_depot(depot_code));

DROP POLICY road_reports_read ON execution.road_reports;
CREATE POLICY road_reports_read ON execution.road_reports
    FOR SELECT TO waypoint_execution
    USING (app.actor_is_system() OR app.actor_drives(vehicle_id, service_date)
           OR app.actor_oversees_depot(depot_code));
