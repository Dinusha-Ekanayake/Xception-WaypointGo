-- What a chat message was read as (Intelligence's message.understood): the act and
-- whether the reading was sure enough to steer the suggested replies. Kept on the
-- message, so every reader who may read the message reads it, and nobody else.
ALTER TABLE messaging.messages ADD COLUMN IF NOT EXISTS understood_act text
    CHECK (understood_act IS NULL OR (understood_act ~ '^[A-Z_]+$' AND length(understood_act) <= 32));
ALTER TABLE messaging.messages ADD COLUMN IF NOT EXISTS understood_sure boolean;

-- Only the system writes it, from the event; nobody else may change a message.
GRANT UPDATE (understood_act, understood_sure) ON messaging.messages TO waypoint_messaging;
DROP POLICY IF EXISTS messages_understood ON messaging.messages;
CREATE POLICY messages_understood ON messaging.messages
    FOR UPDATE TO waypoint_messaging
    USING (app.actor_is_system())
    WITH CHECK (app.actor_is_system());
