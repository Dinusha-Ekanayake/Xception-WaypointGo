-- Loading's tables (issue #10). Schema and role came with 20260930T1200.
--
--     Loading keeps its own copy of each published trip, built from
--     plan.published and plan.revised, so it never reads Planning's tables
--     (D-B). Every copy is tied to the plan version it came from. A revision
--     inserts a new version and marks the old one superseded; checks made
--     against the old version stop counting (R-LOD-03, R-LOD-07).
--
--     An order is loaded item line by item line (decision 2026-10-01). An item
--     line is one product and its unit count, copied from the order's lines.
--     Order-level weight and volume stay authoritative for capacity and are
--     copied from Ordering's contract, never summed from lines (R-ORD-12).
--
--     Checks are append only: a recheck or an undo is a new attempt, never an
--     overwrite. Nothing is deleted; the role has no DELETE.
--
--     One row_version per trip, on loading.sessions. Every accepted loading
--     command and every plan revision moves it on by exactly one, which the
--     loader client relies on when it queues writes offline.

CREATE TABLE loading.trips (
    trip_id           uuid        NOT NULL,
    plan_version      integer     NOT NULL,
    plan_id           uuid        NOT NULL,
    depot_code        text        NOT NULL,
    service_date      date        NOT NULL,
    vehicle_id        text        NOT NULL REFERENCES ref.vehicle_registry (vehicle_id),
    trip_number       integer     NOT NULL,
    trips_for_vehicle integer     NOT NULL,
    brand_code        text        NOT NULL,
    district_name     text        NOT NULL,
    temperature       text        NOT NULL,
    planned_departure time        NOT NULL,
    dock_code         text        NOT NULL,
    weight_cap_kg     numeric(10,2) NOT NULL,
    volume_cap_m3     numeric(10,3) NOT NULL,
    received_at       timestamptz NOT NULL,
    superseded_at     timestamptz,
    PRIMARY KEY (trip_id, plan_version),
    CONSTRAINT ck_trips_temperature CHECK (temperature IN ('chilled','ambient')),
    CONSTRAINT ck_trips_number CHECK (trip_number BETWEEN 1 AND trips_for_vehicle),
    CONSTRAINT ck_trips_caps CHECK (weight_cap_kg > 0 AND volume_cap_m3 > 0)
);

COMMENT ON TABLE loading.trips IS
  'A published trip as Loading received it, one row per plan version. The current row has superseded_at null.';

CREATE UNIQUE INDEX ux_trips_current ON loading.trips (trip_id) WHERE superseded_at IS NULL;
CREATE INDEX ix_trips_depot_day       ON loading.trips (depot_code, service_date) WHERE superseded_at IS NULL;
CREATE INDEX ix_trips_vehicle         ON loading.trips (vehicle_id);

CREATE TABLE loading.stops (
    trip_id         uuid          NOT NULL,
    plan_version    integer       NOT NULL,
    order_id        uuid          NOT NULL,
    stop_sequence   integer       NOT NULL,
    order_ref       text          NOT NULL,
    outlet_id       text          NOT NULL REFERENCES ref.outlet_registry (outlet_id),
    temperature     text          NOT NULL,
    item_count      integer       NOT NULL,
    weight_kg       numeric(10,3) NOT NULL,
    volume_m3       numeric(10,4) NOT NULL,
    planned_arrival time,
    PRIMARY KEY (trip_id, plan_version, order_id),
    FOREIGN KEY (trip_id, plan_version) REFERENCES loading.trips (trip_id, plan_version),
    CONSTRAINT ck_stops_temperature CHECK (temperature IN ('chilled','ambient')),
    CONSTRAINT ck_stops_measures CHECK (item_count > 0 AND weight_kg > 0 AND volume_m3 > 0),
    CONSTRAINT ck_stops_sequence CHECK (stop_sequence > 0)
);

COMMENT ON TABLE loading.stops IS
  'One order on one trip version, in delivery order. Measures copied from Ordering; authoritative for capacity.';

CREATE INDEX ix_stops_outlet ON loading.stops (outlet_id);
CREATE INDEX ix_stops_order  ON loading.stops (order_id);

CREATE TABLE loading.items (
    trip_id      uuid    NOT NULL,
    plan_version integer NOT NULL,
    order_id     uuid    NOT NULL,
    line_no      integer NOT NULL,
    product_id   text    NOT NULL,
    units        integer NOT NULL,
    PRIMARY KEY (trip_id, plan_version, order_id, line_no),
    FOREIGN KEY (trip_id, plan_version, order_id)
        REFERENCES loading.stops (trip_id, plan_version, order_id),
    CONSTRAINT ck_items_units CHECK (units > 0),
    CONSTRAINT ck_items_line CHECK (line_no > 0)
);

