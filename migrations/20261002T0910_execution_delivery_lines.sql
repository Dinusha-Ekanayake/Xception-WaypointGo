-- A delivery recorded product by product (decision 2026-10-01, issue #12).
--
--     The order's product lines are copied at release, beside the unit count,
--     so what a stop was held to never changes after the trip left. A partial
--     or full delivery may name what arrived of each product; delivered_units
--     stays null until it does. The record's delivered_units remains the total
--     Ordering and Warehouse read.

CREATE TABLE execution.delivery_lines (
    delivery_id     uuid    NOT NULL REFERENCES execution.delivery_records (delivery_id),
    product_id      text    NOT NULL,
    ordered_units   integer NOT NULL,
    delivered_units integer,
    PRIMARY KEY (delivery_id, product_id),
    CONSTRAINT ck_delivery_lines_ordered CHECK (ordered_units > 0),
    CONSTRAINT ck_delivery_lines_delivered CHECK (
        delivered_units IS NULL OR (delivered_units >= 0 AND delivered_units <= ordered_units))
);

COMMENT ON TABLE execution.delivery_lines IS
  'The order''s products at release, and what arrived of each. The product id is the warehouse''s inferred candidate, never a verified SKU.';

GRANT SELECT, INSERT, UPDATE ON execution.delivery_lines TO waypoint_execution;

-- Lines follow their delivery record: visible where it is visible, written by
-- whoever may write it.
ALTER TABLE execution.delivery_lines ENABLE ROW LEVEL SECURITY;
ALTER TABLE execution.delivery_lines FORCE ROW LEVEL SECURITY;
CREATE POLICY delivery_lines_read ON execution.delivery_lines
    FOR SELECT TO waypoint_execution
    USING (EXISTS (SELECT 1 FROM execution.delivery_records d WHERE d.delivery_id = delivery_lines.delivery_id));
CREATE POLICY delivery_lines_insert ON execution.delivery_lines
    FOR INSERT TO waypoint_execution
    WITH CHECK (app.actor_is_system());
CREATE POLICY delivery_lines_update ON execution.delivery_lines
    FOR UPDATE TO waypoint_execution
    USING (EXISTS (SELECT 1 FROM execution.delivery_records d
                   WHERE d.delivery_id = delivery_lines.delivery_id
                     AND (app.actor_is_system() OR app.actor_drives(d.vehicle_id, d.service_date))))
    WITH CHECK (EXISTS (SELECT 1 FROM execution.delivery_records d
                        WHERE d.delivery_id = delivery_lines.delivery_id
                          AND (app.actor_is_system() OR app.actor_drives(d.vehicle_id, d.service_date))));
