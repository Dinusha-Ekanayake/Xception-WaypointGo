-- Roadmap M-01: the bytes may live in the object store. With OBJECT_STORE=s3 a new
-- file is written to the bucket (encrypted on the server first) and its row
-- keeps object_key instead of content; a file stored before the switch is copied
-- by the backfill job, checked by SHA-256, marked moved_at, and its database copy
-- cleared after S3_MOVE_AFTER (30 days). Expand only: both columns nullable.

ALTER TABLE messaging.voice_notes ADD COLUMN IF NOT EXISTS object_key text;
ALTER TABLE messaging.voice_notes ADD COLUMN IF NOT EXISTS moved_at timestamptz;
ALTER TABLE messaging.voice_notes DROP CONSTRAINT IF EXISTS ck_voice_notes_purge;
ALTER TABLE messaging.voice_notes ADD CONSTRAINT ck_voice_notes_purge
    CHECK ((purged_at IS NOT NULL) = (content IS NULL AND object_key IS NULL));
CREATE INDEX IF NOT EXISTS ix_voice_notes_to_move ON messaging.voice_notes (uploaded_at)
    WHERE object_key IS NULL AND content IS NOT NULL;

-- The process alone records where the bytes went, as it alone clears them.
GRANT UPDATE (object_key, moved_at) ON messaging.voice_notes TO waypoint_messaging;
