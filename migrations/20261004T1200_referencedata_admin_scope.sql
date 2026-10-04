-- Reference admin directories use the actor scope predicates in SQL.
-- The predicates run as waypoint_ref, so give that role only the two scope
-- tables they read. Row-level security still limits each read to the actor.

GRANT USAGE ON SCHEMA iam TO waypoint_ref;
GRANT SELECT ON iam.user_depot_access, iam.user_outlet_access TO waypoint_ref;

CREATE POLICY p_depot_access_self_ref ON iam.user_depot_access
    FOR SELECT TO waypoint_ref
    USING (user_id = app.current_actor());

CREATE POLICY p_outlet_access_self_ref ON iam.user_outlet_access
    FOR SELECT TO waypoint_ref
    USING (user_id = app.current_actor());
