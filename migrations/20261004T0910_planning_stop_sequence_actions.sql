-- A dispatcher can fix the order of a trip's stops, and decide more about a
-- draft than placing and deferring: swap two orders, keep deferrals, hold an
-- order on its trip, tell a store its order cannot be served (issue: plan screen
-- against the design).
--
-- manual_sequence says the stop order was chosen by a person rather than by the
-- timeline, so a later edit of the same draft keeps it. Additive: a constant
-- default is a catalogue change in PostgreSQL 16, and every earlier trip read
-- as the timeline's.

ALTER TABLE planning.trips ADD COLUMN manual_sequence boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN planning.trips.manual_sequence IS
  'True when a dispatcher fixed the stop order; the stop sequence on the allocations is then theirs, not the timeline''s.';

-- A new action needs a catalogue row and a handler (R-IAM). The handlers land
-- with this migration, so the rows are implemented. The dispatcher's policy
-- grants plan:*; the auditor is denied by default, as for every write.
INSERT INTO iam.action_catalogue (action, module, description, implemented) VALUES
    ('plan:Swap',         'planning', 'Swap a served order for a deferred one on its trip',      true),
    ('plan:KeepDeferred', 'planning', 'Decide that deferred orders stay deferred, with a reason', true),
    ('plan:Lock',         'planning', 'Hold an order on its trip so a regenerate keeps it',      true),
    ('plan:Unlock',       'planning', 'Let a held order go again',                                true),
    ('plan:ReorderStops', 'planning', 'Fix the order of a trip''s stops',                         true),
    ('plan:ContactStore', 'planning', 'Tell an outlet''s store manager about an order the plan could not serve', true)
ON CONFLICT (action) DO NOTHING;
