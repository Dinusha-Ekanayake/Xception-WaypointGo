-- 008 Driver assignment: no two drivers on one vehicle at once
--    valid_from/valid_to CHECKs pass individually while the ranges still
--    overlap. This closes it at the database.
--    NOTE: validated against existing rows, so clean overlaps first.
-- ---------------------------------------------------------

ALTER TABLE iam.vehicle_driver_assignments
    ADD COLUMN IF NOT EXISTS validity daterange
        GENERATED ALWAYS AS (daterange(valid_from, valid_to, '[]')) STORED;

-- An EXCLUDE constraint also creates an index, so a re-run raises
-- duplicate_table (42P07), not duplicate_object. Catch both.
DO $$ BEGIN
    ALTER TABLE iam.vehicle_driver_assignments
        ADD CONSTRAINT ex_vehicle_one_driver_at_a_time
        EXCLUDE USING gist (vehicle_id WITH =, validity WITH &&);
EXCEPTION WHEN duplicate_object OR duplicate_table THEN NULL; END $$;

-- DEFERRED, policy decision: should one driver be barred from two vehicles
-- at once? Correct if a driver owns a vehicle for the whole day; wrong if
-- they run one in the Fresh window and another in the trading day.
-- ALTER TABLE iam.vehicle_driver_assignments
--     ADD CONSTRAINT ex_driver_one_vehicle_at_a_time
--     EXCLUDE USING gist (driver_user_id WITH =, validity WITH &&);
