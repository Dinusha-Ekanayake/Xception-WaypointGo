-- Roadmap M-01: the bytes may live in the object store. With OBJECT_STORE=s3 a new
-- file is written to the bucket (encrypted on the server first) and its row
-- keeps object_key instead of content; a file stored before the switch is copied
-- by the backfill job, checked by SHA-256, marked moved_at, and its database copy
-- cleared after S3_MOVE_AFTER (30 days). Expand only: both columns nullable.

ALTER TABLE iam.profile_photos ADD COLUMN IF NOT EXISTS object_key text;
ALTER TABLE iam.profile_photos ADD COLUMN IF NOT EXISTS moved_at timestamptz;
ALTER TABLE iam.profile_photos DROP CONSTRAINT IF EXISTS ck_profile_photo_removed;
ALTER TABLE iam.profile_photos ADD CONSTRAINT ck_profile_photo_removed CHECK (
    (removed_at IS NULL) = (content IS NOT NULL OR object_key IS NOT NULL)
    AND (removed_at IS NULL) = (removed_by IS NULL)
    AND (removed_at IS NULL) = (removal_reason IS NULL));
CREATE INDEX IF NOT EXISTS ix_profile_photos_to_move ON iam.profile_photos (uploaded_at)
    WHERE object_key IS NULL AND content IS NOT NULL;
