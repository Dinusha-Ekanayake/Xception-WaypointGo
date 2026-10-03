-- Issue #139: MCP tool calls are rate limited per credential and per OAuth
-- client (R-IAM-33). The counters live here, not in a process, so a second
-- backend replica is not a way around the limit (as iam.login_attempts).
--
-- One row per bucket and one-minute window. A bucket is 'credential:<token
-- hash>' (the same SHA-256 iam.sessions stores, never the token) or
-- 'client:<oauth client id>'. Authentication bookkeeping like login_attempts:
-- the retention job deletes old windows, so this role may delete here.

CREATE TABLE iam.mcp_rate_windows (
    bucket        text        NOT NULL,
    window_start  timestamptz NOT NULL,
    calls         integer     NOT NULL DEFAULT 1,
    PRIMARY KEY (bucket, window_start),
    CONSTRAINT ck_mcp_rate_windows_calls CHECK (calls > 0),
    CONSTRAINT ck_mcp_rate_windows_bucket CHECK (bucket ~ '^(credential|client):[0-9A-Za-z_-]+$')
);

COMMENT ON TABLE iam.mcp_rate_windows IS
  'MCP requests per credential or OAuth client per one-minute window, shared by every replica (R-IAM-33).';

CREATE INDEX ix_mcp_rate_windows_start ON iam.mcp_rate_windows (window_start);

GRANT SELECT, INSERT, UPDATE, DELETE ON iam.mcp_rate_windows TO waypoint_iam;
