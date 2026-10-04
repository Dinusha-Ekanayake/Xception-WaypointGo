-- Test data for manual testing: orders in every status, with their timelines,
-- plus the access the demo accounts need to see them.
--
-- This is NOT how the application creates data. It writes rows directly, as the
-- database owner, so the screens have something to show before Planning,
-- Loading, Execution and Receipt exist to produce it. Nothing here goes through
-- the command bus: there are no command receipts, no audit rows and no outbox
-- events, and every warehouse reference is made up (SEED-WH-...). Cancelling or
-- amending a seeded order from the UI will ask the real warehouse about a
-- reservation it never made.
--
-- Run it by hand, never from a build, a deploy or a request:
--
--   docker compose exec -T db psql -U waypoint -d waypoint -v ON_ERROR_STOP=1 \
--     < scripts/seed-scenarios.sql
--
-- Dates are relative to the day it runs (Asia/Colombo). Running it again changes
-- nothing. To move the data to a new "today", remove it and seed again:
--
--   ... psql -U waypoint -d waypoint -v ON_ERROR_STOP=1 -v reset=1 < scripts/seed-scenarios.sql
--
-- Every seeded order has a reference starting WPO-SEED, which is how reset finds
-- them. Orders placed through the application are never touched.

\set ON_ERROR_STOP on
BEGIN;

\if :{?reset}
DELETE FROM ordering.order_status_history h USING ordering.orders o
 WHERE o.order_id = h.order_id AND o.order_ref LIKE 'WPO-SEED%';
DELETE FROM ordering.order_lines l USING ordering.orders o
 WHERE o.order_id = l.order_id AND o.order_ref LIKE 'WPO-SEED%';
DELETE FROM ordering.orders WHERE order_ref LIKE 'WPO-SEED%' AND redelivery_of IS NOT NULL;
DELETE FROM ordering.orders WHERE order_ref LIKE 'WPO-SEED%';
-- Day closures are left in place: a seeded one cannot be told from a real one.
DELETE FROM ref.vehicle_day_status WHERE reason LIKE 'Seed:%';
\endif

-- ---- helpers --------------------------------------------------------------

-- Operating days as the application sees them: an override, else the supplied
-- calendar, else Monday to Saturday (R-PLN-17).
CREATE FUNCTION pg_temp.is_op(d date) RETURNS boolean LANGUAGE sql STABLE AS $$
  SELECT COALESCE(
    (SELECT o.is_operating FROM ref.calendar_overrides o WHERE o.calendar_date = d),
    (SELECT c.is_operating FROM ref.calendar_days c WHERE c.calendar_date = d LIMIT 1),
    extract(dow FROM d) <> 0)
$$;

-- n operating days after d (n > 0) or before it (n < 0); d itself for 0.
CREATE FUNCTION pg_temp.op(d date, n int) RETURNS date LANGUAGE plpgsql STABLE AS $$
DECLARE
  step int := sign(n);
  remaining int := abs(n);
  cur date := d;
BEGIN
  WHILE remaining > 0 LOOP
    cur := cur + step;
    IF pg_temp.is_op(cur) THEN remaining := remaining - 1; END IF;
  END LOOP;
  RETURN cur;
END $$;

CREATE FUNCTION pg_temp.at(d date, t time) RETURNS timestamptz LANGUAGE sql IMMUTABLE AS $$
  SELECT (d + t) AT TIME ZONE 'Asia/Colombo'
$$;

CREATE FUNCTION pg_temp.order_id(n int) RETURNS uuid LANGUAGE sql IMMUTABLE AS $$
  SELECT md5('waypoint-seed-order-' || n)::uuid
$$;

CREATE FUNCTION pg_temp.order_ref(n int) RETURNS text LANGUAGE sql IMMUTABLE AS $$
  SELECT 'WPO-SEED' || lpad(n::text, 8, '0')
$$;

-- A vehicle of the depot that could legally carry the order.
CREATE FUNCTION pg_temp.pick_vehicle(depot text, chilled boolean, van boolean, h int)
RETURNS text LANGUAGE sql STABLE AS $$
  SELECT (array_agg(vehicle_id ORDER BY vehicle_id))[1 + h % count(*)::int]
    FROM ref.vehicles
   WHERE depot_code = depot
     AND (NOT chilled OR temperature_capability = 'reefer')
     AND (CASE WHEN van THEN vehicle_type = 'van' ELSE vehicle_type <> 'van' END)
