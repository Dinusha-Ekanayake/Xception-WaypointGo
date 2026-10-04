-- An administrator ends one person's MCP connections now (R-IAM-38). Attaching
-- WaypointMcpBlocked already refuses their next request; this also removes the
-- sessions, so their connection list empties and nothing is left to retry with.
--
--     mcp:RevokeUserConnections is a new action (a catalogue row and a
--     handler). It joins the other client controls in WaypointMcpClientAdmin,
--     on resource wpt:mcp:user:*. A policy version is immutable (R-IAM-04), so
--     this is a new default version, and the generation moves with it (R-IAM-21).

INSERT INTO iam.action_catalogue (action, module, description, implemented) VALUES
    ('mcp:RevokeUserConnections', 'identity', 'End every MCP connection one person holds, with a reason', true)
ON CONFLICT (action) DO NOTHING;

CREATE TEMP TABLE mcp_user_revoke_doc ON COMMIT DROP AS
SELECT p.policy_id,
       jsonb_set(
           pv.document, '{Statement}',
           (pv.document -> 'Statement')
           || jsonb_build_array(
                  jsonb_build_object(
                      'Sid', 'EndPeoplesMcpConnections',
                      'Effect', 'Allow',
                      'Action', jsonb_build_array('mcp:RevokeUserConnections'),
                      'Resource', jsonb_build_array('wpt:mcp:user:*')))) AS document
FROM iam.policies p
JOIN iam.policy_versions pv ON pv.policy_id = p.policy_id AND pv.is_default
WHERE p.name = 'WaypointMcpClientAdmin'
  AND NOT EXISTS (
        SELECT 1 FROM jsonb_array_elements(pv.document -> 'Statement') s
         WHERE s ->> 'Sid' = 'EndPeoplesMcpConnections');

UPDATE iam.policy_versions pv
SET is_default = false
FROM mcp_user_revoke_doc d
WHERE pv.policy_id = d.policy_id AND pv.is_default;

INSERT INTO iam.policy_versions (policy_id, version_number, document, is_default)
SELECT d.policy_id,
       (SELECT max(version_number) + 1 FROM iam.policy_versions x WHERE x.policy_id = d.policy_id),
       d.document,
       true
FROM mcp_user_revoke_doc d;

UPDATE iam.policies p
SET row_version = p.row_version + 1
FROM mcp_user_revoke_doc d
WHERE p.policy_id = d.policy_id;

UPDATE iam.policy_generation
SET generation = generation + 1, changed_at = now()
WHERE EXISTS (SELECT 1 FROM mcp_user_revoke_doc);
