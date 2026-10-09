-- Roadmap M-01: the bytes may live in the object store. With OBJECT_STORE=s3 a new
-- file is written to the bucket (encrypted on the server first) and its row
-- keeps object_key instead of content; a file stored before the switch is copied
-- by the backfill job, checked by SHA-256, marked moved_at, and its database copy
-- cleared after S3_MOVE_AFTER (30 days). Expand only: both columns nullable.

ALTER TABLE issues.attachment_content ADD COLUMN IF NOT EXISTS object_key text;
ALTER TABLE issues.attachment_content ADD COLUMN IF NOT EXISTS moved_at timestamptz;
ALTER TABLE issues.attachment_content DROP CONSTRAINT IF EXISTS ck_attachment_content_purge;
ALTER TABLE issues.attachment_content ADD CONSTRAINT ck_attachment_content_purge
    CHECK ((purged_at IS NOT NULL) = (content IS NULL AND object_key IS NULL));
CREATE INDEX IF NOT EXISTS ix_attachment_content_to_move ON issues.attachment_content (stored_at)
    WHERE object_key IS NULL AND content IS NOT NULL;