$$;

-- The states an order passed through to reach `status`, in order.
CREATE FUNCTION pg_temp.path(status text, variant text, deferrals int) RETURNS text[]
LANGUAGE sql IMMUTABLE AS $$
  SELECT CASE status
    WHEN 'stock_unknown' THEN ARRAY['stock_unknown']
    WHEN 'confirmed' THEN CASE WHEN variant = 'amended' THEN ARRAY['confirmed','confirmed'] ELSE ARRAY['confirmed'] END
    WHEN 'allocated' THEN ARRAY['confirmed','allocated']
    WHEN 'deferred' THEN ARRAY['confirmed'] || array_fill('deferred'::text, ARRAY[greatest(deferrals, 1)])
    WHEN 'unservable' THEN ARRAY['confirmed','unservable']
    WHEN 'loading' THEN ARRAY['confirmed','allocated','loading']
    WHEN 'in_transit' THEN ARRAY['confirmed','allocated','loading','in_transit']
    WHEN 'delivered' THEN ARRAY['confirmed','allocated','loading','in_transit','delivered']
    WHEN 'partially_delivered' THEN ARRAY['confirmed','allocated','loading','in_transit','partially_delivered']
    WHEN 'failed' THEN ARRAY['confirmed','allocated','loading','in_transit','failed']
    WHEN 'received' THEN CASE WHEN variant = 'partial'
      THEN ARRAY['confirmed','allocated','loading','in_transit','partially_delivered','received']
      ELSE ARRAY['confirmed','allocated','loading','in_transit','delivered','received'] END
    WHEN 'unconfirmed' THEN ARRAY['confirmed','allocated','loading','in_transit','delivered','unconfirmed']
    WHEN 'cancelled' THEN CASE WHEN variant = 'after_allocation'
      THEN ARRAY['confirmed','allocated','cancelled'] ELSE ARRAY['confirmed','cancelled'] END
  END
$$;

-- ---- anchors --------------------------------------------------------------
-- t0 is the most recent operating day, so a Sunday run still has a "today".

CREATE TEMP TABLE a ON COMMIT DROP AS
SELECT t0,
       pg_temp.op(t0, 1) AS t1, pg_temp.op(t0, 2) AS t2,
       pg_temp.op(t0, -1) AS p1, pg_temp.op(t0, -2) AS p2,
       pg_temp.op(t0, -3) AS p3, pg_temp.op(t0, -4) AS p4,
       (pg_temp.op(t0, 1) + ((7 - extract(dow FROM pg_temp.op(t0, 1))::int) % 7)
          + CASE WHEN extract(dow FROM pg_temp.op(t0, 1)) = 0 THEN 7 ELSE 0 END)::date AS sunday
  FROM (SELECT CASE WHEN pg_temp.is_op(d) THEN d ELSE pg_temp.op(d, -1) END AS t0
          FROM (SELECT (now() AT TIME ZONE 'Asia/Colombo')::date AS d) x) y;

CREATE TEMP TABLE u ON COMMIT DROP AS
SELECT (SELECT user_id FROM iam.users WHERE email = 'store_manager@waypoint.local') AS store,
       (SELECT user_id FROM iam.users WHERE email = 'dispatcher@waypoint.local') AS dispatcher,
       (SELECT user_id FROM iam.users WHERE email = 'driver@waypoint.local') AS driver,
       (SELECT user_id FROM iam.users WHERE email = 'admin@waypoint.local') AS admin;

-- Outlets of the current reference version, with their depot and a stable hash.
CREATE TEMP TABLE ov ON COMMIT DROP AS
SELECT o.outlet_id, o.brand_code AS brand, o.district_name AS district, d.depot_code AS depot,
       o.parking_constraint = 'van_only' AS van_only,
       abs(hashtext(o.outlet_id) % 1000003) AS h
  FROM ref.outlets o
  JOIN ref.districts d USING (reference_version_id, district_name)
 WHERE o.reference_version_id = (SELECT reference_version_id FROM ref.outlets ORDER BY reference_version_id LIMIT 1);

-- ---- access ---------------------------------------------------------------
-- The store manager gets OUT001, the dispatcher both depots, and the driver the
-- first refrigerated van at Peliyagoda, which is the vehicle the OUT001 orders
-- below are loaded on.

