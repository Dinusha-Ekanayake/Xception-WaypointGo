-- The attention watch (issue #268): what on a live trip needs the dispatcher.
-- AttentionWatchJob raises an item once per stop and kind, refreshes it while
-- the condition holds, and ends it when the condition goes away. Nothing is
-- deleted: an item reaches a terminal state, cleared or acknowledged, with who
-- and when (decisions are recorded).
--
-- Thresholds are data, per depot; a depot with no row gets the defaults in
-- AttentionThresholds. The heartbeat says when the watch last looked at a
-- depot, so a stalled watch never reads as a calm day.

CREATE TABLE ml.attention_items (
    delivery_id      uuid        NOT NULL,
    kind             text        NOT NULL CHECK (kind IN ('FAILED_STOP', 'WINDOW_AT_RISK', 'RUNNING_LATE', 'PROOF_OWED')),
    severity         text        NOT NULL CHECK (severity IN ('CRITICAL', 'HIGH', 'MEDIUM')),
    depot_code       text        NOT NULL,
    service_date     date        NOT NULL,
    vehicle_id       text        NOT NULL,
    trip_id          uuid        NOT NULL,
    outlet_id        text        NOT NULL,
    minutes_left     integer,
    raised_at        timestamptz NOT NULL,
    last_seen_at     timestamptz NOT NULL,
    reminded_count   integer     NOT NULL DEFAULT 0 CHECK (reminded_count >= 0),
    last_reminded_at timestamptz,
    cleared_at       timestamptz,
    acknowledged_at  timestamptz,
    acknowledged_by  uuid,
    ack_reason       text,
    row_version      bigint      NOT NULL DEFAULT 1,
    PRIMARY KEY (delivery_id, kind),
    CONSTRAINT ck_attention_ack CHECK ((acknowledged_at IS NULL) = (acknowledged_by IS NULL))
);

CREATE INDEX attention_items_open ON ml.attention_items (depot_code, service_date)
    WHERE cleared_at IS NULL AND acknowledged_at IS NULL;

CREATE TABLE ml.attention_thresholds (
    depot_code           text    PRIMARY KEY,
    late_minutes         integer NOT NULL CHECK (late_minutes >= 1),
    window_warn_minutes  integer NOT NULL CHECK (window_warn_minutes >= 1),
    proof_grace_minutes  integer NOT NULL CHECK (proof_grace_minutes >= 0),
    remind_after_minutes integer NOT NULL CHECK (remind_after_minutes >= 1),
    max_reminders        integer NOT NULL CHECK (max_reminders >= 0)
);

CREATE TABLE ml.attention_heartbeats (
    depot_code text        PRIMARY KEY,
    checked_at timestamptz NOT NULL,
    vehicles   integer     NOT NULL CHECK (vehicles >= 0)
);

GRANT SELECT, INSERT, UPDATE ON ml.attention_items, ml.attention_thresholds, ml.attention_heartbeats TO waypoint_ml;

ALTER TABLE ml.attention_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE ml.attention_items FORCE ROW LEVEL SECURITY;
CREATE POLICY attention_items_scope ON ml.attention_items
    FOR ALL TO waypoint_ml
    USING (app.actor_is_system() OR app.actor_has_depot(depot_code))
    WITH CHECK (app.actor_is_system() OR app.actor_has_depot(depot_code));

ALTER TABLE ml.attention_thresholds ENABLE ROW LEVEL SECURITY;
ALTER TABLE ml.attention_thresholds FORCE ROW LEVEL SECURITY;
CREATE POLICY attention_thresholds_scope ON ml.attention_thresholds
    FOR ALL TO waypoint_ml
    USING (app.actor_is_system() OR app.actor_has_depot(depot_code))
    WITH CHECK (app.actor_is_system());

ALTER TABLE ml.attention_heartbeats ENABLE ROW LEVEL SECURITY;
ALTER TABLE ml.attention_heartbeats FORCE ROW LEVEL SECURITY;
CREATE POLICY attention_heartbeats_scope ON ml.attention_heartbeats
    FOR ALL TO waypoint_ml
    USING (app.actor_is_system() OR app.actor_has_depot(depot_code))
    WITH CHECK (app.actor_is_system());
