-- The last date outlook a store was warned of, per order and delivery day
-- (issue #224, R-ML-08). OutlookWatchJob reads it to warn once when a booked
-- day worsens, never again for the same or a milder status, so a day that
-- flips between busy and on track does not keep notifying. A deferral to
-- another day is a new key, so the new day is watched afresh.

CREATE TABLE ml.order_outlooks (
    order_id      uuid        NOT NULL,
    delivery_date date        NOT NULL,
    outlet_id     text        NOT NULL,
    depot_code    text        NOT NULL,
    status        text        NOT NULL CHECK (status IN ('BUSY', 'AT_RISK')),
    reason        text        NOT NULL,
    notified_at   timestamptz NOT NULL,
    PRIMARY KEY (order_id, delivery_date)
);

CREATE INDEX order_outlooks_depot_day ON ml.order_outlooks (depot_code, delivery_date);

GRANT SELECT, INSERT, UPDATE ON ml.order_outlooks TO waypoint_ml;

ALTER TABLE ml.order_outlooks ENABLE ROW LEVEL SECURITY;
ALTER TABLE ml.order_outlooks FORCE ROW LEVEL SECURITY;
CREATE POLICY order_outlooks_scope ON ml.order_outlooks
    FOR ALL TO waypoint_ml
    USING (app.actor_is_system() OR app.actor_has_depot(depot_code) OR app.actor_has_outlet(outlet_id))
    WITH CHECK (app.actor_is_system());