INSERT INTO iam.user_outlet_access (user_id, outlet_id)
SELECT store, 'OUT001' FROM u WHERE store IS NOT NULL
ON CONFLICT DO NOTHING;

INSERT INTO iam.user_depot_access (user_id, depot_code)
SELECT dispatcher, depot_code FROM u CROSS JOIN ref.depots WHERE dispatcher IS NOT NULL
ON CONFLICT DO NOTHING;

INSERT INTO iam.vehicle_driver_assignments (vehicle_id, driver_user_id, validity, assigned_by)
SELECT v.vehicle_id, u.driver, daterange(a.p4, a.t0 + 60), u.admin
  FROM u, a, (SELECT pg_temp.pick_vehicle('Peliyagoda', true, true, 0) AS vehicle_id) v
 WHERE u.driver IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM iam.vehicle_driver_assignments x
                    WHERE x.validity && daterange(a.p4, a.t0 + 60)
                      AND (x.vehicle_id = v.vehicle_id OR x.driver_user_id = u.driver));

-- ---- fleet: several vehicles off the road ---------------------------------

INSERT INTO ref.vehicle_day_status (vehicle_id, service_date, status, reason, set_by)
SELECT v.vehicle_id, d.day, v.status, v.reason, u.dispatcher
  FROM u, a,
       LATERAL (VALUES (a.t0), (a.t1)) d(day),
       LATERAL (
         (SELECT vehicle_id, 'in_workshop' AS status, 'Seed: compressor fault, in workshop' AS reason
            FROM ref.vehicles WHERE depot_code = 'Peliyagoda' AND temperature_capability = 'reefer' AND vehicle_type = 'truck'
           ORDER BY vehicle_id OFFSET 1 LIMIT 2)
         UNION ALL
         (SELECT vehicle_id, 'in_workshop', 'Seed: brake service'
            FROM ref.vehicles WHERE depot_code = 'Peliyagoda' AND temperature_capability = 'ambient' AND vehicle_type = 'truck'
           ORDER BY vehicle_id OFFSET 3 LIMIT 2)
         UNION ALL
         (SELECT vehicle_id, 'unavailable', 'Seed: driver on leave'
            FROM ref.vehicles WHERE depot_code = 'Kandy' AND vehicle_type = 'truck'
           ORDER BY vehicle_id OFFSET 2 LIMIT 1)
       ) v
ON CONFLICT DO NOTHING;

-- ---- what to create -------------------------------------------------------

CREATE TEMP TABLE spec (
  n int PRIMARY KEY,
  outlet_id text NOT NULL,
  status text NOT NULL,
  req date NOT NULL,
  del date NOT NULL,
  temperature text,
  kg numeric(10,3),
  m3 numeric(10,4),
  items int,
  deferrals int NOT NULL DEFAULT 0,
  variant text NOT NULL DEFAULT '',
  redelivery_n int,
  note text NOT NULL DEFAULT ''
) ON COMMIT DROP;

