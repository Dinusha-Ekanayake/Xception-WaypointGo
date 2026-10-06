-- Roadmap FF-06b: the vehicle's National Fuel Pass on the driver's phone.
--
-- The government issues one QR per vehicle and enforces the quota at the pump;
-- Waypoint only carries the official image so no paper copy is needed. The image
-- is a bearer credential: whoever holds it can draw that vehicle's fuel. So it is
-- uploaded only by an administrator, read only by the driver assigned to the
-- vehicle today or tomorrow, never shown to anyone else, and every read is audited.
--
-- One current pass per vehicle. Replacing a pass revokes the one before in the
-- same transaction; a pass is never deleted, it reaches the revoked state with
-- an actor, a reason and a time (decisions are recorded).

CREATE TABLE execution.fuel_passes (
    pass_id        uuid        PRIMARY KEY,
    vehicle_id     text        NOT NULL REFERENCES ref.vehicle_registry (vehicle_id),
    content        bytea       NOT NULL,
    content_type   text        NOT NULL,
    size_bytes     integer     NOT NULL,
    sha256         text        NOT NULL,
    uploaded_by    uuid        NOT NULL,
    uploaded_at    timestamptz NOT NULL,
    revoked_at     timestamptz,
    revoked_by     uuid,
    revoke_reason  text,
    row_version    bigint      NOT NULL DEFAULT 1,
    CONSTRAINT ck_fuel_passes_type CHECK (content_type IN ('image/png', 'image/jpeg', 'image/webp')),
    CONSTRAINT ck_fuel_passes_size CHECK (size_bytes > 0 AND size_bytes <= 524288),
    CONSTRAINT ck_fuel_passes_revoked CHECK (
        (revoked_at IS NULL) = (revoked_by IS NULL)
        AND (revoked_at IS NULL) = (revoke_reason IS NULL))
);

COMMENT ON TABLE execution.fuel_passes IS
  'The official National Fuel Pass QR per vehicle (FF-06b). A bearer credential: administrators write, the assigned driver reads, every read is audited. Revoked, never deleted.';

CREATE UNIQUE INDEX uq_fuel_passes_current ON execution.fuel_passes (vehicle_id) WHERE revoked_at IS NULL;
CREATE INDEX ix_fuel_passes_vehicle ON execution.fuel_passes (vehicle_id, uploaded_at);

-- Never DELETE: a pass is revoked.
GRANT SELECT, INSERT, UPDATE ON execution.fuel_passes TO waypoint_execution;

ALTER TABLE execution.fuel_passes ENABLE ROW LEVEL SECURITY;
ALTER TABLE execution.fuel_passes FORCE ROW LEVEL SECURITY;

-- A driver with a current or coming assignment to the vehicle, an administrator,
-- and the process itself. The application narrows a driver to today or tomorrow
-- by its own clock (time is a parameter, so the operating day is not read from
-- the database's); this policy keeps every other driver out even if it did not.
CREATE POLICY fuel_passes_read ON execution.fuel_passes
    FOR SELECT TO waypoint_execution
    USING (app.actor_is_system()
           OR app.actor_holds_role(ARRAY['admin'])
           OR EXISTS (SELECT 1 FROM iam.vehicle_driver_assignments a
                      WHERE a.driver_user_id = app.current_actor()
                        AND a.vehicle_id = fuel_passes.vehicle_id
                        AND a.validity && daterange(current_date - 1, NULL)));
CREATE POLICY fuel_passes_insert ON execution.fuel_passes
    FOR INSERT TO waypoint_execution
    WITH CHECK (app.actor_is_system() OR app.actor_holds_role(ARRAY['admin']));
CREATE POLICY fuel_passes_update ON execution.fuel_passes
    FOR UPDATE TO waypoint_execution
    USING (app.actor_is_system() OR app.actor_holds_role(ARRAY['admin']))
    WITH CHECK (app.actor_is_system() OR app.actor_holds_role(ARRAY['admin']));
