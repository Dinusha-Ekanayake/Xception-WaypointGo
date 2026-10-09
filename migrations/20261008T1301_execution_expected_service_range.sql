-- Roadmap MF-06: the range around the expected unloading minutes, copied from
-- the plan's stored prediction at release beside the minutes themselves
-- (T1-04, R-ML-20). Expand only.
ALTER TABLE execution.delivery_records ADD COLUMN IF NOT EXISTS expected_service_low numeric(6,2);
ALTER TABLE execution.delivery_records ADD COLUMN IF NOT EXISTS expected_service_high numeric(6,2);
ALTER TABLE execution.delivery_records DROP CONSTRAINT IF EXISTS ck_delivery_records_expected_service_range;
ALTER TABLE execution.delivery_records ADD CONSTRAINT ck_delivery_records_expected_service_range
    CHECK ((expected_service_low IS NULL) = (expected_service_high IS NULL)
           AND (expected_service_low IS NULL OR expected_service_minutes IS NOT NULL)
           AND (expected_service_low IS NULL OR (expected_service_low >= 0 AND expected_service_low <= expected_service_high
                                                 AND expected_service_high <= 600)));