-- One of everything at OUT001, the store manager's outlet. It is van only, so
-- anything over a van's 1200 kg or 9 m3 cannot be served there.
INSERT INTO spec (n, outlet_id, status, req, del, temperature, kg, m3, items, deferrals, variant, redelivery_n, note)
SELECT v.n, 'OUT001', v.status, v.req, v.del, v.temperature, v.kg, v.m3, v.items, v.deferrals, v.variant, v.redelivery_n, v.note
  FROM a, LATERAL (VALUES
    ( 1, 'stock_unknown',       a.t1, a.t1, NULL,      NULL,   NULL,  NULL, 0, '', NULL::int, 'the warehouse did not answer'),
    ( 2, 'confirmed',           a.t1, a.t1, 'ambient',  640.0, 3.20,   96, 0, '', NULL, ''),
    ( 3, 'confirmed',           a.t1, a.t1, 'chilled',  310.0, 1.90,   60, 0, '', NULL, ''),
    ( 4, 'confirmed',           a.t2, a.t2, 'ambient',  420.0, 2.40,   70, 0, 'amended', NULL, ''),
    ( 5, 'confirmed',           a.sunday, pg_temp.op(a.sunday, 1), 'ambient', 380.0, 2.10, 58, 0, '', NULL, ''),
    ( 6, 'allocated',           a.t1, a.t1, 'chilled',  280.0, 1.60,   48, 0, '', NULL, ''),
    ( 7, 'deferred',            a.t0, a.t1, 'chilled',  350.0, 2.20,   64, 1, '', NULL, 'refrigerated van capacity exhausted for Colombo'),
    ( 8, 'deferred',            a.p1, a.t1, 'ambient',  510.0, 2.90,   82, 2, '', NULL, 'no van free inside the 05:00 to 07:30 window'),
    ( 9, 'unservable',          a.t1, a.t1, 'ambient', 1500.0, 10.50, 210, 0, '', NULL, 'larger than any van, and the outlet is van only'),
    (10, 'loading',             a.t0, a.t0, 'ambient',  560.0, 3.00,   88, 0, '', NULL, ''),
    (11, 'in_transit',          a.t0, a.t0, 'chilled',  300.0, 1.80,   52, 0, '', NULL, ''),
    (12, 'delivered',           a.t0, a.t0, 'ambient',  470.0, 2.60,   74, 0, '', NULL, ''),
    (13, 'partially_delivered', a.p1, a.p1, 'chilled',  330.0, 2.00,   56, 0, '', NULL, ''),
    (14, 'failed',              a.p1, a.p1, 'chilled',  290.0, 1.70,   50, 0, '', NULL, 'outlet closed on arrival'),
    (15, 'confirmed',           a.t1, a.t1, 'chilled',  290.0, 1.70,   50, 0, '', 14,   ''),
    (16, 'received',            a.p2, a.p2, 'ambient',  600.0, 3.10,   92, 0, '', NULL, ''),
    (17, 'received',            a.p3, a.p3, 'chilled',  340.0, 2.10,   58, 0, 'partial', NULL, ''),
    (18, 'unconfirmed',         a.p4, a.p4, 'ambient',  450.0, 2.50,   70, 0, '', NULL, ''),
    (19, 'cancelled',           a.t1, a.t1, 'ambient',  400.0, 2.30,   64, 0, '', NULL, 'ordered twice by mistake'),
    (20, 'cancelled',           a.p1, a.p1, 'ambient',  520.0, 2.80,   80, 0, 'after_allocation', NULL, 'store closed for stock take'),
    (21, 'stock_unknown',       a.t2, a.t2, NULL,      NULL,   NULL,  NULL, 0, '', NULL, 'the warehouse did not answer')
  ) v(n, status, req, del, temperature, kg, m3, items, deferrals, variant, redelivery_n, note);

-- Three that need another kind of outlet: heavier than the largest truck, a
-- garment order that fills volume long before weight, and an outlet skipped on
-- three runs in a row.
INSERT INTO spec (n, outlet_id, status, req, del, temperature, kg, m3, items, deferrals, note)
SELECT 22, (SELECT min(outlet_id) FROM ov WHERE brand = 'Tech' AND depot = 'Peliyagoda'),
       'unservable', a.t1, a.t1, 'ambient', 9000.0, 41.00, 14, 0, 'heavier and larger than any vehicle at Peliyagoda' FROM a
UNION ALL
SELECT 23, (SELECT min(o.outlet_id) FROM ov JOIN ref.outlets o USING (outlet_id)
             WHERE ov.brand = 'Style' AND ov.depot = 'Peliyagoda' AND o.mall_window_open IS NOT NULL),
       'confirmed', a.t1, a.t1, 'ambient', 180.0, 14.00, 120, 0, '' FROM a
UNION ALL
SELECT 24, (SELECT min(outlet_id) FROM ov WHERE brand = 'Fresh' AND depot = 'Kandy' AND van_only),
       'deferred', a.p2, a.t1, 'chilled', 380.0, 2.30, 66, 3, 'no refrigerated van free at Kandy' FROM a;

-- Bulk demand, so the dispatcher's day is not one outlet. Sizes follow the
-- brands: Fresh by weight, Style by volume, Tech heavy and few. Peliyagoda's
-- chilled orders for tomorrow are deliberately large against a refrigerated
-- fleet with two trucks in the workshop: a day where demand exceeds capacity.
CREATE FUNCTION pg_temp.kg(brand text, temperature text, h int) RETURNS numeric LANGUAGE sql IMMUTABLE AS $$
  SELECT CASE WHEN brand = 'Fresh' AND temperature = 'chilled' THEN 250 + h % 700
              WHEN brand = 'Fresh' THEN 350 + h % 900
              WHEN brand = 'Style' THEN 90 + h % 220
              ELSE 300 + h % 1400 END::numeric
