-- Roadmap FF-02: a loader photographs a damaged or short line at the dock, the
-- "left the dock intact" evidence for a later dispute. The photo is stored like
-- a store's, for the order, but the loader holds the depot, not the outlet.
--
-- The insert rule widens to a loader of the order's depot. A driver also holds
-- depot scope and stays excluded: only the loader role is added (R-ISS-10).
GRANT EXECUTE ON FUNCTION app.actor_holds_role(text[]) TO waypoint_issues;

DROP POLICY IF EXISTS attachments_upload ON issues.attachments;
CREATE POLICY attachments_upload ON issues.attachments
    FOR INSERT TO waypoint_issues
    WITH CHECK (app.actor_is_system()
                OR app.actor_has_outlet(outlet_id)
                OR (app.actor_has_depot(depot_code) AND app.actor_holds_role(ARRAY['loader'])));
