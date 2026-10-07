-- Roadmap T1-03: the planned minutes at the stop, from trip.released, for the
-- arrival estimate of the stops after it. Expand only: nullable, as deliveries
-- released before it have none and are estimated as before.
ALTER TABLE execution.delivery_records ADD COLUMN IF NOT EXISTS planned_service_minutes integer;
ALTER TABLE execution.delivery_records DROP CONSTRAINT IF EXISTS ck_delivery_records_planned_service;
ALTER TABLE execution.delivery_records ADD CONSTRAINT ck_delivery_records_planned_service
    CHECK (planned_service_minutes IS NULL OR planned_service_minutes BETWEEN 0 AND 600);
