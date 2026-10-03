-- Who decided an allocation and whether it is held in place, so the dispatcher
-- sees which orders were placed or kept by hand (rule 8: a decision carries an
-- actor and a time) and a regenerate can keep the orders that were locked.
--
-- Additive. A constant default is a catalogue change in PostgreSQL 16, not a
-- table rewrite, and a row from before this migration reads as the engine's
-- and unlocked, which is what it was. The published-run trigger compares whole
-- runs, not allocations, and children are frozen by require_draft_parent, so a
-- published plan keeps these columns as written.

ALTER TABLE planning.allocations
    ADD COLUMN source     text        NOT NULL DEFAULT 'engine',
    ADD COLUMN locked     boolean     NOT NULL DEFAULT false,
    ADD COLUMN decided_by uuid,
    ADD COLUMN decided_at timestamptz;

ALTER TABLE planning.allocations ADD CONSTRAINT ck_allocations_source
    CHECK (source IN ('engine', 'override', 'swap', 'kept', 'manual_defer', 'restored'));

-- An engine decision nobody touched has no deciding person. Anything else, a
-- dispatcher's placement or only a lock on an engine placement, names who and when.
ALTER TABLE planning.allocations ADD CONSTRAINT ck_allocations_decider
    CHECK ((decided_by IS NULL) = (decided_at IS NULL)
       AND ((source = 'engine' AND NOT locked) = (decided_by IS NULL)));

-- Only an order on a trip can be held there.
ALTER TABLE planning.allocations ADD CONSTRAINT ck_allocations_lock_served
    CHECK (NOT locked OR decision = 'served');

COMMENT ON COLUMN planning.allocations.source IS
  'engine, or the dispatcher decision that placed or kept the order: override, swap, kept, manual_defer, restored.';
COMMENT ON COLUMN planning.allocations.locked IS
  'A served order the dispatcher holds on its trip; a regenerate keeps it there.';
