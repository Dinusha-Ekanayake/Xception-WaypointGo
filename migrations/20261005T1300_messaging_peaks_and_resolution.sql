-- Issue #136, two additions to the messaging schema.
--
-- 1. A voice note keeps its waveform: the loudness of each slice, 0 to 100, as
--    the recording phone measured it. Every reader then draws the same bars
--    without downloading and decoding the audio (which some phones cannot do).
--
-- 2. A report names the outlet it is about, when there is one: a store's own
--    outlet, or the outlet of the issue it was made from. The timeline draws
--    its warning sign on that stop instead of the stop's dot.
--
-- 3. A report can be resolved by the dispatcher, with who, when and why
--    (R-MSG-07, rule 8). Its warning sign on the timeline then goes; the report
--    stays on the thread as the record. A report made from a raised issue is
--    resolved when that issue is (source_issue_id). Messages stay append only
--    otherwise: only these three columns can change, once, from unresolved.

ALTER TABLE messaging.voice_notes ADD COLUMN IF NOT EXISTS peaks smallint[];

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_voice_notes_peaks') THEN
        ALTER TABLE messaging.voice_notes
            ADD CONSTRAINT ck_voice_notes_peaks CHECK (peaks IS NULL OR cardinality(peaks) BETWEEN 1 AND 64);
    END IF;
END $$;

ALTER TABLE messaging.messages ADD COLUMN IF NOT EXISTS about_outlet text;
ALTER TABLE messaging.messages ADD COLUMN IF NOT EXISTS source_issue_id uuid;
ALTER TABLE messaging.messages ADD COLUMN IF NOT EXISTS resolved_at timestamptz;
ALTER TABLE messaging.messages ADD COLUMN IF NOT EXISTS resolved_by uuid;
ALTER TABLE messaging.messages ADD COLUMN IF NOT EXISTS resolution_note text;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_messages_resolution') THEN
        ALTER TABLE messaging.messages
            ADD CONSTRAINT ck_messages_resolution CHECK (
                (resolved_at IS NULL AND resolved_by IS NULL AND resolution_note IS NULL)
                OR (kind = 'report' AND resolved_at IS NOT NULL AND resolved_by IS NOT NULL
                    AND char_length(coalesce(resolution_note, '')) <= 500));
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS ix_messages_source_issue ON messaging.messages (source_issue_id) WHERE source_issue_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_messages_open_reports ON messaging.messages (thread_id) WHERE kind = 'report' AND resolved_at IS NULL;

GRANT UPDATE (resolved_at, resolved_by, resolution_note) ON messaging.messages TO waypoint_messaging;

-- Resolving: an open report, by a dispatcher who oversees the trip's depot, as
-- themself; or the system, when the issue behind the report was resolved.
DROP POLICY IF EXISTS messages_resolve ON messaging.messages;
CREATE POLICY messages_resolve ON messaging.messages
    FOR UPDATE TO waypoint_messaging
    USING (kind = 'report' AND resolved_at IS NULL
           AND (app.actor_is_system()
                OR EXISTS (SELECT 1 FROM messaging.threads t
                            WHERE t.thread_id = messages.thread_id AND app.actor_oversees_depot(t.depot_code))))
    WITH CHECK (app.actor_is_system() OR resolved_by = app.current_actor());
