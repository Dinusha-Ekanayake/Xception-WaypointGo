-- The delivery half of the event backbone (issue #6).
--
--     The relay claims pending outbox rows, hands each to its subscribers and
--     marks it published, failed or dead. Three things the table lacked:

-- ---- write order --------------------------------------------------------
--     Per-aggregate ordering is the relay's guarantee. occurred_at and the
--     UUIDv7 id both resolve to a millisecond, so two events one command wrote
--     for the same aggregate had no defined order. An identity column gives the
--     order rows were written in. Adding it rewrites the table, which is small:
--     nothing has ever been delivered from it, and delivered rows are pruned.
--     Existing rows are numbered in their physical order.

ALTER TABLE integration.outbox_events
    ADD COLUMN IF NOT EXISTS seq bigint GENERATED ALWAYS AS IDENTITY;

-- ---- claim indexes ------------------------------------------------------
--     ix_outbox_due covers pending and failed only. A claimed row is
--     'processing' with next_attempt_at as its lease, and a relay that dies
--     mid-batch leaves rows that must become claimable again when it lapses
--     (PLT-02), so the claim reads all three states.

CREATE INDEX IF NOT EXISTS ix_outbox_claimable
    ON integration.outbox_events (next_attempt_at)
    WHERE status IN ('pending','failed','processing');

--     "Is there an older undelivered event for this aggregate" is asked of every
--     candidate row on every claim.
CREATE INDEX IF NOT EXISTS ix_outbox_open_by_aggregate
    ON integration.outbox_events (aggregate_type, aggregate_id, seq)
    WHERE status IN ('pending','failed','processing');

--     The dead-letter list (PLT-03), newest first.
CREATE INDEX IF NOT EXISTS ix_outbox_dead
    ON integration.outbox_events (seq)
    WHERE status = 'dead';

COMMENT ON COLUMN integration.outbox_events.seq IS
  'Write order. The relay delivers one aggregate''s events in this order.';
COMMENT ON COLUMN integration.outbox_events.next_attempt_at IS
  'When the row may next be claimed. For a processing row this is the claim''s lease.';

-- ---- replay -------------------------------------------------------------
--     ReplayEventHandler now handles the action 20260930T1201 catalogued and
--     granted to the administrator.

UPDATE iam.action_catalogue SET implemented = true WHERE action = 'platform:ReplayEvent';
