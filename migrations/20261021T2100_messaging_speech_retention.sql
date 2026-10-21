-- R-MSG-10, as changed on 2026-10-21: the server's spoken copy of a message is a
-- cache, kept while its text is in use. A render no message has asked for in 30
-- days loses its audio; the row stays (nothing is deleted), and a later message
-- with the same words is spoken again. A message whose render was cleared is read
-- by the phone's own voice, as when the speech service is off.
ALTER TABLE messaging.speech_renders ALTER COLUMN audio DROP NOT NULL;
ALTER TABLE messaging.speech_renders ADD COLUMN IF NOT EXISTS purged_at timestamptz;
ALTER TABLE messaging.speech_renders DROP CONSTRAINT IF EXISTS ck_speech_renders_purged;
ALTER TABLE messaging.speech_renders ADD CONSTRAINT ck_speech_renders_purged
    CHECK ((audio IS NULL) = (purged_at IS NOT NULL));

-- The newest use of each text, for the nightly clear.
CREATE INDEX IF NOT EXISTS ix_message_speech_text ON messaging.message_speech (text_sha256, lang, created_at);
