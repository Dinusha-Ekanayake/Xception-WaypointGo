-- Local demo data on top of the init seed, so the named accounts in
-- scripts/demo-accounts.csv open on a filled screen. Run by the `seed` service
-- of compose.yaml after `init`, as the database's own account. Local copies
-- only: a deployment grants scope through the application's commands.
--
-- Safe to repeat: every statement skips what is already there.

-- The named store manager sees the seeded outlet and its orders, as
-- store_manager@waypoint.local does.
INSERT INTO iam.user_outlet_access (user_id, outlet_id)
SELECT u.user_id, 'OUT001'
FROM iam.users u
WHERE u.email = 'ransikaj@waypoint.local'
ON CONFLICT DO NOTHING;

-- The named driver drives the seeded day's vehicles. A vehicle has one driver a
-- day, so the assignments move from driver@waypoint.local, who keeps none.
UPDATE iam.vehicle_driver_assignments a
SET driver_user_id = named.user_id,
    row_version = a.row_version + 1
FROM iam.users named, iam.users demo
WHERE named.email = 'rashmikadilshan@waypoint.local'
  AND demo.email = 'driver@waypoint.local'
  AND a.driver_user_id = demo.user_id;
