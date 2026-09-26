-- 011 Evidence lifecycle
--     object_key, byte_size and sha256_hex already exist. What was missing
--     is scan state and retention, because proof artifacts are personal data.
-- ---------------------------------------------------------

ALTER TABLE ops.attachments
    ADD COLUMN IF NOT EXISTS scan_status text NOT NULL DEFAULT 'pending',
    ADD COLUMN IF NOT EXISTS retain_until date;

DO $$ BEGIN
    ALTER TABLE ops.attachments ADD CONSTRAINT ck_attachment_scan_status
        CHECK (scan_status IN ('pending','clean','infected','skipped'));
EXCEPTION WHEN duplicate_object THEN NULL; END $$;
