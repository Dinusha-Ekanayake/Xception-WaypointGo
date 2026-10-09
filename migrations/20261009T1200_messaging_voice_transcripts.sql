-- Roadmap FF-05: a machine transcript of a voice note, so the dispatcher can
-- read what a driver said from the cab without playing every note. Made by a
-- job outside any transaction when an engine is configured (TRANSCRIBE_ENGINE);
-- with none, nothing is queued and the screen says "no transcript, play audio".
--
-- The text is personal communication like the audio: it is cleared when the
-- audio is (P-33), and read only by who may read the note's message. The row
-- stays as the record that a transcript was made. Nothing is deleted.

CREATE TABLE IF NOT EXISTS messaging.voice_transcripts (
    voice_note_id uuid        PRIMARY KEY REFERENCES messaging.voice_notes (voice_note_id),
    status        text        NOT NULL CHECK (status IN ('pending', 'done', 'failed')),
    attempts      integer     NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 10),
    engine        text        NOT NULL CHECK (length(engine) BETWEEN 1 AND 40),
    language      text        CHECK (language IN ('en', 'si', 'ta')),
    transcript    text        CHECK (length(transcript) <= 4000),
    queued_at     timestamptz NOT NULL,
    finished_at   timestamptz,
    purged_at     timestamptz,
    CONSTRAINT ck_voice_transcripts_done CHECK (status <> 'done' OR finished_at IS NOT NULL),
    CONSTRAINT ck_voice_transcripts_purged CHECK (purged_at IS NULL OR transcript IS NULL)
);

COMMENT ON TABLE messaging.voice_transcripts IS
  'FF-05: the machine transcript of a voice note. Personal communication: never logged, cleared with the audio.';

CREATE INDEX IF NOT EXISTS ix_voice_transcripts_pending ON messaging.voice_transcripts (queued_at) WHERE status = 'pending';

GRANT SELECT, INSERT, UPDATE ON messaging.voice_transcripts TO waypoint_messaging;

ALTER TABLE messaging.voice_transcripts ENABLE ROW LEVEL SECURITY;
ALTER TABLE messaging.voice_transcripts FORCE ROW LEVEL SECURITY;

-- Read with the note's message; queued, written and cleared by the system only.
DROP POLICY IF EXISTS voice_transcripts_read ON messaging.voice_transcripts;
CREATE POLICY voice_transcripts_read ON messaging.voice_transcripts
    FOR SELECT TO waypoint_messaging
    USING (app.actor_is_system()
           OR EXISTS (SELECT 1 FROM messaging.messages m WHERE m.voice_note_id = voice_transcripts.voice_note_id));
DROP POLICY IF EXISTS voice_transcripts_write ON messaging.voice_transcripts;
CREATE POLICY voice_transcripts_write ON messaging.voice_transcripts
    FOR INSERT TO waypoint_messaging WITH CHECK (app.actor_is_system());
DROP POLICY IF EXISTS voice_transcripts_update ON messaging.voice_transcripts;
CREATE POLICY voice_transcripts_update ON messaging.voice_transcripts
    FOR UPDATE TO waypoint_messaging USING (app.actor_is_system()) WITH CHECK (app.actor_is_system());
