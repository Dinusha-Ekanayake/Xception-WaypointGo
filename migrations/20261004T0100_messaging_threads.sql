-- Issue #136 (epic #150): the messaging module. A thread is anchored to one
-- subject; the first subject is a trip, whose channel joins the dispatcher, the
-- depot's loaders, the driver and the stores on it
-- (docs/issues/136-messaging/PLAN.md).
--
-- Messaging owns its rows and reads no other module's tables: a trip's thread is
-- opened from plan.published (and widened from plan.revised), which carry each
-- trip's depot, vehicle, service date and outlets, so it exists before loading
-- starts and a loader's shortfall has somewhere to go. Every membership check is
-- then a question about the thread itself.

-- ---- schema and module role ------------------------------------------------
--     The same grants 20260930T1200, 20261001T0100 and 20261001T2200 gave every
--     module role, for one more module. Roles are cluster-wide, so attributes
--     are asserted rather than assumed.

CREATE SCHEMA IF NOT EXISTS messaging;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'waypoint_messaging') THEN
        CREATE ROLE waypoint_messaging NOLOGIN NOINHERIT;
    END IF;
END $$;

ALTER ROLE waypoint_messaging NOINHERIT NOLOGIN;
GRANT waypoint_messaging TO waypoint_app;

-- Its own schema. No DELETE: messages are never deleted.
GRANT USAGE ON SCHEMA messaging TO waypoint_messaging;

-- Read on the reference kernel.
GRANT USAGE ON SCHEMA ref TO waypoint_messaging;
GRANT SELECT ON ALL TABLES IN SCHEMA ref TO waypoint_messaging;
ALTER DEFAULT PRIVILEGES IN SCHEMA ref GRANT SELECT ON TABLES TO waypoint_messaging;

-- The scope tables the scope predicates read as the invoking role, narrowed to
-- the actor's own grants.
GRANT USAGE ON SCHEMA iam TO waypoint_messaging;
GRANT SELECT ON iam.user_depot_access, iam.user_outlet_access, iam.vehicle_driver_assignments TO waypoint_messaging;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE policyname = 'p_depot_access_self_messaging') THEN
        CREATE POLICY p_depot_access_self_messaging ON iam.user_depot_access
            FOR SELECT TO waypoint_messaging USING (user_id = app.current_actor());
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE policyname = 'p_outlet_access_self_messaging') THEN
        CREATE POLICY p_outlet_access_self_messaging ON iam.user_outlet_access
            FOR SELECT TO waypoint_messaging USING (user_id = app.current_actor());
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE policyname = 'p_driver_assignment_self_messaging') THEN
        CREATE POLICY p_driver_assignment_self_messaging ON iam.vehicle_driver_assignments
            FOR SELECT TO waypoint_messaging USING (driver_user_id = app.current_actor());
    END IF;
END $$;

-- A depot grant alone does not make a dispatcher: drivers and loaders hold one
-- too. The depot reads as overseen only for a dispatcher, an admin or an auditor
-- (app.actor_oversees_depot, 20261002T0900), and as the loaders' depot only with
-- the loader role.
GRANT EXECUTE ON FUNCTION app.actor_holds_role(text[]) TO waypoint_messaging;

-- Every command commits its receipt, its audit row and its events with the change.
GRANT USAGE ON SCHEMA integration TO waypoint_messaging;
GRANT INSERT ON integration.audit_log, integration.outbox_events TO waypoint_messaging;
GRANT SELECT, INSERT ON integration.command_receipts TO waypoint_messaging;
GRANT SELECT, INSERT ON integration.consumed_events TO waypoint_messaging;

-- ---- threads -----------------------------------------------------------------

CREATE TABLE messaging.threads (
    thread_id    uuid PRIMARY KEY,
    subject_type text        NOT NULL,
    subject_id   text        NOT NULL,
    depot_code   text        NOT NULL,
    -- A trip's vehicle and service date, so its driver reads the thread
    -- (app.actor_drives).
    vehicle_id   text,
    service_date date,
    -- The outlets the subject concerns: the trip's stops. A store manager of
    -- one of them is on the thread.
    outlet_ids   text[]      NOT NULL DEFAULT '{}',
    opened_at    timestamptz NOT NULL,
    -- A revision that adds a stop to the same trip widens it; never narrowed,
    -- so nobody loses what they were already told.
    updated_at   timestamptz NOT NULL,
    CONSTRAINT uq_threads_subject UNIQUE (subject_type, subject_id),
    CONSTRAINT ck_threads_subject_type CHECK (subject_type IN ('trip')),
    CONSTRAINT ck_threads_trip CHECK (subject_type <> 'trip' OR (vehicle_id IS NOT NULL AND service_date IS NOT NULL))
);

