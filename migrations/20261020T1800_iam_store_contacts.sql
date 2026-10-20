-- A dispatcher reads who to call about an outlet's order (iam:ReadStoreContact,
-- R-IAM-41): its store managers' names and the phones they keep on their own
-- profile. The policy names the action; the handler limits it to outlets in the
-- dispatcher's depots. Administrators hold iam:* already. Policy versions are
-- immutable, so the statement arrives as a new default version.
INSERT INTO iam.action_catalogue(action,module,description,implemented) VALUES
 ('iam:ReadStoreContact','identity','Read the store managers to call about an outlet''s order',true)
ON CONFLICT (action) DO NOTHING;
CREATE TEMP TABLE store_contact_policy ON COMMIT DROP AS
SELECT p.policy_id,jsonb_set(v.document,'{Statement}',(v.document->'Statement') ||
 '[{"Sid":"ReadStoreContact","Effect":"Allow","Action":["iam:ReadStoreContact"],"Resource":["wpt:iam:outlet:*"]}]'::jsonb) AS document
FROM iam.policies p JOIN iam.policy_versions v ON p.policy_id=v.policy_id AND v.is_default WHERE p.name='WaypointDispatcher';
UPDATE iam.policy_versions v SET is_default=false FROM store_contact_policy d WHERE v.policy_id=d.policy_id AND v.is_default;
INSERT INTO iam.policy_versions(policy_id,version_number,document,is_default)
SELECT d.policy_id,(SELECT max(version_number)+1 FROM iam.policy_versions WHERE policy_id=d.policy_id),d.document,true FROM store_contact_policy d;
UPDATE iam.policies p SET row_version=row_version+1 FROM store_contact_policy d WHERE p.policy_id=d.policy_id;
UPDATE iam.policy_generation SET generation=generation+1,changed_at=now();
