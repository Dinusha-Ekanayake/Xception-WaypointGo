-- R-MSG-10: a typed message is spoken on our own server (the speech service,
-- Piper) so every phone can play it, in Sinhala, Tamil or English. The same text
-- in the same language is spoken once and kept (speech_renders); each message
-- waits in message_speech until its audio is ready. Audio is heard only by those
-- who may read the message: the read rule asks the messages table, whose own
-- row-level security decides. Never deleted with the message's retention.
CREATE TABLE messaging.speech_renders (
    text_sha256  text        NOT NULL,
    lang         text        NOT NULL,
    audio        bytea       NOT NULL,
    content_type text        NOT NULL,
    created_at   timestamptz NOT NULL,
    PRIMARY KEY (text_sha256, lang),
    CONSTRAINT ck_speech_renders_lang CHECK (lang IN ('en', 'si', 'ta')),
    CONSTRAINT ck_speech_renders_size CHECK (octet_length(audio) BETWEEN 1 AND 2097152)
);

CREATE TABLE messaging.message_speech (
    message_id   uuid        PRIMARY KEY REFERENCES messaging.messages (message_id),
    text_sha256  text        NOT NULL,
    lang         text        NOT NULL,
    status       text        NOT NULL DEFAULT 'pending',
    attempts     integer     NOT NULL DEFAULT 0,
    created_at   timestamptz NOT NULL,
    ready_at     timestamptz,
    CONSTRAINT ck_message_speech_status CHECK (status IN ('pending', 'ready', 'failed'))
);

CREATE INDEX ix_message_speech_pending ON messaging.message_speech (created_at) WHERE status = 'pending';

GRANT SELECT, INSERT, UPDATE ON messaging.speech_renders, messaging.message_speech TO waypoint_messaging;

ALTER TABLE messaging.message_speech ENABLE ROW LEVEL SECURITY;
ALTER TABLE messaging.message_speech FORCE ROW LEVEL SECURITY;
CREATE POLICY message_speech_read ON messaging.message_speech
    FOR SELECT TO waypoint_messaging
    USING (app.actor_is_system()
           OR EXISTS (SELECT 1 FROM messaging.messages m WHERE m.message_id = message_speech.message_id));
CREATE POLICY message_speech_write ON messaging.message_speech
    FOR ALL TO waypoint_messaging
    USING (app.actor_is_system()) WITH CHECK (app.actor_is_system());

ALTER TABLE messaging.speech_renders ENABLE ROW LEVEL SECURITY;
ALTER TABLE messaging.speech_renders FORCE ROW LEVEL SECURITY;
-- Reached only through a message the reader may read (message_speech above).
CREATE POLICY speech_renders_read ON messaging.speech_renders
    FOR SELECT TO waypoint_messaging
    USING (app.actor_is_system()
           OR EXISTS (SELECT 1 FROM messaging.message_speech s
                      WHERE s.text_sha256 = speech_renders.text_sha256 AND s.lang = speech_renders.lang));
CREATE POLICY speech_renders_write ON messaging.speech_renders
    FOR ALL TO waypoint_messaging
    USING (app.actor_is_system()) WITH CHECK (app.actor_is_system());
