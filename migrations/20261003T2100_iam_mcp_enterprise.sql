-- Issue #177: MCP as an enterprise interface. Client scopes (R-IAM-34), confirmed
-- safe write tools with their own rate limit (R-IAM-35), the personal-field grant
-- (R-IAM-36) and blocking (R-IAM-37). Decision 2026-10-03: every read and the safe
-- writes are on by default; an administrator turns them off per person, role or
-- client with no deployment.

-- The scopes a connection was granted. NULL is a connection made before scopes
-- existed and means waypoint.read, every read the user has, as it did then.
ALTER TABLE iam.sessions ADD COLUMN mcp_scopes text[];
ALTER TABLE iam.oauth_authorization_codes ADD COLUMN scopes text[];

-- An administrator can block a registered client. Blocking revokes its sessions
-- and refuses new ones; it is a recorded decision with an actor and a reason.
ALTER TABLE iam.oauth_clients
    ADD COLUMN blocked_at   timestamptz,
    ADD COLUMN blocked_by   uuid,
    ADD COLUMN block_reason text,
    ADD COLUMN row_version  bigint NOT NULL DEFAULT 1;

ALTER TABLE iam.oauth_clients
    ADD CONSTRAINT ck_oauth_clients_block CHECK (
        (blocked_at IS NULL AND blocked_by IS NULL AND block_reason IS NULL)
        OR (blocked_at IS NOT NULL AND blocked_by IS NOT NULL AND length(block_reason) BETWEEN 1 AND 500));

-- Writes are counted in a bucket of their own, per credential per hour.
ALTER TABLE iam.mcp_rate_windows DROP CONSTRAINT ck_mcp_rate_windows_bucket;
ALTER TABLE iam.mcp_rate_windows
    ADD CONSTRAINT ck_mcp_rate_windows_bucket CHECK (bucket ~ '^(credential|client|write):[0-9A-Za-z_-]+$');

-- A write tool first stores the exact command it would submit and answers with
-- a confirmation; only the same connection can confirm it, once, within two
-- minutes. The command id is fixed here, so the bus sees one command however
-- often the confirmation is retried. Only the SHA-256 of the token is stored.
CREATE TABLE iam.mcp_write_confirmations (
    confirmation_hash text        PRIMARY KEY,
    session_key       text        NOT NULL,
    user_id           uuid        NOT NULL REFERENCES iam.users (user_id),
    oauth_client_id   uuid,
    tool              text        NOT NULL,
    command_id        uuid        NOT NULL UNIQUE,
    kind              text        NOT NULL,
    expected_version  bigint,
    payload           jsonb       NOT NULL,
    issued_at         timestamptz NOT NULL,
    expires_at        timestamptz NOT NULL,
    consumed_at       timestamptz,
    CONSTRAINT ck_mcp_write_confirmations_tool CHECK (tool IN ('raise_issue', 'assign_issue')),
    CONSTRAINT ck_mcp_write_confirmations_expiry CHECK (expires_at > issued_at)
);

COMMENT ON TABLE iam.mcp_write_confirmations IS
  'MCP write previews awaiting the person''s confirmation (R-IAM-35). Single use, bound to one connection.';

CREATE INDEX ix_mcp_write_confirmations_user ON iam.mcp_write_confirmations (user_id);
CREATE INDEX ix_mcp_write_confirmations_client ON iam.mcp_write_confirmations (oauth_client_id);

GRANT SELECT, INSERT, UPDATE ON iam.mcp_write_confirmations TO waypoint_iam;

INSERT INTO iam.action_catalogue (action, module, description, implemented) VALUES
  ('mcp:Write', 'identity', 'Use the confirmed MCP write tools; the business policy and scope of each command still apply', true),
  ('mcp:ReadPersonal', 'identity', 'Receive personal fields (people, notes, descriptions) in MCP results', true),
  ('mcp:ManageClients', 'identity', 'List registered MCP clients and their usage', true),
  ('mcp:BlockClient', 'identity', 'Block a registered MCP client and revoke its connections', true),
  ('mcp:UnblockClient', 'identity', 'Unblock a registered MCP client', true);

INSERT INTO iam.policies (name, description) VALUES
  ('WaypointMcpWriter', 'Confirmed MCP safe writes (raise and assign issues); each command is still authorized by its own business policy and scope'),
  ('WaypointMcpClientAdmin', 'See registered MCP clients and their usage, block and unblock them'),
  ('WaypointMcpPersonalReader', 'Personal fields in MCP results. Attached to no one by default: an administrator grants it explicitly'),
  ('WaypointMcpNoWrites', 'Turns off MCP writes for whoever it is attached to. Attached to no one by default'),
  ('WaypointMcpBlocked', 'Turns off MCP entirely for whoever it is attached to. Attached to no one by default');

INSERT INTO iam.policy_versions (policy_id, version_number, document, is_default)
SELECT p.policy_id, 1, d.doc::jsonb, true
FROM iam.policies p
JOIN (VALUES
  ('WaypointMcpWriter',
   '{"Version":"2026-01-01","Statement":[{"Sid":"ConfirmedMcpWrites","Effect":"Allow","Action":["mcp:Write"],"Resource":["wpt:mcp:connection:*"]}]}'),
  ('WaypointMcpClientAdmin',
   '{"Version":"2026-01-01","Statement":[{"Sid":"ManageMcpClients","Effect":"Allow","Action":["mcp:ManageClients","mcp:BlockClient","mcp:UnblockClient"],"Resource":["wpt:mcp:client:*"]}]}'),
  ('WaypointMcpPersonalReader',
   '{"Version":"2026-01-01","Statement":[{"Sid":"PersonalFieldsOverMcp","Effect":"Allow","Action":["mcp:ReadPersonal"],"Resource":["wpt:mcp:connection:*"]}]}'),
  ('WaypointMcpNoWrites',
   '{"Version":"2026-01-01","Statement":[{"Sid":"NoMcpWrites","Effect":"Deny","Action":["mcp:Write"],"Resource":["wpt:mcp:*"]}]}'),
  ('WaypointMcpBlocked',
   '{"Version":"2026-01-01","Statement":[{"Sid":"NoMcp","Effect":"Deny","Action":["mcp:*"],"Resource":["wpt:mcp:*"]}]}')
) AS d(name, doc) ON d.name = p.name;

-- On by default (decision 2026-10-03): the safe writes for the four field roles,
-- client management for administrators.
INSERT INTO iam.policy_attachments (policy_id, principal_type, principal_id)
SELECT p.policy_id, 'role', r.role_code
FROM iam.policies p CROSS JOIN iam.roles r
WHERE (p.name = 'WaypointMcpWriter' AND r.role_code IN ('dispatcher', 'store_manager', 'loader', 'driver'))
   OR (p.name = 'WaypointMcpClientAdmin' AND r.role_code = 'admin');

UPDATE iam.policy_generation SET generation = generation + 1, changed_at = now();