$$;
CREATE FUNCTION pg_temp.m3(brand text, temperature text, depot text, h int) RETURNS numeric LANGUAGE sql IMMUTABLE AS $$
  SELECT round(CASE WHEN brand = 'Fresh' AND temperature = 'chilled'
                      THEN (250 + h % 700) * CASE WHEN depot = 'Peliyagoda' THEN 0.011 ELSE 0.006 END
                    WHEN brand = 'Fresh' THEN (350 + h % 900) * 0.0045
                    WHEN brand = 'Style' THEN 3.5 + (h % 80) / 10.0
                    ELSE 1.5 + (h % 50) / 10.0 END::numeric, 2)
$$;
CREATE FUNCTION pg_temp.items(brand text, temperature text, h int) RETURNS int LANGUAGE sql IMMUTABLE AS $$
  SELECT CASE WHEN brand = 'Fresh' THEN (pg_temp.kg(brand, temperature, h) / 6)::int
              WHEN brand = 'Style' THEN 20 + h % 80
              ELSE 2 + h % 6 END
$$;

INSERT INTO spec (n, outlet_id, status, req, del, temperature, kg, m3, items, deferrals, variant, note)
SELECT b.base + row_number() OVER (PARTITION BY b.base ORDER BY ov.outlet_id),
       ov.outlet_id, b.status, b.req, b.del, b.temperature,
       pg_temp.kg(ov.brand, b.temperature, ov.h + b.base),
       pg_temp.m3(ov.brand, b.temperature, ov.depot, ov.h + b.base),
       pg_temp.items(ov.brand, b.temperature, ov.h + b.base),
       b.deferrals, b.variant, b.note
  FROM ov, a,
       LATERAL (
         -- Tomorrow: every Fresh outlet orders dry goods, most also chilled.
         SELECT 1000 AS base, 'confirmed' AS status, a.t1 AS req, a.t1 AS del, 'ambient' AS temperature, 0 AS deferrals, '' AS variant, '' AS note
          WHERE ov.brand = 'Fresh'
         UNION ALL
         SELECT 2000, 'confirmed', a.t1, a.t1, 'chilled', 0, '', ''
          WHERE ov.brand = 'Fresh' AND ov.h % 10 < CASE WHEN ov.depot = 'Peliyagoda' THEN 8 ELSE 4 END
         UNION ALL
         SELECT 3000, 'confirmed', a.t1, a.t1, 'ambient', 0, '', ''
          WHERE ov.brand <> 'Fresh' AND ov.h % 10 < 6
         UNION ALL
         -- Today, at every stage of the run.
         SELECT 4000, CASE (ov.h / 3) % 10 WHEN 0 THEN 'loading' WHEN 1 THEN 'in_transit' WHEN 2 THEN 'in_transit'
                        WHEN 3 THEN 'delivered' WHEN 4 THEN 'delivered' WHEN 5 THEN 'partially_delivered'
                        WHEN 6 THEN 'failed' ELSE 'received' END,
                a.t0, a.t0, CASE WHEN ov.brand = 'Fresh' AND ov.h % 2 = 0 THEN 'chilled' ELSE 'ambient' END, 0, '',
                'outlet closed on arrival'
          WHERE ov.h % 3 = 0
         UNION ALL
         -- Yesterday and the day before: mostly received, some never confirmed.
         SELECT 5000, CASE (ov.h / 3) % 10 WHEN 0 THEN 'unconfirmed' WHEN 1 THEN 'failed' ELSE 'received' END,
                a.p1, a.p1, CASE WHEN ov.brand = 'Fresh' AND ov.h % 2 = 0 THEN 'chilled' ELSE 'ambient' END, 0,
                CASE WHEN (ov.h / 3) % 10 = 2 THEN 'partial' ELSE '' END, 'no one at the dock to receive'
          WHERE ov.h % 3 = 1
         UNION ALL
         SELECT 6000, CASE (ov.h / 3) % 10 WHEN 0 THEN 'unconfirmed' ELSE 'received' END,
                a.p2, a.p2, CASE WHEN ov.brand = 'Fresh' AND ov.h % 2 = 0 THEN 'chilled' ELSE 'ambient' END, 0, '', ''
          WHERE ov.h % 3 = 2
         UNION ALL
         -- Skipped by last night's plan and carried to tomorrow.
         SELECT 7000, 'deferred', a.t0, a.t1, CASE WHEN ov.brand = 'Fresh' THEN 'chilled' ELSE 'ambient' END, 1, '',
                CASE WHEN ov.brand = 'Fresh' THEN 'refrigerated capacity exhausted for ' || ov.district
                     ELSE 'vehicle volume exhausted for ' || ov.district END
          WHERE ov.h % 11 = 0
         UNION ALL
         -- The day after tomorrow is still filling up.
         SELECT 8000, 'confirmed', a.t2, a.t2, 'ambient', 0, '', ''
          WHERE ov.h % 5 = 0
       ) b
 WHERE ov.outlet_id <> 'OUT001';

