-- Authentication bookkeeping, separate from business commands. Existing browser
-- sessions keep their purpose; an MCP credential can never become one.
ALTER TABLE iam.sessions ADD COLUMN mcp_read_only boolean NOT NULL DEFAULT false;

INSERT INTO iam.action_catalogue (action, module, description, implemented)
VALUES ('mcp:Connect', 'identity', 'Connect a personal read-only MCP client', true);

INSERT INTO iam.policies (name, description)
VALUES ('WaypointMcpReader', 'Optional personal read-only MCP connection; business read policy and scope still apply');

INSERT INTO iam.policy_versions (policy_id, version_number, document, is_default)
SELECT policy_id, 1,
       '{"Version":"2026-01-01","Statement":[{"Sid":"ConnectReadOnlyMcp","Effect":"Allow","Action":["mcp:Connect"],"Resource":["wpt:mcp:connection:*"]}]}'::jsonb,
       true
FROM iam.policies WHERE name = 'WaypointMcpReader';

INSERT INTO iam.policy_attachments (policy_id, principal_type, principal_id)
SELECT p.policy_id, 'role', r.role_code
FROM iam.policies p CROSS JOIN iam.roles r
WHERE p.name = 'WaypointMcpReader'
  AND r.role_code IN ('dispatcher', 'store_manager', 'loader', 'driver', 'auditor', 'admin');

UPDATE iam.policy_generation SET generation = generation + 1, changed_at = now();
