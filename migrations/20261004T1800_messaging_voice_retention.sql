-- Voice note retention (issue #136, P-33, MSG-11). The audio of a voice note is
-- cleared once retain_until passes; the row, its size and its SHA-256 stay as
-- the record that it was said. Nothing is deleted.
--
-- Expand only: the table is small and new (20261004T0100), so the backfill is
-- one statement. retain_until is uploaded_at plus the default 400 days for notes
-- recorded before this; VoiceNotes sets it from VOICE_RETENTION for new ones.

ALTER TABLE messaging.voice_notes ADD COLUMN IF NOT EXISTS retain_until timestamptz;
ALTER TABLE messaging.voice_notes ADD COLUMN IF NOT EXISTS purged_at timestamptz;
ALTER TABLE messaging.voice_notes ALTER COLUMN content DROP NOT NULL;

-- The table forces row-level security and the migrator runs with row security
-- off, so the force is lifted for the backfill and put back after (AGENTS.md).
ALTER TABLE messaging.voice_notes NO FORCE ROW LEVEL SECURITY;
UPDATE messaging.voice_notes SET retain_until = uploaded_at + interval '400 days' WHERE retain_until IS NULL;
ALTER TABLE messaging.voice_notes FORCE ROW LEVEL SECURITY;

ALTER TABLE messaging.voice_notes ALTER COLUMN retain_until SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_voice_notes_purge') THEN
        ALTER TABLE messaging.voice_notes
            ADD CONSTRAINT ck_voice_notes_purge CHECK ((content IS NULL) = (purged_at IS NOT NULL));
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS ix_voice_notes_due ON messaging.voice_notes (retain_until) WHERE purged_at IS NULL;

-- The retention job alone clears a voice note's bytes: UPDATE of two columns,
-- for the system actor only. Notes stay append only for everyone else.
GRANT UPDATE (content, purged_at) ON messaging.voice_notes TO waypoint_messaging;

DROP POLICY IF EXISTS voice_notes_purge ON messaging.voice_notes;
CREATE POLICY voice_notes_purge ON messaging.voice_notes
    FOR UPDATE TO waypoint_messaging
    USING (app.actor_is_system())
    WITH CHECK (app.actor_is_system());
