-- Preparing a demo day also plans and publishes it (plan:PrepareDemoDay), so the
-- loaders' dock board and the drivers' notifications have the day to show. The
-- administrator may run it for Peliyagoda only; the handler also requires demo
-- mode. Policy versions are immutable, so the statement arrives as a new default
-- version of WaypointAdministrator, as order:PrepareDemoDay did.
INSERT INTO iam.action_catalogue(action,module,description,implemented) VALUES
 ('plan:PrepareDemoDay','planning','Plan and publish a prepared demo date',true)
ON CONFLICT (action) DO NOTHING;
CREATE TEMP TABLE demo_plan_policy ON COMMIT DROP AS
SELECT p.policy_id,jsonb_set(v.document,'{Statement}',(v.document->'Statement') ||
 '[{"Sid":"PrepareDemoPlan","Effect":"Allow","Action":["plan:PrepareDemoDay"],"Resource":["wpt:plan:depot:Peliyagoda"]}]'::jsonb) AS document
FROM iam.policies p JOIN iam.policy_versions v ON p.policy_id=v.policy_id AND v.is_default WHERE p.name='WaypointAdministrator';
UPDATE iam.policy_versions v SET is_default=false FROM demo_plan_policy d WHERE v.policy_id=d.policy_id AND v.is_default;
INSERT INTO iam.policy_versions(policy_id,version_number,document,is_default)
SELECT d.policy_id,(SELECT max(version_number)+1 FROM iam.policy_versions WHERE policy_id=d.policy_id),d.document,true FROM demo_plan_policy d;
UPDATE iam.policies p SET row_version=row_version+1 FROM demo_plan_policy d WHERE p.policy_id=d.policy_id;
UPDATE iam.policy_generation SET generation=generation+1,changed_at=now();
