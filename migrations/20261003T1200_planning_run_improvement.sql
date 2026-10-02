-- Issue #92: the engine's second pass, the scarce-fleet re-plan, and what it
-- achieved over the first pass, so the dispatcher can see it on the plan.
--
-- Nullable and additive: a run from before this migration, or from an engine
-- with no second pass, has none. The published-run trigger compares whole rows,
-- so a published plan keeps its summary unchanged like every other column.

ALTER TABLE planning.runs ADD COLUMN improvement jsonb;

ALTER TABLE planning.runs ADD CONSTRAINT ck_runs_improvement_object
    CHECK (improvement IS NULL OR jsonb_typeof(improvement) = 'object');

COMMENT ON COLUMN planning.runs.improvement IS
  'First-pass and final served and deferred counts, chilled volume gained and why the search stopped (issue #92, R-PLN-32).';
