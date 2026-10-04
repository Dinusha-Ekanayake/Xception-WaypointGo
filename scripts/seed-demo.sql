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
