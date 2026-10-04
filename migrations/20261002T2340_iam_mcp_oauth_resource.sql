-- Bind remote codes and credentials to the canonical MCP protected resource.
-- Null is reserved for the existing local stdio credentials. Previously issued
-- unfinished OAuth codes have no resource and cannot be exchanged.
ALTER TABLE iam.oauth_authorization_codes ADD COLUMN resource_uri text;
ALTER TABLE iam.sessions ADD COLUMN oauth_resource text;
ALTER TABLE iam.sessions ADD COLUMN oauth_client_id uuid REFERENCES iam.oauth_clients(client_id);
CREATE INDEX ix_sessions_oauth_client ON iam.sessions(oauth_client_id) WHERE oauth_client_id IS NOT NULL;
ALTER TABLE iam.sessions ADD CONSTRAINT ck_sessions_oauth_purpose CHECK (
    (oauth_resource IS NULL AND oauth_client_id IS NULL)
    OR (mcp_read_only AND oauth_resource IS NOT NULL AND oauth_client_id IS NOT NULL)
);
