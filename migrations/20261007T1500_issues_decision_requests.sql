-- Roadmap FF-09: live decisions. On the road, the dispatcher asks one store one
-- question and the store answers with a tap: can someone receive at about a
-- given time (RECEIVE_LATE), or can the crew unload a stop's goods at the
-- store's dock to reach the store's own and reload them (DOCK_HELP; the depot
-- loads the last stop first, R-LOD-08). Every name and number on the card is
-- filled by the system from the run sheet. The store answers in the app, or
-- the dispatcher records an answer given by phone. Asked once, answered once,
-- never deleted; asking again about the same stop withdraws the open request.
CREATE TABLE issues.decision_requests (
    request_id        uuid        PRIMARY KEY,
    kind              text        NOT NULL,
    depot_code        text        NOT NULL,
    service_date      date        NOT NULL,
    trip_id           uuid        NOT NULL,
    vehicle_id        text        NOT NULL,
    delivery_id       uuid        NOT NULL,
    outlet_id         text        NOT NULL,
    about_delivery_id uuid,
    about_outlet_id   text,
    about_units       integer,
    receive_at        timestamptz,
    arrival_at        timestamptz,
    deadline          timestamptz NOT NULL,
    state             text        NOT NULL DEFAULT 'sent',
    answer            text,
    answered_via      text,
    answered_by       uuid,
    answered_at       timestamptz,
    answered_late     boolean     NOT NULL DEFAULT false,
    reason            text,
    voice_note_id     uuid,
    asked_by          uuid        NOT NULL,
    asked_at          timestamptz NOT NULL,
    closed_at         timestamptz,
    command_id        uuid        NOT NULL,
    row_version       bigint      NOT NULL DEFAULT 1,
    CONSTRAINT ck_decision_kind CHECK (kind IN ('receive_late', 'dock_help')),
    CONSTRAINT ck_decision_state CHECK (state IN ('sent', 'answered', 'expired', 'withdrawn')),
    CONSTRAINT ck_decision_answer CHECK (
        (answer IS NULL) = (state IN ('sent', 'expired', 'withdrawn'))
        AND (answer IS NULL OR answer IN ('yes', 'no'))
        AND (answer IS NULL) = (answered_by IS NULL)
        AND (answer IS NULL) = (answered_at IS NULL)
        AND (answer IS NULL) = (answered_via IS NULL)
        AND (answered_via IS NULL OR answered_via IN ('app', 'phone'))),
    CONSTRAINT ck_decision_receive_late CHECK (kind <> 'receive_late' OR receive_at IS NOT NULL),
    CONSTRAINT ck_decision_dock_help CHECK (
        kind <> 'dock_help' OR (about_delivery_id IS NOT NULL AND about_outlet_id IS NOT NULL)),
    CONSTRAINT ck_decision_reason CHECK (reason IS NULL OR char_length(reason) BETWEEN 1 AND 500)
);

COMMENT ON TABLE issues.decision_requests IS
  'A question the dispatcher asked one store on a live trip, and its answer (FF-09). Never deleted.';

CREATE INDEX ix_decision_requests_day ON issues.decision_requests (depot_code, service_date);
CREATE INDEX ix_decision_requests_outlet ON issues.decision_requests (outlet_id, service_date);
CREATE INDEX ix_decision_requests_due ON issues.decision_requests (deadline) WHERE state = 'sent';
CREATE UNIQUE INDEX ux_decision_requests_open ON issues.decision_requests (delivery_id, kind) WHERE state = 'sent';

GRANT SELECT, INSERT, UPDATE ON issues.decision_requests TO waypoint_issues;

ALTER TABLE issues.decision_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE issues.decision_requests FORCE ROW LEVEL SECURITY;
-- The dispatcher who oversees the depot reads and asks; the store reads and
-- answers its own outlet's. No other store, and no driver or loader.
CREATE POLICY decision_requests_read ON issues.decision_requests
    FOR SELECT TO waypoint_issues
    USING (app.actor_is_system() OR app.actor_oversees_depot(depot_code) OR app.actor_has_outlet(outlet_id));
CREATE POLICY decision_requests_insert ON issues.decision_requests
    FOR INSERT TO waypoint_issues
    WITH CHECK (app.actor_is_system() OR app.actor_oversees_depot(depot_code));
CREATE POLICY decision_requests_update ON issues.decision_requests
    FOR UPDATE TO waypoint_issues
    USING (app.actor_is_system() OR app.actor_oversees_depot(depot_code) OR app.actor_has_outlet(outlet_id))
    WITH CHECK (app.actor_is_system() OR app.actor_oversees_depot(depot_code) OR app.actor_has_outlet(outlet_id));
