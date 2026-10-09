-- Roadmap MF-02: what happened at each stop, joined to what was predicted for
-- it, so a model is measured and retrained on outcomes (R-ML-12). One row per
-- delivery, refreshed while the day's record can still change. A missing or
-- untrusted arrival leaves late_label NULL: no label, never "not late".
CREATE TABLE IF NOT EXISTS ml.delivery_outcomes (
    delivery_id         uuid PRIMARY KEY,
    order_id            uuid          NOT NULL,
    outlet_id           text          NOT NULL,
    depot_code          text          NOT NULL,
    service_date        date          NOT NULL,
    stop_sequence       integer       NOT NULL,
    outcome             text          NOT NULL,
    arrived_at          timestamptz,
    completed_at        timestamptz,
    late_minutes        integer,
    service_minutes     numeric(8,2),
    late_label          boolean,
    label_source        text          NOT NULL CHECK (label_source IN ('device', 'none')),
    prediction_id       uuid,
    predicted_late_prob numeric(6,4),
    predicted_service   numeric(8,2),
    model_label         text,
    recorded_at         timestamptz   NOT NULL,
    CONSTRAINT ck_outcome_label CHECK ((late_label IS NULL) = (label_source = 'none'))
);

COMMENT ON TABLE ml.delivery_outcomes IS
  'MF-02: each stop''s outcome beside its prediction. late_label NULL means no trustworthy arrival, never not late.';

CREATE INDEX IF NOT EXISTS ix_delivery_outcomes_day ON ml.delivery_outcomes (depot_code, service_date);
CREATE INDEX IF NOT EXISTS ix_delivery_outcomes_order ON ml.delivery_outcomes (order_id);

GRANT SELECT, INSERT, UPDATE ON ml.delivery_outcomes TO waypoint_ml;

ALTER TABLE ml.delivery_outcomes ENABLE ROW LEVEL SECURITY;
ALTER TABLE ml.delivery_outcomes FORCE ROW LEVEL SECURITY;
-- Read by those who oversee the depot; written by the process.
CREATE POLICY delivery_outcomes_read ON ml.delivery_outcomes
    FOR SELECT TO waypoint_ml
    USING (app.actor_is_system() OR app.actor_has_depot(depot_code));
CREATE POLICY delivery_outcomes_write ON ml.delivery_outcomes
    FOR ALL TO waypoint_ml
    USING (app.actor_is_system()) WITH CHECK (app.actor_is_system());
