-- The read half of the event backbone: the relay that delivers what the
-- publisher wrote (issue #6, PLT-02, PLT-03).
--
--     Expand only. Every column is nullable or has a constant default, so nothing
--     here rewrites outbox_events or waits on a lock held by a running relay.

-- A relay claims a batch and delivers it outside the claiming transaction, so a
-- subscriber's own transaction is never nested in the relay's. The claim is a
-- lease: a relay that dies mid batch leaves rows 'processing' until locked_until
-- passes, and the next claim picks them up again.
ALTER TABLE integration.outbox_events
    ADD COLUMN IF NOT EXISTS locked_until timestamptz,
    ADD COLUMN IF NOT EXISTS published_at timestamptz;

-- The claim reads pending or failed rows whose time has come, and processing rows
-- whose lease ran out. ix_outbox_due (005) covers only the first two.
CREATE INDEX IF NOT EXISTS ix_outbox_claimable
    ON integration.outbox_events (occurred_at, event_id)
    WHERE status IN ('pending', 'failed', 'processing');

-- ---- attempt history ----------------------------------------------------
--     PLT-03: a poison event is dead-lettered "with attempt history". One row per
--     failed delivery, and one per operator replay, so a dead event says what was
--     tried, when, by what and who finally put it back.
--
--     error holds an exception class and a truncated message, never the payload,
--     which can carry personal data.

CREATE TABLE IF NOT EXISTS integration.outbox_attempts (
    attempt_id   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id     uuid        NOT NULL,
    attempt      integer     NOT NULL,
    attempted_at timestamptz NOT NULL DEFAULT now(),
    outcome      text        NOT NULL,
    error        text,
    actor_id     uuid,
    note         text,
    CONSTRAINT ck_outbox_attempt_outcome CHECK (outcome IN ('failed', 'dead', 'replayed'))
);

-- Modules share no foreign keys (D-B), so event_id is an id, not a reference.
CREATE INDEX IF NOT EXISTS ix_outbox_attempts_event
    ON integration.outbox_attempts (event_id, attempted_at);

COMMENT ON TABLE integration.outbox_attempts IS
  'Failed deliveries and operator replays of an outbox event. Append only.';

GRANT SELECT, INSERT ON integration.outbox_attempts TO waypoint_integration;

-- ---- what retention may delete -----------------------------------------
--     Operational records are never deleted, and the integration role holds no
--     DELETE by default. These four tables are infrastructure bookkeeping with a
--     retention window (the jobs of issue #6), not operational records, so each
--     is named here rather than granted by schema. audit_log is not on this list:
--     it ages out by detaching a partition.

GRANT DELETE ON integration.outbox_events,
                integration.outbox_attempts,
                integration.consumed_events,
                integration.command_receipts
    TO waypoint_integration;

-- ---- the replay action is now enforced ---------------------------------
UPDATE iam.action_catalogue SET implemented = true WHERE action = 'platform:ReplayEvent';

-- The administrator's dead-letter list, newest first, and the dead gauge.
CREATE INDEX IF NOT EXISTS ix_outbox_dead
    ON integration.outbox_events (dead_lettered_at DESC, event_id DESC)
    WHERE status = 'dead';
