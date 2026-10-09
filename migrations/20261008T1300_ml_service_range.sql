-- Roadmap MF-06: a range around each stop's expected unloading minutes, from
-- how the same predictor missed on earlier days (R-ML-20). Expand only: empty
-- when there were too few earlier stops to say anything.
ALTER TABLE ml.delivery_predictions ADD COLUMN IF NOT EXISTS service_low numeric(8,2);
ALTER TABLE ml.delivery_predictions ADD COLUMN IF NOT EXISTS service_high numeric(8,2);
ALTER TABLE ml.delivery_predictions ADD COLUMN IF NOT EXISTS service_range_basis text;
ALTER TABLE ml.delivery_predictions DROP CONSTRAINT IF EXISTS ck_delivery_predictions_service_range;
ALTER TABLE ml.delivery_predictions ADD CONSTRAINT ck_delivery_predictions_service_range
    CHECK ((service_low IS NULL) = (service_high IS NULL)
           AND (service_low IS NULL) = (service_range_basis IS NULL)
           AND (service_low IS NULL OR (service_low >= 0 AND service_low <= service_high))
           AND (service_range_basis IS NULL OR service_range_basis IN ('outlet', 'depot', 'all')));
