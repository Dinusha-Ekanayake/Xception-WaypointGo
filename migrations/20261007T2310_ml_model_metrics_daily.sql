-- Roadmap MF-03: how each model did each day, per depot, from the outcomes kept
-- beside its predictions (ml.delivery_outcomes, R-ML-12). One row per depot,
-- day and model label, recomputed while late records can still arrive (R-ML-13).
CREATE TABLE IF NOT EXISTS ml.model_metrics_daily (
    depot_code     text          NOT NULL,
    service_date   date          NOT NULL,
    model_label    text          NOT NULL,
    stops          integer       NOT NULL CHECK (stops >= 0),
    labelled       integer       NOT NULL CHECK (labelled >= 0 AND labelled <= stops),
    brier          numeric(8,6),
    log_loss       numeric(10,6),
    cal_slope      numeric(10,4),
    cal_intercept  numeric(10,4),
    service_mae    numeric(8,2),
    service_n      integer       NOT NULL CHECK (service_n >= 0),
    computed_at    timestamptz   NOT NULL,
    PRIMARY KEY (depot_code, service_date, model_label)
);

COMMENT ON TABLE ml.model_metrics_daily IS
  'MF-03: daily Brier, log loss, calibration and service-minute error per depot and model. Lateness from labelled stops only.';

GRANT SELECT, INSERT, UPDATE ON ml.model_metrics_daily TO waypoint_ml;

ALTER TABLE ml.model_metrics_daily ENABLE ROW LEVEL SECURITY;
ALTER TABLE ml.model_metrics_daily FORCE ROW LEVEL SECURITY;
CREATE POLICY model_metrics_daily_read ON ml.model_metrics_daily
    FOR SELECT TO waypoint_ml
    USING (app.actor_is_system() OR app.actor_has_depot(depot_code));
CREATE POLICY model_metrics_daily_write ON ml.model_metrics_daily
    FOR ALL TO waypoint_ml
    USING (app.actor_is_system()) WITH CHECK (app.actor_is_system());
