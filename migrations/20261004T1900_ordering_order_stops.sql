-- Issue #224: where a store's order stands on the published plan, so the store
-- sees "Planned for Fri · stop 3 · expected 06:10" on the order itself.
--
-- A projection Ordering keeps from plan.published and plan.revised (it already
-- consumes both to mark the order ALLOCATED): one row per order, rewritten when
-- a revision moves the stop. It is not the plan; Planning owns that. Shown only
-- while the order is allocated, loading or on the road, so a deferral needs no
-- delete: the status says the stop no longer holds.

CREATE TABLE ordering.order_stops (
    order_id        uuid        PRIMARY KEY REFERENCES ordering.orders (order_id),
    trip_id         uuid        NOT NULL,
    stop_sequence   integer     NOT NULL CHECK (stop_sequence > 0),
    planned_arrival time,
    service_date    date        NOT NULL,
    plan_version    integer     NOT NULL CHECK (plan_version > 0),
    updated_at      timestamptz NOT NULL
);

GRANT SELECT, INSERT, UPDATE ON ordering.order_stops TO waypoint_ordering;

-- Follows its order, like the other children of ordering.orders.
ALTER TABLE ordering.order_stops ENABLE ROW LEVEL SECURITY;
ALTER TABLE ordering.order_stops FORCE ROW LEVEL SECURITY;
CREATE POLICY order_stops_scope ON ordering.order_stops
    FOR ALL TO waypoint_ordering
    USING (EXISTS (SELECT 1 FROM ordering.orders o WHERE o.order_id = order_stops.order_id))
    WITH CHECK (EXISTS (SELECT 1 FROM ordering.orders o WHERE o.order_id = order_stops.order_id));

COMMENT ON TABLE ordering.order_stops IS
  'The stop and planned arrival of an allocated order, from plan.published and plan.revised (issue #224).';
