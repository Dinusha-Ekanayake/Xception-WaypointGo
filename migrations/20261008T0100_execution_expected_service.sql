-- Roadmap T1-04: the expected minutes at each stop, from the plan's stored
-- prediction (the active model's, or the deterministic estimate's), taken at
-- release. The arrival estimate uses them for the time spent at a stop; the
-- plan's own minutes still give the planned drive between stops. Expand only.
ALTER TABLE execution.delivery_records ADD COLUMN IF NOT EXISTS expected_service_minutes numeric(6,2);
ALTER TABLE execution.delivery_records ADD COLUMN IF NOT EXISTS expected_service_basis text;
ALTER TABLE execution.delivery_records DROP CONSTRAINT IF EXISTS ck_delivery_records_expected_service;
ALTER TABLE execution.delivery_records ADD CONSTRAINT ck_delivery_records_expected_service
    CHECK ((expected_service_minutes IS NULL) = (expected_service_basis IS NULL)
           AND (expected_service_minutes IS NULL OR expected_service_minutes BETWEEN 0 AND 600));
