-- Roadmap FF-04: a trip's live channel. The dispatcher and the stores whose
-- goods are still on the trip coordinate in one place; the driver is driving
-- and is not part of it (decided 2026-10-06): the driver keeps reading only
-- what is addressed to the driver, or to everyone, and their own posts.
--
-- The channel opens when the driver starts the trip, after loading: before that
-- there is nothing to coordinate on the road, so nobody may post to it.
--
-- Membership is per outlet. An outlet joins when a plan names it on the trip
-- and becomes read only once every order it has on the trip is delivered or
-- failed; it never reverts. Events from different aggregates arrive in any
-- order, so membership is the planned orders less the finished ones.

ALTER TABLE messaging.threads ADD COLUMN channel_opened_at timestamptz;
COMMENT ON COLUMN messaging.threads.channel_opened_at IS
  'When the driver started the trip (its first delivery.started): the trip''s channel takes posts from then (FF-04).';

CREATE TABLE messaging.thread_outlets (
    thread_id       uuid        NOT NULL REFERENCES messaging.threads (thread_id),
    outlet_id       text        NOT NULL,
    planned_orders  uuid[]      NOT NULL DEFAULT '{}',
    done_orders     uuid[]      NOT NULL DEFAULT '{}',
    state           text        NOT NULL DEFAULT 'active',
    activated_at    timestamptz NOT NULL,
    read_only_at    timestamptz,
    PRIMARY KEY (thread_id, outlet_id),
    CONSTRAINT ck_thread_outlets_state CHECK (state IN ('active', 'read_only')),
    CONSTRAINT ck_thread_outlets_read_only CHECK ((state = 'read_only') = (read_only_at IS NOT NULL))
);

COMMENT ON TABLE messaging.thread_outlets IS
  'Which stores are in a trip''s live channel (FF-04): active while any of their orders on the trip is open, read only after.';

CREATE INDEX ix_thread_outlets_outlet ON messaging.thread_outlets (outlet_id);

GRANT SELECT, INSERT, UPDATE ON messaging.thread_outlets TO waypoint_messaging;
ALTER TABLE messaging.thread_outlets ENABLE ROW LEVEL SECURITY;
ALTER TABLE messaging.thread_outlets FORCE ROW LEVEL SECURITY;
CREATE POLICY thread_outlets_read ON messaging.thread_outlets
    FOR SELECT TO waypoint_messaging
    USING (app.actor_is_system()
           OR EXISTS (SELECT 1 FROM messaging.threads t WHERE t.thread_id = thread_outlets.thread_id));
CREATE POLICY thread_outlets_write ON messaging.thread_outlets
    FOR ALL TO waypoint_messaging
    USING (app.actor_is_system()) WITH CHECK (app.actor_is_system());

-- Every trip thread already open joins its outlets now, active: their orders are
-- not known here, so they go read only with the thread at the end of the next day.
ALTER TABLE messaging.thread_outlets NO FORCE ROW LEVEL SECURITY;
ALTER TABLE messaging.threads NO FORCE ROW LEVEL SECURITY;
INSERT INTO messaging.thread_outlets (thread_id, outlet_id, activated_at)
SELECT t.thread_id, o, t.opened_at
FROM messaging.threads t, unnest(t.outlet_ids) o
WHERE t.subject_type = 'trip'
ON CONFLICT DO NOTHING;
ALTER TABLE messaging.threads FORCE ROW LEVEL SECURITY;
ALTER TABLE messaging.thread_outlets FORCE ROW LEVEL SECURITY;

-- A dispatcher may move a channel post off the channel, to that store's private
-- line. The message is never changed: a redirect is a row of its own, audited.
CREATE TABLE messaging.message_redirects (
    message_id     uuid        PRIMARY KEY REFERENCES messaging.messages (message_id),
    redirected_by  uuid        NOT NULL,
    reason         text        NOT NULL,
    redirected_at  timestamptz NOT NULL,
    command_id     uuid        NOT NULL,
    CONSTRAINT ck_message_redirects_reason CHECK (char_length(reason) BETWEEN 3 AND 500)
);
GRANT SELECT, INSERT ON messaging.message_redirects TO waypoint_messaging;
ALTER TABLE messaging.message_redirects ENABLE ROW LEVEL SECURITY;
ALTER TABLE messaging.message_redirects FORCE ROW LEVEL SECURITY;
-- Readable by every messaging query: the read rule on messages checks whether a
-- post was redirected, and must see the row to hide the post from a store. A
-- policy that read messages here would loop with that rule. The row holds ids,
-- who, when and why, never the message; only a dispatcher's read returns it.
CREATE POLICY message_redirects_read ON messaging.message_redirects
    FOR SELECT TO waypoint_messaging
    USING (true);
CREATE POLICY message_redirects_insert ON messaging.message_redirects
    FOR INSERT TO waypoint_messaging
    WITH CHECK (redirected_by = app.current_actor()
                AND EXISTS (SELECT 1 FROM messaging.messages m JOIN messaging.threads t ON t.thread_id = m.thread_id
                            WHERE m.message_id = message_redirects.message_id
                              AND app.actor_oversees_depot(t.depot_code)));

ALTER TABLE messaging.messages DROP CONSTRAINT ck_messages_audience;
ALTER TABLE messaging.messages ADD CONSTRAINT ck_messages_audience CHECK (
    audience IN ('dispatch','driver','loader','outlet','all','channel')
    AND ((audience = 'outlet') = (audience_outlet IS NOT NULL)));

-- Reading. Everything as before, plus the channel: a store reads it while its
-- outlet is active, and what was posted before it went read only; a redirected
-- post leaves the channel. The driver's lines are unchanged and do not include it.
DROP POLICY messages_read ON messaging.messages;
CREATE POLICY messages_read ON messaging.messages
    FOR SELECT TO waypoint_messaging
    USING (app.actor_is_system()
           OR author_user_id = app.current_actor()
           OR EXISTS (
               SELECT 1 FROM messaging.threads t
               WHERE t.thread_id = messages.thread_id
                 AND (app.actor_oversees_depot(t.depot_code)
                      OR (messages.audience IN ('all','driver') AND t.vehicle_id IS NOT NULL
                          AND app.actor_drives(t.vehicle_id, t.service_date))
                      OR (messages.audience IN ('all','loader') AND app.actor_has_depot(t.depot_code)
                          AND app.actor_holds_role(ARRAY['loader']))
                      OR (messages.audience = 'outlet' AND app.actor_has_outlet(messages.audience_outlet))
                      OR (messages.audience = 'all'
                          AND EXISTS (SELECT 1 FROM unnest(t.outlet_ids) o WHERE app.actor_has_outlet(o)))
                      OR (messages.audience = 'channel'
                          AND NOT EXISTS (SELECT 1 FROM messaging.message_redirects r WHERE r.message_id = messages.message_id)
                          AND EXISTS (SELECT 1 FROM messaging.thread_outlets o
                                      WHERE o.thread_id = messages.thread_id
                                        AND app.actor_has_outlet(o.outlet_id)
                                        AND (o.state = 'active' OR messages.created_at < o.read_only_at))))));
