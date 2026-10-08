-- Roadmap T1-02: each likely-late notice given for a published stop, once per
-- order, and who it went to (R-ML-22). The store's weekly count is read from
-- here, so a rerun or a second replica never tells anyone twice.
CREATE TABLE IF NOT EXISTS ml.late_notices (
    order_id      uuid          PRIMARY KEY,
    plan_id       uuid          NOT NULL,
    outlet_id     text          NOT NULL,
    depot_code    text          NOT NULL,
    service_date  date          NOT NULL,
    late_prob     numeric(6,4)  NOT NULL CHECK (late_prob BETWEEN 0 AND 1),
    model_label   text          NOT NULL,
    audience      text          NOT NULL CHECK (audience IN ('dispatcher', 'store')),
    noticed_at    timestamptz   NOT NULL
);

COMMENT ON TABLE ml.late_notices IS
  'T1-02: one likely-late notice per order, to the dispatcher or the store.';

CREATE INDEX IF NOT EXISTS ix_late_notices_outlet_day ON ml.late_notices (outlet_id, service_date);
CREATE INDEX IF NOT EXISTS ix_late_notices_depot_day ON ml.late_notices (depot_code, service_date);

GRANT SELECT, INSERT ON ml.late_notices TO waypoint_ml;

ALTER TABLE ml.late_notices ENABLE ROW LEVEL SECURITY;
ALTER TABLE ml.late_notices FORCE ROW LEVEL SECURITY;
CREATE POLICY late_notices_read ON ml.late_notices
    FOR SELECT TO waypoint_ml
    USING (app.actor_is_system() OR app.actor_has_depot(depot_code));
CREATE POLICY late_notices_write ON ml.late_notices
    FOR INSERT TO waypoint_ml
    WITH CHECK (app.actor_is_system());

-- Roadmap MF-04, T1-02: the threshold of each model's late flag, frozen once
-- from its backtest and never moved (R-ML-22). A model with no row here is
-- told about at the provisional threshold.
CREATE TABLE IF NOT EXISTS ml.late_thresholds (
    model_label      text          PRIMARY KEY,
    threshold        numeric(4,2)  NOT NULL CHECK (threshold > 0 AND threshold < 1),
    flagged          integer       NOT NULL CHECK (flagged >= 0),
    late             integer       NOT NULL CHECK (late >= 0 AND late <= flagged),
    precision_lower  numeric(5,4)  NOT NULL CHECK (precision_lower BETWEEN 0 AND 1),
    labelled         integer       NOT NULL CHECK (labelled >= flagged),
    window_from      date          NOT NULL,
    window_to        date          NOT NULL,
    frozen_at        timestamptz   NOT NULL
);

COMMENT ON TABLE ml.late_thresholds IS
  'MF-04, T1-02: one frozen late-flag threshold per model, chosen once from its backtest.';

GRANT SELECT, INSERT ON ml.late_thresholds TO waypoint_ml;

ALTER TABLE ml.late_thresholds ENABLE ROW LEVEL SECURITY;
ALTER TABLE ml.late_thresholds FORCE ROW LEVEL SECURITY;
CREATE POLICY late_thresholds_read ON ml.late_thresholds
    FOR SELECT TO waypoint_ml USING (true);
CREATE POLICY late_thresholds_write ON ml.late_thresholds
    FOR INSERT TO waypoint_ml WITH CHECK (app.actor_is_system());
