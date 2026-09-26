-- 012 Return cost for the vehicle pool
--     The weekly fuel quota under-counts without the return leg.
-- ---------------------------------------------------------

ALTER TABLE ops.vehicle_trip_fuel_usage
    ADD COLUMN IF NOT EXISTS return_distance_km numeric(10,3) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS return_litres numeric(12,3) NOT NULL DEFAULT 0;

DO $$ BEGIN
    ALTER TABLE ops.vehicle_trip_fuel_usage ADD CONSTRAINT ck_trip_return_nonnegative
        CHECK (return_distance_km >= 0 AND return_litres >= 0);
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

CREATE OR REPLACE VIEW ops.vehicle_week_fuel AS
SELECT t.vehicle_id,
       cd.iso_year,
       cd.iso_week,
       SUM(f.distance_km + f.return_distance_km)                    AS total_distance_km,
       SUM(COALESCE(f.actual_litres, f.estimated_litres) + f.return_litres) AS total_litres,
       MAX(v.weekly_fuel_quota_l)                                   AS quota_litres
FROM ops.vehicle_trip_fuel_usage f
JOIN ops.trips t          ON t.trip_id = f.trip_id
JOIN ops.planning_runs pr ON pr.planning_run_id = t.planning_run_id
JOIN ref.calendar_days cd ON cd.calendar_date = pr.service_date
JOIN ref.vehicles v       ON v.vehicle_id = t.vehicle_id
GROUP BY t.vehicle_id, cd.iso_year, cd.iso_week;
