-- Roadmap M-01: the bytes may live in the object store. With OBJECT_STORE=s3 a new
-- file is written to the bucket (encrypted on the server first) and its row
-- keeps object_key instead of content; a file stored before the switch is copied
-- by the backfill job, checked by SHA-256, marked moved_at, and its database copy
-- cleared after S3_MOVE_AFTER (30 days). Expand only: both columns nullable.

ALTER TABLE execution.proof_content ADD COLUMN IF NOT EXISTS object_key text;
ALTER TABLE execution.proof_content ADD COLUMN IF NOT EXISTS moved_at timestamptz;
ALTER TABLE execution.proof_content DROP CONSTRAINT IF EXISTS ck_proof_content_purge;
ALTER TABLE execution.proof_content ADD CONSTRAINT ck_proof_content_purge
    CHECK ((purged_at IS NOT NULL) = (content IS NULL AND object_key IS NULL));
CREATE INDEX IF NOT EXISTS ix_proof_content_to_move ON execution.proof_content (stored_at)
    WHERE object_key IS NULL AND content IS NOT NULL;

ALTER TABLE execution.fuel_passes ADD COLUMN IF NOT EXISTS object_key text;
ALTER TABLE execution.fuel_passes ADD COLUMN IF NOT EXISTS moved_at timestamptz;
ALTER TABLE execution.fuel_passes ALTER COLUMN content DROP NOT NULL;
ALTER TABLE execution.fuel_passes DROP CONSTRAINT IF EXISTS ck_fuel_passes_held;
ALTER TABLE execution.fuel_passes ADD CONSTRAINT ck_fuel_passes_held
    CHECK (content IS NOT NULL OR object_key IS NOT NULL);
CREATE INDEX IF NOT EXISTS ix_fuel_passes_to_move ON execution.fuel_passes (uploaded_at)
    WHERE object_key IS NULL AND content IS NOT NULL;
