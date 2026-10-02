-- A store manager reads the loading check of its own orders (Figma store manager
-- "06-5 Chilled order": "PKG-E · Short at loading").
--
--     The store counts what arrived. Showing it what the loader already flagged
--     at the dock stops it reporting the same shortage a second time, and lets it
--     see the custody chain of its own delivery (R-RCP-08). Until now Loading's
--     rows were visible to the depot and the process only, so the store's custody
--     read named the loading check as unavailable.
--
--     Read only, and narrow: the order's stop, its item lines, their checks and
--     their shortfalls, for an outlet in the actor's scope. Not the trip header,
--     the session or another outlet's stop. These policies are added beside the
--     depot policies (permissive policies are OR-ed) and grant no write.
--
--     No policy here refers back to the table it guards, so there is no
--     recursion: the child tables reach the stop, and the stop decides by its own
--     outlet column.

CREATE POLICY stops_outlet_read ON loading.stops
    FOR SELECT TO waypoint_loading
    USING (app.actor_has_outlet(outlet_id));

CREATE POLICY items_outlet_read ON loading.items
    FOR SELECT TO waypoint_loading
    USING (EXISTS (SELECT 1 FROM loading.stops s
                   WHERE s.trip_id = items.trip_id AND s.plan_version = items.plan_version
                     AND s.order_id = items.order_id AND app.actor_has_outlet(s.outlet_id)));

CREATE POLICY item_checks_outlet_read ON loading.item_checks
    FOR SELECT TO waypoint_loading
    USING (EXISTS (SELECT 1 FROM loading.stops s
                   WHERE s.trip_id = item_checks.trip_id AND s.plan_version = item_checks.plan_version
                     AND s.order_id = item_checks.order_id AND app.actor_has_outlet(s.outlet_id)));

CREATE POLICY shortfalls_outlet_read ON loading.shortfalls
    FOR SELECT TO waypoint_loading
    USING (EXISTS (SELECT 1 FROM loading.stops s
                   WHERE s.trip_id = shortfalls.trip_id AND s.plan_version = shortfalls.plan_version
                     AND s.order_id = shortfalls.order_id AND app.actor_has_outlet(s.outlet_id)));