COMMENT ON TABLE loading.items IS
  'The item lines of an order: one product and its unit count. Descriptive; the product id is inferred, never a verified SKU.';

CREATE TABLE loading.sessions (
    trip_id        uuid        PRIMARY KEY,
    depot_code     text        NOT NULL,
    plan_version   integer     NOT NULL,
    status         text        NOT NULL DEFAULT 'not_started',
    holder_user_id uuid,
    holder_name    text,
    holder_code    text,
    held_since     timestamptz,
    started_at     timestamptz,
    released_at    timestamptz,
    released_by    uuid,
    seal_number    text,
    reefer_temp_c  numeric(4,1),
    row_version    bigint      NOT NULL DEFAULT 1,
    updated_at     timestamptz NOT NULL,
    CONSTRAINT ck_sessions_status CHECK (status IN ('not_started','in_progress','released')),
    CONSTRAINT ck_sessions_holder CHECK ((holder_user_id IS NULL) = (held_since IS NULL)),
    CONSTRAINT ck_sessions_released CHECK (
        (status = 'released') = (released_at IS NOT NULL)
        AND (status <> 'released' OR (released_by IS NOT NULL AND seal_number IS NOT NULL
                                      AND holder_user_id IS NULL)))
);

COMMENT ON TABLE loading.sessions IS
  'The dock work on one trip. row_version moves on by one per accepted command and per plan revision.';

CREATE INDEX ix_sessions_depot  ON loading.sessions (depot_code);
CREATE INDEX ix_sessions_holder ON loading.sessions (holder_user_id) WHERE holder_user_id IS NOT NULL;

CREATE TABLE loading.item_checks (
    check_id           uuid        PRIMARY KEY,
    trip_id            uuid        NOT NULL,
    plan_version       integer     NOT NULL,
    order_id           uuid        NOT NULL,
    line_no            integer     NOT NULL,
    attempt            integer     NOT NULL,
    status             text        NOT NULL,
    units              integer     NOT NULL,
    reason             text,
    shortfall_id       uuid,
    actor_user_id      uuid        NOT NULL,
    device_id          uuid,
    command_id         uuid        NOT NULL,
    recorded_at        timestamptz NOT NULL,
    client_recorded_at timestamptz,
    FOREIGN KEY (trip_id, plan_version, order_id, line_no)
        REFERENCES loading.items (trip_id, plan_version, order_id, line_no),
    CONSTRAINT uq_item_checks_attempt UNIQUE (trip_id, plan_version, order_id, line_no, attempt),
    CONSTRAINT ck_item_checks_status CHECK (status IN (
        'pending','loaded','missing','damaged','short','does_not_fit')),
    CONSTRAINT ck_item_checks_attempt CHECK (attempt > 0),
    CONSTRAINT ck_item_checks_units CHECK (units >= 0),
    CONSTRAINT ck_item_checks_flag_reason CHECK (
        status IN ('pending','loaded') OR (reason IS NOT NULL AND shortfall_id IS NOT NULL))
);

COMMENT ON TABLE loading.item_checks IS
  'Append only. The latest attempt per item line is its state; earlier attempts are the custody record.';

CREATE INDEX ix_item_checks_actor ON loading.item_checks (actor_user_id, recorded_at);

CREATE TABLE loading.shortfalls (
    shortfall_id        uuid        PRIMARY KEY,
    trip_id             uuid        NOT NULL,
    plan_version        integer     NOT NULL,
    order_id            uuid        NOT NULL,
    line_no             integer,
    kind                text        NOT NULL,
    missing_units       integer     NOT NULL,
    reason              text        NOT NULL,
    photo_attachment_id uuid,
    reported_by         uuid        NOT NULL,
    device_id           uuid,
    reported_at         timestamptz NOT NULL,
    resolved_at         timestamptz,
    resolution          text,
    FOREIGN KEY (trip_id, plan_version, order_id)
        REFERENCES loading.stops (trip_id, plan_version, order_id),
    CONSTRAINT ck_shortfalls_kind CHECK (kind IN ('missing','damaged','short','does_not_fit')),
    CONSTRAINT ck_shortfalls_units CHECK (missing_units > 0),
    CONSTRAINT ck_shortfalls_resolution CHECK ((resolved_at IS NULL) = (resolution IS NULL))
);

