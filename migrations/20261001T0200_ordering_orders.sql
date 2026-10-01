-- Ordering's tables (issue #8). Schema and role came with 20260930T1200.
--
--     Weight, volume, temperature and item count are the warehouse's answer to
--     POST /orders and are authoritative for capacity (D-E, R-ORD-12). They are
--     null exactly while no reservation exists: a stock_unknown order has no
--     measures yet (D-G), and the constraint says so rather than a zero that a
--     planner would happily fit onto a truck.
--
--     Nothing here is ever deleted; the role has no DELETE. An amendment writes
--     a new revision of the lines instead of replacing them, which keeps what
--     the store asked for first as well as what it asks for now.
--
--     References to other modules (trip_id, source_issue_id) are plain ids with
--     no foreign key (D-B). The outlet references the reference registry, which
--     is the one foreign key a module may hold outside its own schema.

CREATE TABLE ordering.orders (
    order_id                uuid PRIMARY KEY,
    order_ref               text        NOT NULL UNIQUE,
    outlet_id               text        NOT NULL REFERENCES ref.outlet_registry (outlet_id),
    depot_code              text        NOT NULL,
    brand_code              text        NOT NULL,
    district_name           text        NOT NULL,
    requested_date          date        NOT NULL,
    original_requested_date date        NOT NULL,
    delivery_date           date        NOT NULL,
    status                  text        NOT NULL,
    warehouse_order_ref     text,
    temperature             text,
    weight_kg               numeric(10,3),
    volume_m3               numeric(10,4),
    item_count              integer,
    line_revision           integer     NOT NULL DEFAULT 1,
    redelivery_of           uuid REFERENCES ordering.orders (order_id),
    source_issue_id         uuid UNIQUE,
    trip_id                 uuid,
    deferral_count          integer     NOT NULL DEFAULT 0,
    placed_by               uuid,
    placed_at               timestamptz NOT NULL,
    command_id              uuid,
    row_version             bigint      NOT NULL DEFAULT 1,
    updated_at              timestamptz NOT NULL,
    CONSTRAINT ck_orders_status CHECK (status IN (
        'stock_unknown','confirmed','allocated','deferred','unservable','loading',
        'in_transit','delivered','partially_delivered','failed','received',
        'unconfirmed','cancelled')),
    CONSTRAINT ck_orders_temperature CHECK (temperature IN ('chilled','ambient')),
    CONSTRAINT ck_orders_measures_positive CHECK (
        weight_kg > 0 AND volume_m3 > 0 AND item_count > 0),
    CONSTRAINT ck_orders_measures_with_reservation CHECK (
        (warehouse_order_ref IS NULL) = (weight_kg IS NULL)
        AND (warehouse_order_ref IS NULL) = (volume_m3 IS NULL)
        AND (warehouse_order_ref IS NULL) = (temperature IS NULL)
        AND (warehouse_order_ref IS NULL) = (item_count IS NULL)),
    -- R-STK-05: only these states may exist without stock actually reserved.
    CONSTRAINT ck_orders_reserved_when_planned CHECK (
        warehouse_order_ref IS NOT NULL OR status IN ('stock_unknown','deferred','cancelled')),
    CONSTRAINT ck_orders_deferrals CHECK (deferral_count >= 0),
    -- Dates only ever roll forward: cutoff, closure, calendar, deferral.
    CONSTRAINT ck_orders_dates CHECK (delivery_date >= requested_date)
);

COMMENT ON TABLE ordering.orders IS
  'One outlet''s demand for one delivery date. Status moves only along OrderStateMachine.';
COMMENT ON COLUMN ordering.orders.weight_kg IS
  'From the warehouse. Authoritative for capacity; never recomputed from lines (R-ORD-12).';

CREATE INDEX ix_orders_outlet_placed  ON ordering.orders (outlet_id, placed_at DESC, order_id DESC);
CREATE INDEX ix_orders_demand         ON ordering.orders (depot_code, delivery_date, status);
CREATE INDEX ix_orders_redelivery_of  ON ordering.orders (redelivery_of);
CREATE INDEX ix_orders_trip           ON ordering.orders (trip_id) WHERE trip_id IS NOT NULL;

