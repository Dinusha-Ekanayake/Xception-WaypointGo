-- The driver at the depot (R-LOD-12, R-EXE-24). Execution announces
-- vehicle.at_depot when the driver who drives the vehicle today says they have
-- collected it and are at the depot; Loading keeps the first report of the day,
-- so the dock board can show "Driver at the dock" and put that trip first for
-- the loader to take.
--
-- Written only by the system (the relay's consumer); read by the depot's
-- loaders and dispatchers through row-level security. Never deleted: an
-- operational record reaches no terminal state of its own.

CREATE TABLE loading.vehicle_arrivals (
    vehicle_id   text        NOT NULL REFERENCES ref.vehicle_registry (vehicle_id),
    service_date date        NOT NULL,
    depot_code   text        NOT NULL,
    driver_id    uuid        NOT NULL,
    arrived_at   timestamptz NOT NULL,
    PRIMARY KEY (vehicle_id, service_date)
);

COMMENT ON TABLE loading.vehicle_arrivals IS
  'When the vehicle''s driver reported at the depot, ready to load. The first report of the day is kept.';

CREATE INDEX ix_vehicle_arrivals_depot_day ON loading.vehicle_arrivals (depot_code, service_date);

GRANT SELECT, INSERT ON loading.vehicle_arrivals TO waypoint_loading;

ALTER TABLE loading.vehicle_arrivals ENABLE ROW LEVEL SECURITY;
ALTER TABLE loading.vehicle_arrivals FORCE ROW LEVEL SECURITY;

CREATE POLICY vehicle_arrivals_read ON loading.vehicle_arrivals
    FOR SELECT TO waypoint_loading
    USING (app.actor_is_system() OR app.actor_has_depot(depot_code));

CREATE POLICY vehicle_arrivals_record ON loading.vehicle_arrivals
    FOR INSERT TO waypoint_loading
    WITH CHECK (app.actor_is_system());