COMMENT ON TABLE loading.shortfalls IS
  'An item flagged before departure. Not loaded; the dispatcher and store are told (loading.shortfall). Never deleted.';

CREATE INDEX ix_shortfalls_trip ON loading.shortfalls (trip_id, plan_version, order_id);
CREATE INDEX ix_shortfalls_open ON loading.shortfalls (trip_id) WHERE resolved_at IS NULL;

ALTER TABLE loading.item_checks
    ADD CONSTRAINT fk_item_checks_shortfall FOREIGN KEY (shortfall_id) REFERENCES loading.shortfalls (shortfall_id);
CREATE INDEX ix_item_checks_shortfall ON loading.item_checks (shortfall_id) WHERE shortfall_id IS NOT NULL;

-- ---- privileges ---------------------------------------------------------

GRANT SELECT, INSERT, UPDATE ON loading.trips, loading.stops, loading.items, loading.sessions,
                                loading.item_checks, loading.shortfalls
    TO waypoint_loading;

-- ---- row-level security -------------------------------------------------
-- Loaders and dispatchers see their depots; the process itself sees all of
-- this schema. An actor with no scope sees nothing, and FORCE applies it to
-- the owner too. Children follow their trip.

ALTER TABLE loading.trips ENABLE ROW LEVEL SECURITY;
ALTER TABLE loading.trips FORCE ROW LEVEL SECURITY;
CREATE POLICY trips_scope ON loading.trips
    FOR ALL TO waypoint_loading
    USING (app.actor_is_system() OR app.actor_has_depot(depot_code))
    WITH CHECK (app.actor_is_system() OR app.actor_has_depot(depot_code));

ALTER TABLE loading.sessions ENABLE ROW LEVEL SECURITY;
ALTER TABLE loading.sessions FORCE ROW LEVEL SECURITY;
CREATE POLICY sessions_scope ON loading.sessions
    FOR ALL TO waypoint_loading
    USING (app.actor_is_system() OR app.actor_has_depot(depot_code))
    WITH CHECK (app.actor_is_system() OR app.actor_has_depot(depot_code));

ALTER TABLE loading.stops ENABLE ROW LEVEL SECURITY;
ALTER TABLE loading.stops FORCE ROW LEVEL SECURITY;
CREATE POLICY stops_scope ON loading.stops
    FOR ALL TO waypoint_loading
    USING (EXISTS (SELECT 1 FROM loading.trips t
                   WHERE t.trip_id = stops.trip_id AND t.plan_version = stops.plan_version))
    WITH CHECK (EXISTS (SELECT 1 FROM loading.trips t
                        WHERE t.trip_id = stops.trip_id AND t.plan_version = stops.plan_version));

ALTER TABLE loading.items ENABLE ROW LEVEL SECURITY;
ALTER TABLE loading.items FORCE ROW LEVEL SECURITY;
CREATE POLICY items_scope ON loading.items
    FOR ALL TO waypoint_loading
    USING (EXISTS (SELECT 1 FROM loading.trips t
                   WHERE t.trip_id = items.trip_id AND t.plan_version = items.plan_version))
    WITH CHECK (EXISTS (SELECT 1 FROM loading.trips t
                        WHERE t.trip_id = items.trip_id AND t.plan_version = items.plan_version));

ALTER TABLE loading.item_checks ENABLE ROW LEVEL SECURITY;
ALTER TABLE loading.item_checks FORCE ROW LEVEL SECURITY;
CREATE POLICY item_checks_scope ON loading.item_checks
    FOR ALL TO waypoint_loading
    USING (EXISTS (SELECT 1 FROM loading.sessions s WHERE s.trip_id = item_checks.trip_id))
    WITH CHECK (EXISTS (SELECT 1 FROM loading.sessions s WHERE s.trip_id = item_checks.trip_id));

ALTER TABLE loading.shortfalls ENABLE ROW LEVEL SECURITY;
ALTER TABLE loading.shortfalls FORCE ROW LEVEL SECURITY;
CREATE POLICY shortfalls_scope ON loading.shortfalls
    FOR ALL TO waypoint_loading
    USING (EXISTS (SELECT 1 FROM loading.sessions s WHERE s.trip_id = shortfalls.trip_id))
    WITH CHECK (EXISTS (SELECT 1 FROM loading.sessions s WHERE s.trip_id = shortfalls.trip_id));
