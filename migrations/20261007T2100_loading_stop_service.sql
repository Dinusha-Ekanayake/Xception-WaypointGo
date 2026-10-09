-- Roadmap T1-03: the planned minutes at each stop, carried from the plan to the
-- released trip so Execution can work out when the vehicle reaches the next
-- stop. Expand only: nullable, as stops written before it have none.
ALTER TABLE loading.stops ADD COLUMN IF NOT EXISTS planned_service_minutes integer;
ALTER TABLE loading.stops DROP CONSTRAINT IF EXISTS ck_stops_planned_service;
ALTER TABLE loading.stops ADD CONSTRAINT ck_stops_planned_service
    CHECK (planned_service_minutes IS NULL OR planned_service_minutes BETWEEN 0 AND 600);
