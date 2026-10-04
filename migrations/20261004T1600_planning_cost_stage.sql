-- Planning v2: the cost stage. What it did to the rules plan (vehicles, trips
-- and litres before and after), or why it did not run, kept on the run like the
-- reefer pass's summary (R-PLN-38, R-PLN-39).
--
-- Nullable and additive: a run from before this migration, or from an engine
-- without the stage, has none.
--
-- And the rules plan the stage replaced is saved beside the draft as a snapshot
-- of a fourth kind, 'rules', so the dispatcher can compare and choose it.

ALTER TABLE planning.runs ADD COLUMN cost_summary jsonb;

ALTER TABLE planning.runs ADD CONSTRAINT ck_runs_cost_summary_object
    CHECK (cost_summary IS NULL OR jsonb_typeof(cost_summary) = 'object');

COMMENT ON COLUMN planning.runs.cost_summary IS
  'Cost stage: rules-plan and final vehicles, trips and litres, iterations, and the trigger or reason it was skipped (R-PLN-38, R-PLN-39).';

ALTER TABLE planning.snapshots DROP CONSTRAINT snapshots_kind_check;
ALTER TABLE planning.snapshots ADD CONSTRAINT snapshots_kind_check
    CHECK (kind IN ('auto', 'manual', 'regenerated', 'rules'));
