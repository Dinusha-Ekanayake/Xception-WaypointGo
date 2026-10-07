-- Roadmap FF-08: which ready reply a post came from, so its use can be
-- measured (share of replies from a template, edited or not, by rank).
-- Append-only, one row per message; the message itself is never changed.
CREATE TABLE IF NOT EXISTS messaging.reply_uses (
    message_id       uuid PRIMARY KEY,
    template_id      text        NOT NULL CHECK (template_id ~ '^[a-z_]+(\.[a-z_]+)+$' AND length(template_id) <= 64),
    template_version integer     NOT NULL CHECK (template_version BETWEEN 1 AND 1000),
    rank             integer     NOT NULL CHECK (rank BETWEEN 1 AND 10),
    edited           boolean     NOT NULL,
    author_user_id   uuid        NOT NULL REFERENCES iam.users (user_id),
    used_at          timestamptz NOT NULL
);

COMMENT ON TABLE messaging.reply_uses IS
  'FF-08: the ready reply a post came from. Append-only; joined to messaging.messages by message_id.';

CREATE INDEX IF NOT EXISTS ix_reply_uses_template ON messaging.reply_uses (template_id, used_at);
CREATE INDEX IF NOT EXISTS ix_reply_uses_author ON messaging.reply_uses (author_user_id);

GRANT SELECT, INSERT ON messaging.reply_uses TO waypoint_messaging;

ALTER TABLE messaging.reply_uses ENABLE ROW LEVEL SECURITY;
ALTER TABLE messaging.reply_uses FORCE ROW LEVEL SECURITY;
-- Read with the message it belongs to; written only by its author.
CREATE POLICY reply_uses_read ON messaging.reply_uses
    FOR SELECT TO waypoint_messaging
    USING (app.actor_is_system()
           OR EXISTS (SELECT 1 FROM messaging.messages m WHERE m.message_id = reply_uses.message_id));
CREATE POLICY reply_uses_write ON messaging.reply_uses
    FOR INSERT TO waypoint_messaging
    WITH CHECK (app.actor_is_system() OR author_user_id = app.current_actor());