-- ---- staging: everything derived from the spec ----------------------------

CREATE TEMP TABLE st ON COMMIT DROP AS
SELECT s.*, ov.brand, ov.district, ov.depot, ov.van_only,
       abs(hashtext(s.outlet_id || ':' || s.n) % 1000003) AS h,
       pg_temp.order_id(s.n) AS order_id,
       pg_temp.order_ref(s.n) AS order_ref,
       pg_temp.path(s.status, s.variant, s.deferrals) AS path,
       CASE WHEN s.outlet_id = 'OUT001'
            THEN pg_temp.pick_vehicle('Peliyagoda', true, true, 0)
            ELSE pg_temp.pick_vehicle(ov.depot, COALESCE(s.temperature = 'chilled', false), ov.van_only,
                                      abs(hashtext(ov.district || s.del::text) % 1000003)) END AS vehicle_id
  FROM spec s JOIN ov USING (outlet_id);

ALTER TABLE st ADD COLUMN trip_id uuid;
UPDATE st SET trip_id = md5('waypoint-seed-trip-' || vehicle_id || del::text)::uuid
 WHERE 'allocated' = ANY (path) AND status <> 'cancelled';

-- The timeline of each order: one row per state it entered.
CREATE TEMP TABLE hist ON COMMIT DROP AS
SELECT st.n, st.order_id, p.i, array_length(st.path, 1) AS len,
       CASE WHEN p.i > 1 THEN st.path[p.i - 1] END AS from_status,
       st.path[p.i] AS to_status,
       CASE
         WHEN p.i = 1 AND st.redelivery_n IS NOT NULL THEN 'redelivery of ' || pg_temp.order_ref(st.redelivery_n)
         WHEN p.i = 1 AND st.path[1] = 'stock_unknown' THEN 'placed; stock unknown: ' || st.note
         WHEN p.i = 1 THEN 'placed'
         WHEN st.path[p.i] = 'confirmed' THEN 'amended; warehouse reserved'
         WHEN st.path[p.i] = 'allocated' THEN 'allocated to trip ' || md5('waypoint-seed-trip-' || st.vehicle_id || st.del::text)::uuid
         WHEN st.path[p.i] = 'deferred' THEN 'deferred by plan ' || md5('waypoint-seed-plan-' || st.depot || pg_temp.op(st.req, p.i - 3)::text)::uuid
                                             || ' (R-PLN-20): ' || st.note
         WHEN st.path[p.i] = 'unservable' THEN 'unservable (R-PLN-09): ' || st.note
         WHEN st.path[p.i] = 'loading' THEN 'loading started on ' || st.vehicle_id
         WHEN st.path[p.i] = 'in_transit' THEN 'released on ' || st.vehicle_id
         WHEN st.path[p.i] = 'delivered' THEN 'delivered'
         WHEN st.path[p.i] = 'partially_delivered' THEN 'delivered short'
         WHEN st.path[p.i] = 'failed' THEN 'delivery failed: ' || st.note
         WHEN st.path[p.i] = 'received' AND st.variant = 'partial' THEN 'received, partial'
         WHEN st.path[p.i] = 'received' THEN 'received'
         WHEN st.path[p.i] = 'unconfirmed' THEN 'not confirmed within the auto-close window'
         WHEN st.path[p.i] = 'cancelled' THEN 'cancelled: ' || st.note
       END AS reason,
       -- A person acts on their own outlet's order; everything else is the system.
       CASE WHEN st.outlet_id = 'OUT001' AND st.redelivery_n IS NULL
                 AND (p.i = 1 OR st.path[p.i] IN ('confirmed', 'cancelled', 'received'))
            THEN (SELECT store FROM u) END AS actor_id,
       CASE
         WHEN p.i = 1 THEN pg_temp.at(pg_temp.op(st.req, -1), '08:00') + make_interval(mins => st.h % 420)
         WHEN st.path[p.i] = 'confirmed' THEN pg_temp.at(pg_temp.op(st.req, -1), '08:00') + make_interval(mins => st.h % 420 + 45)
         WHEN st.path[p.i] = 'cancelled' AND st.variant = 'after_allocation' THEN pg_temp.at(pg_temp.op(st.del, -1), '18:10')
         WHEN st.path[p.i] = 'cancelled' THEN pg_temp.at(pg_temp.op(st.req, -1), '08:00') + make_interval(mins => st.h % 420 + 90)
         WHEN st.path[p.i] = 'allocated' THEN pg_temp.at(pg_temp.op(st.del, -1), '17:20')
         WHEN st.path[p.i] = 'deferred' THEN pg_temp.at(pg_temp.op(st.req, p.i - 3), '17:25')
         WHEN st.path[p.i] = 'unservable' THEN pg_temp.at(pg_temp.op(st.del, -1), '17:25')
         WHEN st.path[p.i] = 'loading' THEN pg_temp.at(st.del, '04:20') + make_interval(mins => st.h % 40)
         WHEN st.path[p.i] = 'in_transit' THEN pg_temp.at(st.del, '05:10') + make_interval(mins => st.h % 40)
         WHEN st.path[p.i] IN ('delivered', 'partially_delivered', 'failed') THEN pg_temp.at(st.del, '06:00') + make_interval(mins => st.h % 110)
         WHEN st.path[p.i] = 'received' THEN pg_temp.at(st.del, '06:20') + make_interval(mins => st.h % 110 + st.h % 60)
         WHEN st.path[p.i] = 'unconfirmed' THEN pg_temp.at(pg_temp.op(st.del, 1), '08:00')
       END AS wanted_at
  FROM st CROSS JOIN LATERAL generate_subscripts(st.path, 1) AS p(i);

