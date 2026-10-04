-- Vehicle positions from the driver's phone (issue #161, R-EXE-18 to R-EXE-21).
--
--     Append only: the execution role may SELECT and INSERT, never UPDATE or
--     DELETE. Thinning after the approved retention window (service day plus
--     30 days, then one point per stop event) goes through a SECURITY DEFINER
--     function only the execution role may call, with the cutoff computed by
--     PositionRetentionJob from the operating clock, so the role never gains
--     DELETE and nothing else can thin a trail.
--
--     A row carries its depot, vehicle, service date and trip so row-level
--     security decides without a join to another module. A store manager sees
--     a trip's points only while that trip still has a pending or arrived stop
--     at one of their outlets (R-EXE-20).

CREATE TABLE execution.vehicle_positions (
    position_id  uuid          PRIMARY KEY,
    vehicle_id   text          NOT NULL REFERENCES ref.vehicle_registry (vehicle_id),
    depot_code   text          NOT NULL,
    service_date date          NOT NULL,
    trip_id      uuid          REFERENCES execution.trips (trip_id),
    driver_id    uuid          NOT NULL,
    recorded_at  timestamptz   NOT NULL,
    received_at  timestamptz   NOT NULL,
    latitude     numeric(9,6)  NOT NULL,
    longitude    numeric(9,6)  NOT NULL,
    accuracy_m   numeric(7,1),
    heading_deg  numeric(4,1),
    speed_kmh    numeric(5,1),
    low_quality  boolean       NOT NULL,
    command_id   uuid          NOT NULL,
    CONSTRAINT uq_positions_vehicle_instant UNIQUE (vehicle_id, recorded_at),
    CONSTRAINT ck_positions_lat CHECK (latitude BETWEEN 5.8 AND 9.9),
    CONSTRAINT ck_positions_lon CHECK (longitude BETWEEN 79.5 AND 82.0),
    CONSTRAINT ck_positions_heading CHECK (heading_deg IS NULL OR heading_deg BETWEEN 0 AND 359.9),
    CONSTRAINT ck_positions_sensor CHECK (
        (accuracy_m IS NULL OR accuracy_m >= 0) AND (speed_kmh IS NULL OR speed_kmh >= 0))
);

COMMENT ON TABLE execution.vehicle_positions IS
  'GPS fixes from the assigned driver''s phone. Personal data: never logged, thinned after service day plus 30 days.';
COMMENT ON COLUMN execution.vehicle_positions.recorded_at IS
  'The phone clock. Accepted up to five minutes ahead of received_at; older offline points are accepted (R-EXE-18).';
COMMENT ON COLUMN execution.vehicle_positions.low_quality IS
  'Accuracy worse than 200 m. Drawn faded and never used as last seen (R-EXE-19).';

CREATE INDEX ix_positions_vehicle_time ON execution.vehicle_positions (vehicle_id, recorded_at DESC);
CREATE INDEX ix_positions_trip_time    ON execution.vehicle_positions (trip_id, recorded_at);
CREATE INDEX ix_positions_depot_day    ON execution.vehicle_positions (depot_code, service_date);

GRANT SELECT, INSERT ON execution.vehicle_positions TO waypoint_execution;

ALTER TABLE execution.vehicle_positions ENABLE ROW LEVEL SECURITY;
ALTER TABLE execution.vehicle_positions FORCE ROW LEVEL SECURITY;

CREATE POLICY positions_read ON execution.vehicle_positions
    FOR SELECT TO waypoint_execution
    USING (app.actor_is_system() OR app.actor_drives(vehicle_id, service_date)
           OR app.actor_has_depot(depot_code)
           OR (trip_id IS NOT NULL AND EXISTS (
                 SELECT 1 FROM execution.delivery_records d
                  WHERE d.trip_id = vehicle_positions.trip_id
                    AND d.outcome IN ('pending', 'arrived')
                    AND app.actor_has_outlet(d.outlet_id))));

