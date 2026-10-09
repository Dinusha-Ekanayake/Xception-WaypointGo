-- Roadmap FF-09, R-ISS-15: the system asks a store by itself when the attention
-- watch finds a stop at risk. On by default; a depot's operations turns it off
-- by a new parameter row with 'off'. issues.parameters forces row-level
-- security with no insert policy, so the force is lifted around the insert.
ALTER TABLE issues.parameters NO FORCE ROW LEVEL SECURITY;
INSERT INTO issues.parameters (parameter_key, parameter_value, effective_from, note) VALUES
    ('decision.auto_ask', 'on', DATE '2026-01-01', 'FF-09: ask the store by itself when a stop is at risk (on or off)')
ON CONFLICT DO NOTHING;
ALTER TABLE issues.parameters FORCE ROW LEVEL SECURITY;
