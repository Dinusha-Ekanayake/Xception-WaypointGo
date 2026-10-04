-- Saved plans: what a draft looked like at a moment, so a dispatcher can look
-- back at the engine's own plan, return to an earlier one and compare two.
--
-- A snapshot is the plan as the dispatcher saw it (the header, trips, stops and
-- every allocation with its checks and who decided it), kept whole. It is
-- written once and never changed or removed: the module role is granted INSERT
-- and SELECT only. Three kinds: the engine's plan of a generate ('auto'), one
-- the dispatcher saved ('manual'), and the draft a regenerate replaced
-- ('regenerated').
--
-- Restoring one does not edit it. A restore is a new draft that puts the
-- snapshot's placements back and lets the engine place whatever arrived since.

CREATE TABLE planning.snapshots (
    snapshot_id    uuid        PRIMARY KEY,
    depot_code     text        NOT NULL,
    service_date   date        NOT NULL,
    number         integer     NOT NULL CHECK (number > 0),
    label          text        NOT NULL CHECK (length(label) BETWEEN 1 AND 120),
    kind           text        NOT NULL CHECK (kind IN ('auto', 'manual', 'regenerated')),
    source_plan_id uuid        NOT NULL REFERENCES planning.runs (plan_id),
    plan_version   integer     NOT NULL,
    payload        jsonb       NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    created_by     uuid        NOT NULL,
    created_at     timestamptz NOT NULL,
    CONSTRAINT uq_snapshots_number UNIQUE (depot_code, service_date, number)
);

CREATE INDEX ix_snapshots_day ON planning.snapshots (depot_code, service_date, number DESC);
CREATE INDEX ix_snapshots_source ON planning.snapshots (source_plan_id);

GRANT SELECT, INSERT ON planning.snapshots TO waypoint_planning;

ALTER TABLE planning.snapshots ENABLE ROW LEVEL SECURITY;
ALTER TABLE planning.snapshots FORCE ROW LEVEL SECURITY;
CREATE POLICY snapshots_scope ON planning.snapshots FOR ALL TO waypoint_planning
    USING (app.actor_is_system() OR app.actor_has_depot(depot_code))
    WITH CHECK (app.actor_is_system() OR app.actor_has_depot(depot_code));

COMMENT ON TABLE planning.snapshots IS
  'A plan as a dispatcher saw it at a moment. Written once, never changed (INSERT and SELECT only).';

INSERT INTO iam.action_catalogue (action, module, description, implemented) VALUES
    ('plan:SaveSnapshot',    'planning', 'Save a draft plan so it can be compared or returned to', true),
    ('plan:RestoreSnapshot', 'planning', 'Return a draft to a saved plan, keeping what the dispatcher decided', true)
ON CONFLICT (action) DO NOTHING;
