-- Remote MCP clients (issue #87, R-IAM-31). A hosted assistant cannot start the
-- local adapter and must never see a password, so it authorizes with OAuth 2.1:
-- authorization code with PKCE, public clients only.
--
--     Nothing here is a new credential. The access token is still the opaque
--     read-only session of R-IAM-30 in iam.sessions; these two tables are the
--     bookkeeping that hands one to a client the user has approved.
--
--     A registration grants nothing by itself: it names where a signed-in user
--     may be sent back to. It is open (RFC 7591), so it is bounded per address
--     in the application and unused rows are removed by iam.retention.
--
--     A code is kept as a hash, lives for minutes and is used once. session_key
--     is the hash of the session the code produced, so a second use of the same
--     code can revoke it.

CREATE TABLE iam.oauth_clients (
    client_id       uuid        PRIMARY KEY,
    client_name     text        NOT NULL,
    redirect_uris   jsonb       NOT NULL,
    registered_at   timestamptz NOT NULL,
    registered_from inet,
    last_used_at    timestamptz,
    CONSTRAINT ck_oauth_clients_name CHECK (char_length(client_name) BETWEEN 1 AND 120),
    CONSTRAINT ck_oauth_clients_redirects CHECK (
        jsonb_typeof(redirect_uris) = 'array' AND jsonb_array_length(redirect_uris) BETWEEN 1 AND 10)
);

COMMENT ON TABLE iam.oauth_clients IS
  'Public OAuth clients that registered themselves for the remote MCP endpoint. No secret is ever issued.';
COMMENT ON COLUMN iam.oauth_clients.last_used_at IS
  'Last successful token exchange. Null means nobody ever finished signing in through it.';

CREATE INDEX ix_oauth_clients_registered ON iam.oauth_clients (registered_from, registered_at DESC);

CREATE TABLE iam.oauth_authorization_codes (
    code_hash      text        PRIMARY KEY,
    client_id      uuid        NOT NULL REFERENCES iam.oauth_clients (client_id),
    user_id        uuid        NOT NULL REFERENCES iam.users (user_id) ON DELETE CASCADE,
    redirect_uri   text        NOT NULL,
    code_challenge text        NOT NULL,
    issued_at      timestamptz NOT NULL,
    expires_at     timestamptz NOT NULL,
    consumed_at    timestamptz,
    session_key    text,
    CONSTRAINT ck_oauth_codes_order CHECK (expires_at > issued_at),
    CONSTRAINT ck_oauth_codes_session CHECK (session_key IS NULL OR consumed_at IS NOT NULL)
);

COMMENT ON TABLE iam.oauth_authorization_codes IS
  'One-time codes between sign-in and token exchange. Only the hash is stored; consumed_at makes a code single use.';

CREATE INDEX ix_oauth_codes_client ON iam.oauth_authorization_codes (client_id);
CREATE INDEX ix_oauth_codes_user ON iam.oauth_authorization_codes (user_id);
CREATE INDEX ix_oauth_codes_expiry ON iam.oauth_authorization_codes (expires_at);

-- Authentication bookkeeping like iam.sessions and iam.login_attempts: the
-- retention job deletes what has expired, so this role may delete here.
GRANT SELECT, INSERT, UPDATE, DELETE ON iam.oauth_clients, iam.oauth_authorization_codes TO waypoint_iam;