COMMENT ON TABLE messaging.threads IS
  'One conversation per subject (issue #136). A trip''s thread is opened when its plan is published and keeps its depot, vehicle, date and outlets.';

CREATE INDEX ix_threads_depot_day ON messaging.threads (depot_code, service_date);
CREATE INDEX ix_threads_vehicle_day ON messaging.threads (vehicle_id, service_date);

-- ---- voice notes ---------------------------------------------------------------
--     A voice message or a voice report (issue #136). The phone uploads the audio
--     under its own id first, so a retry is the same note, then posts the message
--     that carries it. Who may listen is who may see that message.

CREATE TABLE messaging.voice_notes (
    voice_note_id uuid PRIMARY KEY,
    thread_id     uuid        NOT NULL REFERENCES messaging.threads (thread_id),
    uploaded_by   uuid        NOT NULL,
    content_type  text        NOT NULL,
    size_bytes    integer     NOT NULL,
    duration_ms   integer,
    sha256        text        NOT NULL,
    content       bytea       NOT NULL,
    uploaded_at   timestamptz NOT NULL,
    CONSTRAINT ck_voice_notes_type CHECK (content_type IN ('audio/webm','audio/ogg','audio/mp4','audio/aac','audio/mpeg')),
    CONSTRAINT ck_voice_notes_size CHECK (size_bytes BETWEEN 1 AND 2097152),
    CONSTRAINT ck_voice_notes_duration CHECK (duration_ms IS NULL OR duration_ms BETWEEN 1 AND 120000)
);

COMMENT ON TABLE messaging.voice_notes IS
  'The audio of a voice message or report. Personal communication: never logged, served only to who may see its message.';

CREATE INDEX ix_voice_notes_thread ON messaging.voice_notes (thread_id);

-- ---- messages ----------------------------------------------------------------

CREATE TABLE messaging.messages (
    message_id        uuid PRIMARY KEY,
    thread_id         uuid        NOT NULL REFERENCES messaging.threads (thread_id),
    author_user_id    uuid        NOT NULL,
    -- How the author belongs to the thread: dispatcher, loader, driver or
    -- store_manager. A report made from an event names the role of the person
    -- who reported it.
    author_role       text        NOT NULL,
    kind              text        NOT NULL,
    report_type       text,
    -- Who it is for besides the dispatcher, who reads everything (R-MSG-01):
    -- dispatch (the dispatcher only), driver, loader, outlet (audience_outlet),
    -- or all (everyone on the trip). Every report is for the dispatcher (R-MSG-03).
    audience          text        NOT NULL,
    audience_outlet   text,
    -- Empty only when the message is a voice note.
    body              text        NOT NULL,
    voice_note_id     uuid        UNIQUE REFERENCES messaging.voice_notes (voice_note_id),
    -- The phone's own id for the message, so a resend from an offline queue is one message.
    client_message_id uuid,
    -- The event a report was made from, so a redelivered event posts nothing.
    source_event_id   uuid,
    command_id        uuid,
    created_at        timestamptz NOT NULL,
    CONSTRAINT ck_messages_role CHECK (author_role IN ('dispatcher','loader','driver','store_manager')),
    CONSTRAINT ck_messages_kind CHECK (kind IN ('message','report')),
    CONSTRAINT ck_messages_report CHECK (
        (kind = 'report') = (report_type IS NOT NULL)
        AND (report_type IS NULL OR report_type IN (
            'loading_shortfall','vehicle_fault','road_disruption','failed_delivery','damaged_goods',
            'receipt_dispute','stock_discrepancy','late','other'))
        AND (kind = 'message' OR audience = 'dispatch')),
    CONSTRAINT ck_messages_audience CHECK (
        audience IN ('dispatch','driver','loader','outlet','all')
        AND ((audience = 'outlet') = (audience_outlet IS NOT NULL))),
    CONSTRAINT ck_messages_body CHECK (
        char_length(body) <= 1000 AND (char_length(body) >= 1 OR voice_note_id IS NOT NULL)),
    CONSTRAINT uq_messages_client UNIQUE (author_user_id, client_message_id),
    CONSTRAINT uq_messages_source UNIQUE (source_event_id)
);

COMMENT ON TABLE messaging.messages IS
  'What people wrote on a thread, and the reports they made. Append only and never deleted. Bodies are personal communication: never logged.';

CREATE INDEX ix_messages_thread ON messaging.messages (thread_id, created_at, message_id);
CREATE INDEX ix_messages_reports ON messaging.messages (thread_id, created_at) WHERE kind = 'report';

-- Messages and voice notes are append only. A thread is opened and widened by the system alone.
GRANT SELECT, INSERT ON messaging.messages, messaging.voice_notes TO waypoint_messaging;
GRANT SELECT, INSERT, UPDATE ON messaging.threads TO waypoint_messaging;

-- ---- row-level security (R-MSG-01) ---------------------------------------------
--     Effective access is policy AND scope. A thread is visible to the depot's
--     dispatcher, the depot's loaders, the vehicle's driver on its date, and a
--     store manager of one of its outlets. Inside it the dispatcher reads every
--     message; anyone else reads messages for everyone, messages addressed to
--     them, and their own.

ALTER TABLE messaging.threads ENABLE ROW LEVEL SECURITY;
ALTER TABLE messaging.threads FORCE ROW LEVEL SECURITY;

CREATE POLICY threads_read ON messaging.threads
    FOR SELECT TO waypoint_messaging
    USING (app.actor_is_system()
           OR app.actor_oversees_depot(depot_code)
           OR (app.actor_has_depot(depot_code) AND app.actor_holds_role(ARRAY['loader']))
           OR (vehicle_id IS NOT NULL AND app.actor_drives(vehicle_id, service_date))
           OR EXISTS (SELECT 1 FROM unnest(outlet_ids) o WHERE app.actor_has_outlet(o)));

CREATE POLICY threads_insert ON messaging.threads
    FOR INSERT TO waypoint_messaging
    WITH CHECK (app.actor_is_system());

CREATE POLICY threads_update ON messaging.threads
    FOR UPDATE TO waypoint_messaging
    USING (app.actor_is_system()) WITH CHECK (app.actor_is_system());

ALTER TABLE messaging.messages ENABLE ROW LEVEL SECURITY;
ALTER TABLE messaging.messages FORCE ROW LEVEL SECURITY;

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
                          AND EXISTS (SELECT 1 FROM unnest(t.outlet_ids) o WHERE app.actor_has_outlet(o))))));

