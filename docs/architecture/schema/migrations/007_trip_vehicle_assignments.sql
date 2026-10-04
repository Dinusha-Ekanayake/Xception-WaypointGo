-- 007 Vehicle interchange as history
--    Swapping the truck at the dock must not be an UPDATE to
--    trips.vehicle_id: that loses who/when/why and skips revalidation,
--    which is how a chilled load ends up on an ambient truck.
--    trips.vehicle_id remains the CURRENT vehicle; this is the record.
-- ---------------------------------------------------------

CREATE TABLE IF NOT EXISTS ops.trip_vehicle_assignments (
    trip_vehicle_assignment_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    trip_id uuid NOT NULL REFERENCES ops.trips(trip_id) ON DELETE CASCADE,
    vehicle_id text NOT NULL REFERENCES ref.vehicles(vehicle_id),
    assigned_at timestamptz NOT NULL DEFAULT now(),
    superseded_at timestamptz,
    reason text NOT NULL,
    assigned_by uuid NOT NULL REFERENCES iam.users(user_id),
    revalidated boolean NOT NULL DEFAULT false,
    revalidation_detail jsonb,
    CONSTRAINT ck_interchange_reason CHECK (reason IN (
        'initial_plan','breakdown','workshop','unavailable','capacity_change','manual_override'
    )),
    -- an interchange may only be recorded after the WHOLE trip was re-checked
    CONSTRAINT ck_interchange_revalidated CHECK (reason = 'initial_plan' OR revalidated)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_one_current_vehicle_per_trip
    ON ops.trip_vehicle_assignments(trip_id) WHERE superseded_at IS NULL;

CREATE INDEX IF NOT EXISTS ix_trip_vehicle_assignments_vehicle
    ON ops.trip_vehicle_assignments(vehicle_id, assigned_at DESC);