CREATE POLICY positions_insert ON execution.vehicle_positions
    FOR INSERT TO waypoint_execution
    WITH CHECK (app.actor_drives(vehicle_id, service_date) AND driver_id = app.current_actor());

-- The thinning function runs as the owner, which FORCE subjects to policies too.
CREATE POLICY positions_retention ON execution.vehicle_positions
    FOR ALL TO waypoint_migrator USING (true) WITH CHECK (true);
CREATE POLICY delivery_retention_read ON execution.delivery_records
    FOR SELECT TO waypoint_migrator USING (true);

-- R-EXE-21: keep one point per stop event (the fix nearest each arrival and
-- each completion) for days older than the window; remove the rest. Idempotent:
-- a second run keeps the same points. At most p_limit rows per call.
CREATE OR REPLACE FUNCTION execution.thin_vehicle_positions(p_before date, p_limit integer)
RETURNS integer
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    removed integer;
BEGIN
    IF p_limit IS NULL OR p_limit < 1 OR p_limit > 10000 THEN
        RAISE EXCEPTION 'retention batch % is outside 1-10000', p_limit;
    END IF;
    WITH events AS (
        SELECT d.trip_id, e.at
          FROM execution.delivery_records d
         CROSS JOIN LATERAL (VALUES (d.arrived_at), (d.completed_at)) AS e(at)
         WHERE e.at IS NOT NULL AND d.service_date < p_before
    ), kept AS (
        SELECT DISTINCT ON (ev.trip_id, ev.at) p.position_id
          FROM events ev
          JOIN execution.vehicle_positions p ON p.trip_id = ev.trip_id
         ORDER BY ev.trip_id, ev.at, abs(extract(epoch FROM p.recorded_at - ev.at)), p.position_id
    ), doomed AS (
        SELECT p.position_id
          FROM execution.vehicle_positions p
         WHERE p.service_date < p_before
           AND NOT EXISTS (SELECT 1 FROM kept k WHERE k.position_id = p.position_id)
         LIMIT p_limit
    )
    DELETE FROM execution.vehicle_positions p USING doomed
     WHERE p.position_id = doomed.position_id;
    GET DIAGNOSTICS removed = ROW_COUNT;
    RETURN removed;
END
$$;

REVOKE ALL ON FUNCTION execution.thin_vehicle_positions(date, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION execution.thin_vehicle_positions(date, integer) TO waypoint_execution;

-- Sync keeps each queued command as sent, points included. The same window
-- applies to that copy: the points array is removed, the envelope stays.
CREATE POLICY operations_retention ON sync.operations
    FOR ALL TO waypoint_migrator USING (true) WITH CHECK (true);

CREATE OR REPLACE FUNCTION sync.redact_position_payloads(p_before timestamptz, p_limit integer)
RETURNS integer
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    redacted integer;
BEGIN
    IF p_limit IS NULL OR p_limit < 1 OR p_limit > 10000 THEN
        RAISE EXCEPTION 'redaction batch % is outside 1-10000', p_limit;
    END IF;
    WITH due AS (
        SELECT operation_id FROM sync.operations
         WHERE kind = 'delivery:RecordPositions' AND received_at < p_before
           AND command #> '{payload,points}' IS NOT NULL
         LIMIT p_limit
    )
    UPDATE sync.operations o
       SET command = o.command #- '{payload,points}'
      FROM due WHERE o.operation_id = due.operation_id;
    GET DIAGNOSTICS redacted = ROW_COUNT;
    RETURN redacted;
END
$$;

REVOKE ALL ON FUNCTION sync.redact_position_payloads(timestamptz, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION sync.redact_position_payloads(timestamptz, integer) TO waypoint_sync;

INSERT INTO iam.action_catalogue (action, module, description, implemented) VALUES
    ('delivery:RecordPositions', 'execution', 'Record the vehicle''s GPS positions from the driver''s phone', true)
ON CONFLICT (action) DO UPDATE SET implemented = true;
