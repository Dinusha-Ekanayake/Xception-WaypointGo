-- 013 Outbox worker state and dead-lettering
--     Without these a poison event either blocks the queue or is retried
--     forever with no visibility.
-- ---------------------------------------------------------

ALTER TABLE integration.outbox_events
    ADD COLUMN IF NOT EXISTS status text NOT NULL DEFAULT 'pending',
    ADD COLUMN IF NOT EXISTS next_attempt_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN IF NOT EXISTS last_error text,
    ADD COLUMN IF NOT EXISTS dead_lettered_at timestamptz;

DO $$ BEGIN
    ALTER TABLE integration.outbox_events ADD CONSTRAINT ck_outbox_status
        CHECK (status IN ('pending','processing','published','failed','dead'));
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

-- Workers claim with FOR UPDATE SKIP LOCKED, or the relay serialises to
-- one effective instance no matter how many are running.
CREATE INDEX IF NOT EXISTS ix_outbox_due
    ON integration.outbox_events(next_attempt_at)
    WHERE status IN ('pending','failed');

CREATE INDEX IF NOT EXISTS ix_outbox_dead
    ON integration.outbox_events(dead_lettered_at DESC) WHERE status = 'dead';