-- Nothing is dated in the future, and the order of events survives the cap.
ALTER TABLE hist ADD COLUMN occurred_at timestamptz;
UPDATE hist SET occurred_at = LEAST(wanted_at, now() - make_interval(mins => 2 + 3 * (len - i)));

-- ---- orders ---------------------------------------------------------------

CREATE TEMP TABLE new_ids (order_id uuid PRIMARY KEY) ON COMMIT DROP;

WITH ins AS (
  INSERT INTO ordering.orders (
      order_id, order_ref, outlet_id, depot_code, brand_code, district_name,
      requested_date, original_requested_date, delivery_date, status,
      warehouse_order_ref, temperature, weight_kg, volume_m3, item_count,
      line_revision, redelivery_of, source_issue_id, trip_id, deferral_count,
      placed_by, placed_at, command_id, row_version, updated_at)
  SELECT st.order_id, st.order_ref, st.outlet_id, st.depot, st.brand, st.district,
         st.req, st.req, st.del, st.status,
         CASE WHEN st.kg IS NOT NULL THEN 'SEED-WH-' || lpad(st.n::text, 6, '0') END,
         st.temperature, st.kg, st.m3, st.items,
         CASE WHEN st.variant = 'amended' THEN 2 ELSE 1 END,
         CASE WHEN st.redelivery_n IS NOT NULL THEN pg_temp.order_id(st.redelivery_n) END,
         CASE WHEN st.redelivery_n IS NOT NULL THEN md5('waypoint-seed-issue-' || st.redelivery_n)::uuid END,
         st.trip_id, st.deferrals,
         CASE WHEN st.outlet_id = 'OUT001' AND st.redelivery_n IS NULL THEN (SELECT store FROM u) END,
         (SELECT min(occurred_at) FROM hist WHERE hist.n = st.n),
         NULL, array_length(st.path, 1),
         (SELECT max(occurred_at) FROM hist WHERE hist.n = st.n)
    FROM st
   -- A redelivery points at its original, so originals go first.
   ORDER BY st.redelivery_n NULLS FIRST, st.n
  ON CONFLICT DO NOTHING
  RETURNING order_id)
