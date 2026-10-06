-- Roadmap F-12: retention deletes published outbox events by when they happened.
-- Without this the nightly purge scans every published event; the partial index
-- holds only those the purge can remove. Dead and pending events are not in it.
CREATE INDEX IF NOT EXISTS ix_outbox_published_occurred
    ON integration.outbox_events (occurred_at)
    WHERE status = 'published';
