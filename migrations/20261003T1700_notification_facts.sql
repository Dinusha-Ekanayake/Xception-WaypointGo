-- The values each notification's title and body were filled from (issue #118),
-- so a client can show the same message in its own language: the loader in
-- Sinhala and Tamil. Additive and nullable; rows written before it have none
-- and are shown as written.
ALTER TABLE notification.notifications ADD COLUMN IF NOT EXISTS facts jsonb;
