-- 005 Command idempotency
--    sync_operations de-duplicates the offline queue. This stores the
--    RESULT, so a retry after a lost response replays the original
--    success instead of returning a duplicate-key error.
-- ---------------------------------------------------------

CREATE TABLE IF NOT EXISTS integration.command_receipts (
    command_id uuid NOT NULL,
    actor_user_id uuid NOT NULL REFERENCES iam.users(user_id),
    command_type text NOT NULL,
    payload_fingerprint text NOT NULL,
    result_status integer NOT NULL,
    result_body jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (command_id, actor_user_id)
);

CREATE INDEX IF NOT EXISTS ix_command_receipts_created
    ON integration.command_receipts(created_at DESC);
