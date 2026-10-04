-- Simulated vehicles for the live demo (issue #231). Each row walks one
-- vehicle's released trip from the depot through its stops; every point is a
-- real delivery:RecordPositions command sent as that vehicle's assigned driver.
-- Additive: nothing outside the demo schema changes.
CREATE TABLE demo.simulations (
  id uuid PRIMARY KEY,
  vehicle_id text NOT NULL,
  service_date date NOT NULL,
  trip_id uuid,
  driver_user_id uuid NOT NULL,
  waypoints jsonb NOT NULL,
  tick integer NOT NULL DEFAULT 0 CHECK (tick >= 0),
  status text NOT NULL CHECK (status IN ('running', 'paused', 'stopped', 'finished', 'failed')),
  reason text NOT NULL,
  failure text,
  started_by uuid NOT NULL,
  started_at timestamptz NOT NULL DEFAULT now(),
  row_version bigint NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX demo_simulations_one_active ON demo.simulations (vehicle_id, service_date)
  WHERE status IN ('running', 'paused');
CREATE INDEX demo_simulations_cursor ON demo.simulations (started_at DESC, id DESC);
GRANT SELECT, INSERT, UPDATE ON demo.simulations TO waypoint_demo;
ALTER TABLE demo.simulations ENABLE ROW LEVEL SECURITY;
ALTER TABLE demo.simulations FORCE ROW LEVEL SECURITY;
CREATE POLICY demo_simulations ON demo.simulations TO waypoint_demo
  USING (app.actor_is_system() OR app.actor_holds_role(ARRAY['admin']))
  WITH CHECK (app.actor_is_system() OR app.actor_holds_role(ARRAY['admin']));

INSERT INTO iam.action_catalogue(action,module,description,implemented) VALUES
 ('demo:StartSimulation','demo','Start simulated vehicles on released trips',true),
 ('demo:ControlSimulations','demo','Pause, resume or stop simulated vehicles',true);
