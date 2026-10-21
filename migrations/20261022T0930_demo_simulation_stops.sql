-- Demo trucks record their own stops (docs/issues/demo-stops/PLAN.md, R-DEMO-04).
-- For each delivery the simulation stops at: the tick it records arrival and the
-- tick it records the delivery. Null for simulations started before; those only drive.
ALTER TABLE demo.simulations ADD COLUMN IF NOT EXISTS stops jsonb;
ALTER TABLE demo.simulations ADD COLUMN IF NOT EXISTS stops_recorded integer NOT NULL DEFAULT 0;
COMMENT ON COLUMN demo.simulations.stops IS
  '[{deliveryId, arrive, done}]: the ticks a simulated truck records arrival and delivery at each stop';