CREATE TABLE ordering.order_lines (
    order_id   uuid    NOT NULL REFERENCES ordering.orders (order_id),
    revision   integer NOT NULL,
    product_id text    NOT NULL,
    quantity   integer NOT NULL,
    PRIMARY KEY (order_id, revision, product_id),
    CONSTRAINT ck_order_lines_quantity CHECK (quantity > 0)
);

COMMENT ON TABLE ordering.order_lines IS
  'Descriptive only. The current lines are those at orders.line_revision.';

CREATE TABLE ordering.order_status_history (
    history_id  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id    uuid        NOT NULL REFERENCES ordering.orders (order_id),
    from_status text,
    to_status   text        NOT NULL,
    reason      text        NOT NULL,
    actor_id    uuid,
    event_id    uuid,
    occurred_at timestamptz NOT NULL
);

COMMENT ON TABLE ordering.order_status_history IS
  'Every status change with its actor, reason and time (architecture rule 8). Null actor is the system.';

CREATE INDEX ix_order_status_history_order ON ordering.order_status_history (order_id, history_id);

CREATE TABLE ordering.day_closures (
    depot_code   text        NOT NULL,
    service_date date        NOT NULL,
    closed_at    timestamptz NOT NULL,
    closed_by    uuid,
    order_count  integer     NOT NULL,
    PRIMARY KEY (depot_code, service_date)
);

COMMENT ON TABLE ordering.day_closures IS
  'orders.closed happened for this depot and day. Amendments stop, new orders roll forward.';

-- ---- privileges ---------------------------------------------------------
-- Default privileges from 20260930T1200 already grant these; stated here so the
-- intent does not depend on who ran which migration.

GRANT SELECT, INSERT, UPDATE ON ordering.orders, ordering.order_lines,
                                ordering.order_status_history, ordering.day_closures
    TO waypoint_ordering;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA ordering TO waypoint_ordering;

-- ---- row-level security -------------------------------------------------
-- The first schema with rows worth scoping. Store managers see their outlets,
-- dispatchers their depots, the process itself everything in this schema. An
-- actor with no scope sees nothing, and FORCE applies it to the owner too.

ALTER TABLE ordering.orders ENABLE ROW LEVEL SECURITY;
ALTER TABLE ordering.orders FORCE ROW LEVEL SECURITY;
CREATE POLICY orders_scope ON ordering.orders
    FOR ALL TO waypoint_ordering
    USING (app.actor_is_system()
           OR app.actor_has_outlet(outlet_id)
           OR app.actor_has_depot(depot_code))
    WITH CHECK (app.actor_is_system()
                OR app.actor_has_outlet(outlet_id)
                OR app.actor_has_depot(depot_code));

-- Children follow their order. The subquery is itself filtered by orders_scope.
ALTER TABLE ordering.order_lines ENABLE ROW LEVEL SECURITY;
ALTER TABLE ordering.order_lines FORCE ROW LEVEL SECURITY;
CREATE POLICY order_lines_scope ON ordering.order_lines
    FOR ALL TO waypoint_ordering
    USING (EXISTS (SELECT 1 FROM ordering.orders o WHERE o.order_id = order_lines.order_id))
    WITH CHECK (EXISTS (SELECT 1 FROM ordering.orders o WHERE o.order_id = order_lines.order_id));

ALTER TABLE ordering.order_status_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE ordering.order_status_history FORCE ROW LEVEL SECURITY;
CREATE POLICY order_status_history_scope ON ordering.order_status_history
    FOR ALL TO waypoint_ordering
    USING (EXISTS (SELECT 1 FROM ordering.orders o WHERE o.order_id = order_status_history.order_id))
    WITH CHECK (EXISTS (SELECT 1 FROM ordering.orders o WHERE o.order_id = order_status_history.order_id));

ALTER TABLE ordering.day_closures ENABLE ROW LEVEL SECURITY;
ALTER TABLE ordering.day_closures FORCE ROW LEVEL SECURITY;
-- Whether a day is closed is not scoped: a store manager placing an order has
-- to know its date will roll, and has no depot scope to read it through.
-- Closing a day is scoped to the dispatcher's depots.
CREATE POLICY day_closures_read ON ordering.day_closures
    FOR SELECT TO waypoint_ordering
    USING (true);
CREATE POLICY day_closures_close ON ordering.day_closures
    FOR INSERT TO waypoint_ordering
    WITH CHECK (app.actor_is_system() OR app.actor_has_depot(depot_code));
