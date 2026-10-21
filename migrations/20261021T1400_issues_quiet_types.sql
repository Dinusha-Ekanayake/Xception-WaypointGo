-- R-ISS-16: goods problems handled at the day's end. Missing or broken goods
-- found at loading, at unloading or by the store do not alert the dispatcher and
-- do not escalate; they show on Live for today and wait in the Issues list.
-- A depot's operations changes the list with a new parameter row ('none' for no
-- quiet type). Effective from the same day as the other issue parameters, so the
-- policy stays complete for every day it is read (POL-10). issues.parameters
-- forces row-level security with no insert policy, so the force is lifted
-- around the insert.
ALTER TABLE issues.parameters NO FORCE ROW LEVEL SECURITY;
INSERT INTO issues.parameters (parameter_key, parameter_value, effective_from, note) VALUES
    ('quiet.types', 'LOADING_SHORTFALL,DAMAGED_GOODS,STOCK_DISCREPANCY', DATE '2026-01-01',
     'R-ISS-16: goods problems handled at the day''s end, without alert or escalation')
ON CONFLICT DO NOTHING;
ALTER TABLE issues.parameters FORCE ROW LEVEL SECURITY;
