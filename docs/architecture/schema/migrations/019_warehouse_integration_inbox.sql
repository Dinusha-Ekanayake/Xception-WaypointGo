-- 019 Inbound warehouse events
--     The warehouse notifies Waypoint about stock decisions. Those
--     notifications are never processed inline in the request that
--     receives them: they land here first, then a worker applies them.
--
--     Why an inbox and not direct processing:
--       * the sender retries, so the same event arrives more than once
--       * a malformed or unknown event must be quarantined, not dropped
--       * processing must survive the HTTP request that delivered it
--
--     This is the mirror of integration.outbox_events, inbound.
-- ---------------------------------------------------------

CREATE TABLE IF NOT EXISTS integration.inbound_events (
    inbound_event_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    source_system text NOT NULL,
    source_event_id text NOT NULL,
    event_type text NOT NULL,
    payload jsonb NOT NULL,
    signature_verified boolean NOT NULL DEFAULT false,
    received_at timestamptz NOT NULL DEFAULT now(),
    status text NOT NULL DEFAULT 'received',
    attempts integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    processed_at timestamptz,
    last_error text,
    -- the sender's own id makes replay detection exact rather than heuristic
    CONSTRAINT uq_inbound_source_event UNIQUE (source_system, source_event_id),
    CONSTRAINT ck_inbound_status CHECK (status IN
        ('received','processing','processed','failed','quarantined','dead')),
    CONSTRAINT ck_inbound_attempts CHECK (attempts >= 0),
    -- an unverified payload may be stored for forensics but never processed
    CONSTRAINT ck_inbound_unverified_is_quarantined CHECK (
        signature_verified OR status IN ('received','quarantined','dead')
    )
);

CREATE INDEX IF NOT EXISTS ix_inbound_due
    ON integration.inbound_events(next_attempt_at)
    WHERE status IN ('received','failed');

CREATE INDEX IF NOT EXISTS ix_inbound_quarantined
    ON integration.inbound_events(received_at DESC) WHERE status = 'quarantined';

GRANT SELECT, INSERT, UPDATE ON integration.inbound_events TO waypoint_integration;
GRANT SELECT ON integration.inbound_events TO waypoint_ops;
