-- Runtime demo state is opt-in. Operational rows remain in their owning modules.
CREATE SCHEMA demo;
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'waypoint_demo') THEN
    CREATE ROLE waypoint_demo NOLOGIN NOINHERIT;
  END IF;
END $$;
ALTER ROLE waypoint_demo NOLOGIN NOINHERIT;
GRANT waypoint_demo TO waypoint_app;
GRANT USAGE ON SCHEMA demo, ref, iam, integration TO waypoint_demo;
GRANT SELECT ON ALL TABLES IN SCHEMA ref TO waypoint_demo;
GRANT SELECT ON iam.user_depot_access, iam.user_outlet_access, iam.vehicle_driver_assignments TO waypoint_demo;
CREATE POLICY demo_depot_scope ON iam.user_depot_access FOR SELECT TO waypoint_demo USING (user_id = app.current_actor());
CREATE POLICY demo_outlet_scope ON iam.user_outlet_access FOR SELECT TO waypoint_demo USING (user_id = app.current_actor());
CREATE POLICY demo_driver_scope ON iam.vehicle_driver_assignments FOR SELECT TO waypoint_demo USING (driver_user_id = app.current_actor());
GRANT INSERT ON integration.audit_log, integration.outbox_events TO waypoint_demo;
GRANT SELECT, INSERT ON integration.command_receipts, integration.consumed_events TO waypoint_demo;

CREATE TABLE demo.settings (
  id boolean PRIMARY KEY DEFAULT true CHECK (id),
  enabled boolean NOT NULL DEFAULT false,
  clock_offset_seconds bigint NOT NULL DEFAULT 0 CHECK (clock_offset_seconds BETWEEN -604800 AND 604800),
  sim_point_interval_ms integer NOT NULL DEFAULT 2000 CHECK (sim_point_interval_ms BETWEEN 500 AND 10000),
  position_flush_ms integer NOT NULL DEFAULT 5000 CHECK (position_flush_ms BETWEEN 1000 AND 60000),
  banner boolean NOT NULL DEFAULT true,
  speed integer NOT NULL DEFAULT 10 CHECK (speed BETWEEN 1 AND 60),
  row_version bigint NOT NULL DEFAULT 0,
  updated_by uuid,
  updated_at timestamptz NOT NULL DEFAULT now(),
  CHECK (enabled OR clock_offset_seconds = 0)
);
INSERT INTO demo.settings(id) VALUES (true);
CREATE TABLE demo.scenario_runs (
  id uuid PRIMARY KEY,
  scenario_key text NOT NULL,
  actor uuid NOT NULL,
  reason text NOT NULL,
  started_at timestamptz NOT NULL DEFAULT now(),
  outcome text NOT NULL,
  details jsonb NOT NULL DEFAULT '{}',
  row_version bigint NOT NULL DEFAULT 0
);
CREATE INDEX demo_runs_cursor ON demo.scenario_runs (started_at DESC, id DESC);
GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA demo TO waypoint_demo;
ALTER TABLE demo.settings ENABLE ROW LEVEL SECURITY;
ALTER TABLE demo.settings FORCE ROW LEVEL SECURITY;
CREATE POLICY demo_settings_read ON demo.settings FOR SELECT TO waypoint_demo USING (true);
CREATE POLICY demo_settings_write ON demo.settings FOR UPDATE TO waypoint_demo
  USING (app.actor_holds_role(ARRAY['admin'])) WITH CHECK (app.actor_holds_role(ARRAY['admin']));
ALTER TABLE demo.scenario_runs ENABLE ROW LEVEL SECURITY;
ALTER TABLE demo.scenario_runs FORCE ROW LEVEL SECURITY;
CREATE POLICY demo_runs ON demo.scenario_runs TO waypoint_demo
  USING (app.actor_is_system() OR app.actor_holds_role(ARRAY['admin']))
  WITH CHECK (app.actor_is_system() OR app.actor_holds_role(ARRAY['admin']));
INSERT INTO iam.action_catalogue(action,module,description,implemented)
SELECT 'demo:' || action, 'demo', description, true FROM (VALUES
 ('Read','Read operational demo state'), ('Manage','Read the demo control room'),
 ('Enable','Enable demo mode'), ('Disable','Disable demo mode'), ('SetClock','Set demo business time'),
 ('UpdateSettings','Update demo settings'), ('ResetDay','Prepare a new demo day')
) AS actions(action,description);
CREATE TEMP TABLE demo_policy_docs ON COMMIT DROP AS
SELECT p.policy_id, jsonb_set(v.document, '{Statement}', (v.document->'Statement') ||
 jsonb_build_array(jsonb_build_object('Sid','DemoRuntime','Effect','Allow',
 'Action', CASE WHEN p.name = 'WaypointAdministrator' THEN '["demo:*"]'::jsonb ELSE '["demo:Read"]'::jsonb END,
 'Resource','["wpt:demo:*"]'::jsonb))) AS document
FROM iam.policies p JOIN iam.policy_versions v ON p.policy_id=v.policy_id AND v.is_default
WHERE p.name IN ('WaypointAdministrator','WaypointDispatcher','WaypointLoader','WaypointDriver','WaypointStoreManager','WaypointAuditor');
UPDATE iam.policy_versions v SET is_default=false FROM demo_policy_docs d WHERE v.policy_id=d.policy_id AND v.is_default;
INSERT INTO iam.policy_versions(policy_id,version_number,document,is_default)
SELECT d.policy_id,(SELECT max(version_number)+1 FROM iam.policy_versions WHERE policy_id=d.policy_id),d.document,true FROM demo_policy_docs d;
UPDATE iam.policies p SET row_version=row_version+1 FROM demo_policy_docs d WHERE p.policy_id=d.policy_id;
UPDATE iam.policy_generation SET generation=generation+1,changed_at=now();
GRANT EXECUTE ON FUNCTION app.actor_holds_role(text[]) TO waypoint_demo;
INSERT INTO iam.action_catalogue(action,module,description,implemented) VALUES
 ('order:PrepareDemoDay','ordering','Prepare an empty demo date from S1',true);
CREATE TEMP TABLE demo_seed_policy ON COMMIT DROP AS
SELECT p.policy_id,jsonb_set(v.document,'{Statement}',(v.document->'Statement') ||
 '[{"Sid":"PrepareDemoDay","Effect":"Allow","Action":["order:PrepareDemoDay"],"Resource":["wpt:order:depot:Peliyagoda"]}]'::jsonb) AS document
FROM iam.policies p JOIN iam.policy_versions v ON p.policy_id=v.policy_id AND v.is_default WHERE p.name='WaypointAdministrator';
UPDATE iam.policy_versions v SET is_default=false FROM demo_seed_policy d WHERE v.policy_id=d.policy_id AND v.is_default;
INSERT INTO iam.policy_versions(policy_id,version_number,document,is_default)
SELECT d.policy_id,(SELECT max(version_number)+1 FROM iam.policy_versions WHERE policy_id=d.policy_id),d.document,true FROM demo_seed_policy d;
UPDATE iam.policies p SET row_version=row_version+1 FROM demo_seed_policy d WHERE p.policy_id=d.policy_id;
UPDATE iam.policy_generation SET generation=generation+1,changed_at=now();
INSERT INTO iam.action_catalogue(action,module,description,implemented) VALUES
 ('reference:PrepareDemoDay','referencedata','Prepare a demo fleet date without replacing statuses',true),
 ('iam:PrepareDemoDay','identity','Prepare dedicated demo account scope without replacing assignments',true);
