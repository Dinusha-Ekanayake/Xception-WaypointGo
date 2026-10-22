-- R-ML-44: a chat message is read with the earlier lines of its conversation.
-- What went with each call is kept, so what left for the AI provider can be
-- shown line by line: the earlier messages' ids, whose masked words are in this
-- same table. Nothing unmasked is stored.
ALTER TABLE ml.message_situations ADD COLUMN IF NOT EXISTS context_ids uuid[] NOT NULL DEFAULT '{}';
COMMENT ON COLUMN ml.message_situations.context_ids IS 'The earlier messages of the same chat sent with this one to Jev (R-ML-44), oldest first';
CREATE INDEX IF NOT EXISTS ix_message_situations_thread ON ml.message_situations (thread_id, created_at) WHERE thread_id IS NOT NULL;