-- Writing: the system, or the author themself on a thread they can read. Who may
-- address whom is the domain's rule (R-MSG-02), checked before the insert.
CREATE POLICY messages_insert ON messaging.messages
    FOR INSERT TO waypoint_messaging
    WITH CHECK (app.actor_is_system()
                OR (author_user_id = app.current_actor()
                    AND EXISTS (SELECT 1 FROM messaging.threads t WHERE t.thread_id = messages.thread_id)));

-- A voice note is heard by its uploader and by whoever may see the message that
-- carries it (that subquery runs under messages_read). The uploader must be on
-- the thread.
ALTER TABLE messaging.voice_notes ENABLE ROW LEVEL SECURITY;
ALTER TABLE messaging.voice_notes FORCE ROW LEVEL SECURITY;

CREATE POLICY voice_notes_read ON messaging.voice_notes
    FOR SELECT TO waypoint_messaging
    USING (app.actor_is_system()
           OR uploaded_by = app.current_actor()
           OR EXISTS (SELECT 1 FROM messaging.messages m WHERE m.voice_note_id = voice_notes.voice_note_id));

CREATE POLICY voice_notes_upload ON messaging.voice_notes
    FOR INSERT TO waypoint_messaging
    WITH CHECK (uploaded_by = app.current_actor()
                AND EXISTS (SELECT 1 FROM messaging.threads t WHERE t.thread_id = voice_notes.thread_id));
