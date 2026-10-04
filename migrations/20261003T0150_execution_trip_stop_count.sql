-- How many stops the trip has, kept on each delivery record (Figma store
-- manager "05 Delivery tracking": "Stop 3 of 7").
--
--     A store manager's row-level scope shows only the stops at its own outlet,
--     so the size of the trip cannot be counted at read time. It is counted once
--     at release, from the trip.released event, and copied onto every record of
--     the trip like the window and the unit count are.
--
--     Expand and contract: the column is added nullable. Records of trips
--     released before this migration are backfilled below; a record the
--     backfill missed reads as "unknown" and the screen omits the count.

ALTER TABLE execution.delivery_records ADD COLUMN trip_stop_count integer;

ALTER TABLE execution.delivery_records
    ADD CONSTRAINT ck_delivery_trip_stop_count CHECK (trip_stop_count IS NULL OR trip_stop_count > 0);

COMMENT ON COLUMN execution.delivery_records.trip_stop_count IS
  'Distinct stops on the trip when it was released. Null on records released before it was kept and not backfilled.';

-- The table forces row-level security, which applies to the migrator too, so
-- the backfill lifts the force around the statement and puts it back.
ALTER TABLE execution.delivery_records NO FORCE ROW LEVEL SECURITY;

UPDATE execution.delivery_records d
   SET trip_stop_count = t.stops
  FROM (SELECT trip_id, count(DISTINCT stop_sequence)::integer AS stops
          FROM execution.delivery_records
         GROUP BY trip_id) t
 WHERE d.trip_id = t.trip_id
   AND d.trip_stop_count IS NULL;

ALTER TABLE execution.delivery_records FORCE ROW LEVEL SECURITY;