INSERT INTO new_ids SELECT order_id FROM ins;

INSERT INTO ordering.order_status_history (order_id, from_status, to_status, reason, actor_id, event_id, occurred_at)
SELECT h.order_id, h.from_status, h.to_status, h.reason, h.actor_id, NULL, h.occurred_at
  FROM hist h JOIN new_ids USING (order_id)
 ORDER BY h.n, h.i;

-- Lines are descriptive only; the order's own weight and volume decide capacity.
-- Names stand in for product ids, as in the store screens' sample catalogue,
-- because the catalogue is a reconstruction with no verified SKUs.
INSERT INTO ordering.order_lines (order_id, revision, product_id, quantity)
SELECT st.order_id, r.revision, l.product_id, l.quantity
  FROM st JOIN new_ids USING (order_id)
 CROSS JOIN LATERAL (
   SELECT generate_series(1, CASE WHEN st.variant = 'amended' THEN 2 ELSE 1 END) AS revision) r
 CROSS JOIN LATERAL (
   SELECT COALESCE(st.items, 40 + st.h % 60) + CASE WHEN st.variant = 'amended' AND r.revision = 1 THEN 24 ELSE 0 END AS total) q
 CROSS JOIN LATERAL (
   SELECT CASE
            WHEN st.brand = 'Style' THEN ARRAY['Hanging garments, rail', 'Garment carton', 'Footwear carton']
            WHEN st.brand = 'Tech' THEN ARRAY['Refrigerator 250 L', 'Washing machine 7 kg', 'LED television 43 in']
            WHEN st.temperature = 'chilled' THEN ARRAY['Fresh milk 1 L', 'Yoghurt 80 g', 'Butter 200 g', 'Cheese slices 200 g']
            ELSE ARRAY['Basmati rice 5 kg', 'Coconut oil 1 L', 'Red lentils 1 kg', 'White sugar 1 kg', 'Ceylon tea 400 g']
          END AS names) c
 CROSS JOIN LATERAL (
   SELECT c.names[1 + (st.h + k) % array_length(c.names, 1)] AS product_id,
          CASE k WHEN 0 THEN greatest(1, (q.total * 6) / 10) ELSE greatest(1, q.total - (q.total * 6) / 10) END AS quantity
     FROM generate_series(0, CASE WHEN q.total > 1 THEN 1 ELSE 0 END) AS k) l;

-- ---- closed days ----------------------------------------------------------
-- Past days are closed for both depots. Today is closed for Peliyagoda only,
-- so "close orders for the day" can still be tried on Kandy. Tomorrow closes by
-- itself at 16:00.

INSERT INTO ordering.day_closures (depot_code, service_date, closed_at, closed_by, order_count)
SELECT d.depot_code, x.day, LEAST(pg_temp.at(pg_temp.op(x.day, -1), '16:00'), now() - interval '1 minute'), NULL,
       (SELECT count(*) FROM ordering.orders o
         WHERE o.depot_code = d.depot_code AND o.delivery_date = x.day AND o.status <> 'cancelled')
  FROM ref.depots d, a, LATERAL (VALUES (a.p2), (a.p1), (a.t0)) x(day)
 WHERE NOT (x.day = a.t0 AND d.depot_code = 'Kandy')
ON CONFLICT DO NOTHING;

-- ---- report ---------------------------------------------------------------

SELECT 'orders created this run' AS what, count(*)::text AS value FROM new_ids
UNION ALL
SELECT 'seeded orders in total', count(*)::text FROM ordering.orders WHERE order_ref LIKE 'WPO-SEED%'
UNION ALL
SELECT 'today is', t0::text FROM a
UNION ALL
SELECT 'driver vehicle', COALESCE((SELECT vehicle_id FROM iam.vehicle_driver_assignments x, u WHERE x.driver_user_id = u.driver ORDER BY assigned_at DESC LIMIT 1), 'none');

SELECT depot_code, delivery_date, status, count(*) AS orders,
       round(sum(weight_kg)) AS kg, round(sum(volume_m3), 1) AS m3
  FROM ordering.orders
 WHERE order_ref LIKE 'WPO-SEED%'
 GROUP BY 1, 2, 3
 ORDER BY 1, 2, 3;

COMMIT;
