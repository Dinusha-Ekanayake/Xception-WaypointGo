-- Roadmap T2-03: beside each finished week's forecast, the simplest one it has
-- to beat: the mean weekly volume ordered over the 8 weeks before (R-ML-21).
-- Expand only: empty when fewer than 4 of those weeks had orders.
ALTER TABLE ml.forecast_accuracy ADD COLUMN IF NOT EXISTS baseline_total_m3 numeric(14,4);
ALTER TABLE ml.forecast_accuracy DROP CONSTRAINT IF EXISTS ck_forecast_accuracy_baseline;
ALTER TABLE ml.forecast_accuracy ADD CONSTRAINT ck_forecast_accuracy_baseline
    CHECK (baseline_total_m3 IS NULL OR baseline_total_m3 >= 0);
