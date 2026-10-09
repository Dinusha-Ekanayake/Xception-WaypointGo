-- Roadmap F-10: a counter bumped by every in-place edit of the current reference
-- version (outlet details, calendar overrides). A version is otherwise
-- immutable, so caches keyed by version alone never saw such an edit: planning
-- kept the old window until a restart, and other replicas kept it for good.
-- Every process compares the counter with the one it loaded and reloads when it
-- moved. One row; never deleted.
CREATE TABLE IF NOT EXISTS ref.generation (
    id    smallint PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    value bigint   NOT NULL DEFAULT 0
);
INSERT INTO ref.generation (id, value) VALUES (1, 0) ON CONFLICT (id) DO NOTHING;

GRANT SELECT, UPDATE ON ref.generation TO waypoint_ref;

COMMENT ON TABLE ref.generation IS
  'Bumped in the same transaction as any in-place edit of reference data (F-10); caches reload when it moves.';
