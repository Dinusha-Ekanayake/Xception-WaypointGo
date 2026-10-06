-- Roadmap FF-03: damaged-goods inquiries belong to Operations (P-DG-4), not to
-- the system administrator. Two roles, both depot-scoped through the usual
-- user_depot_access grant:
--   ops_inquiry  a depot operations manager: reviews issues, records the outcome
--                of an inquiry, approves write-offs up to the depot limit (P-DG-1)
--   ops_head     the Head of Distribution: the same, and approves above the limit
--                and any Tech line
-- Their policy allows reading issues and the two inquiry commands only, so the
-- depot scope never opens trips, plans or messages: those need a policy action
-- this role does not hold, and the trip tables need app.actor_oversees_depot,
-- which these roles are not part of.
INSERT INTO iam.roles (role_code, description) VALUES
    ('ops_inquiry', 'Reviews damaged-goods issues, records inquiries and approves write-offs up to the depot limit'),
    ('ops_head',    'Head of Distribution: approves write-offs above the depot limit and any Tech line')
ON CONFLICT (role_code) DO NOTHING;

INSERT INTO iam.action_catalogue (action, module, description, implemented) VALUES
    ('issue:RecordInquiry',   'issues', 'Record the outcome of an inquiry into an issue, with any write-off value and photos', true),
    ('issue:ApproveWriteOff', 'issues', 'Approve or reject a write-off recorded by an inquiry, within the approval limits', true)
ON CONFLICT (action) DO UPDATE SET implemented = true;

INSERT INTO iam.policies (name, description)
SELECT 'WaypointOpsInquiry', 'Operations inquiries: read a depot''s issues, record inquiries, approve write-offs'
WHERE NOT EXISTS (SELECT 1 FROM iam.policies WHERE name = 'WaypointOpsInquiry');

INSERT INTO iam.policy_versions (policy_id, version_number, document, is_default)
SELECT p.policy_id, 1, '{
    "Version": "2026-10-07",
    "Statement": [
      {"Sid": "Inquire", "Effect": "Allow",
       "Action": ["issue:Read", "issue:RecordInquiry", "issue:ApproveWriteOff", "notification:*"],
       "Resource": ["*"]}
    ]}'::jsonb, true
FROM iam.policies p
WHERE p.name = 'WaypointOpsInquiry'
  AND NOT EXISTS (SELECT 1 FROM iam.policy_versions v WHERE v.policy_id = p.policy_id);

INSERT INTO iam.policy_attachments (policy_id, principal_type, principal_id)
SELECT p.policy_id, 'role', r.role_code
FROM iam.policies p
CROSS JOIN (VALUES ('ops_inquiry'), ('ops_head')) AS r(role_code)
WHERE p.name = 'WaypointOpsInquiry'
  AND NOT EXISTS (
      SELECT 1 FROM iam.policy_attachments a
      WHERE a.policy_id = p.policy_id AND a.principal_type = 'role' AND a.principal_id = r.role_code);

-- The dispatcher holds issue:* and decides deliveries; an inquiry into one is
-- Operations' (P-DG-4), so the dispatcher policy denies the two new actions.
CREATE OR REPLACE FUNCTION pg_temp.deny_inquiry(doc jsonb) RETURNS jsonb
LANGUAGE sql IMMUTABLE AS $$
    SELECT CASE
        WHEN EXISTS (SELECT 1 FROM jsonb_array_elements(doc -> 'Statement') s WHERE s ->> 'Sid' = 'InquiriesAreOperations')
        THEN doc
        ELSE jsonb_set(doc, '{Statement}', (doc -> 'Statement') || jsonb_build_array(jsonb_build_object(
                 'Sid', 'InquiriesAreOperations',
                 'Effect', 'Deny',
                 'Action', jsonb_build_array('issue:RecordInquiry', 'issue:ApproveWriteOff'),
                 'Resource', jsonb_build_array('*'))))
    END
$$;

CREATE TEMP TABLE inquiry_policy_docs ON COMMIT DROP AS
SELECT p.policy_id, pg_temp.deny_inquiry(pv.document) AS document, pv.document AS previous
FROM iam.policies p
JOIN iam.policy_versions pv ON pv.policy_id = p.policy_id AND pv.is_default
WHERE p.name = 'WaypointDispatcher';

DELETE FROM inquiry_policy_docs WHERE document = previous;

UPDATE iam.policy_versions pv SET is_default = false
FROM inquiry_policy_docs d WHERE pv.policy_id = d.policy_id AND pv.is_default;

INSERT INTO iam.policy_versions (policy_id, version_number, document, is_default)
SELECT d.policy_id, (SELECT max(version_number) + 1 FROM iam.policy_versions x WHERE x.policy_id = d.policy_id), d.document, true
FROM inquiry_policy_docs d;

UPDATE iam.policy_generation SET generation = generation + 1, changed_at = now();
