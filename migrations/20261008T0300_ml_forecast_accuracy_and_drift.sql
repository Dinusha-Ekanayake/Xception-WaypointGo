-- Roadmap MF-03, second slice (R-ML-13): how far each week's demand forecast
-- was from what was ordered, judged on the forecast made before the week began,
-- and how far the predicted late probabilities drifted (PSI) from the weeks before.

CREATE TABLE IF NOT EXISTS ml.forecast_accuracy (
    depot_code            text          NOT NULL,
    brand_code            text          NOT NULL,
    iso_year              integer       NOT NULL,
    iso_week              smallint      NOT NULL CHECK (iso_week BETWEEN 1 AND 53),
    model_label           text          NOT NULL,
    forecast_generated_at timestamptz   NOT NULL,
    forecast_total_m3     numeric(14,4) NOT NULL,
    actual_total_m3       numeric(14,4) NOT NULL,
    forecast_chilled_m3   numeric(14,4) NOT NULL,
    actual_chilled_m3     numeric(14,4) NOT NULL,
    computed_at           timestamptz   NOT NULL,
    PRIMARY KEY (depot_code, brand_code, iso_year, iso_week)
);

COMMENT ON TABLE ml.forecast_accuracy IS
  'MF-03: each finished week''s forecast, as made before the week, beside what was ordered.';

CREATE TABLE IF NOT EXISTS ml.prediction_drift_daily (
    depot_code    text          NOT NULL,
    day           date          NOT NULL,
    model_label   text          NOT NULL,
    psi           numeric(10,6) NOT NULL,
    reference_n   integer       NOT NULL CHECK (reference_n >= 0),
    current_n     integer       NOT NULL CHECK (current_n >= 0),
    computed_at   timestamptz   NOT NULL,
    PRIMARY KEY (depot_code, day, model_label)
);

COMMENT ON TABLE ml.prediction_drift_daily IS
  'MF-03: PSI of the last 7 days of predicted late probabilities against the 28 days before.';

GRANT SELECT, INSERT, UPDATE ON ml.forecast_accuracy, ml.prediction_drift_daily TO waypoint_ml;

ALTER TABLE ml.forecast_accuracy ENABLE ROW LEVEL SECURITY;
ALTER TABLE ml.forecast_accuracy FORCE ROW LEVEL SECURITY;
CREATE POLICY forecast_accuracy_read ON ml.forecast_accuracy FOR SELECT TO waypoint_ml
    USING (app.actor_is_system() OR app.actor_has_depot(depot_code));
CREATE POLICY forecast_accuracy_write ON ml.forecast_accuracy FOR ALL TO waypoint_ml
    USING (app.actor_is_system()) WITH CHECK (app.actor_is_system());

ALTER TABLE ml.prediction_drift_daily ENABLE ROW LEVEL SECURITY;
ALTER TABLE ml.prediction_drift_daily FORCE ROW LEVEL SECURITY;
CREATE POLICY prediction_drift_read ON ml.prediction_drift_daily FOR SELECT TO waypoint_ml
    USING (app.actor_is_system() OR app.actor_has_depot(depot_code));
CREATE POLICY prediction_drift_write ON ml.prediction_drift_daily FOR ALL TO waypoint_ml
    USING (app.actor_is_system()) WITH CHECK (app.actor_is_system());

CREATE INDEX IF NOT EXISTS ix_delivery_predictions_drift
    ON ml.delivery_predictions (depot_code, model_label, generated_at);
