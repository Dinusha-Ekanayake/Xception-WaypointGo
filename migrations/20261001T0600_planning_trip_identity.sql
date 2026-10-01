-- Planning revisions (issue #9, step 6).
--
--     A trip keeps its id across plan versions while it carries the same orders,
--     even when it moves to a substitute vehicle, so Loading's state and
--     Ordering's trip_id survive a revision (PLN-04, R-LOD-06). The id is
--     therefore unique within a plan, not across plans.
--
--     One open draft per depot and day: generating, revising or replanning
--     cancels the open draft first, and the index makes that the only way.
--
--     A revision records why it was made, for plan.revised (rule 8).

ALTER TABLE planning.allocations DROP CONSTRAINT allocations_trip_id_fkey;
ALTER TABLE planning.route_legs  DROP CONSTRAINT route_legs_trip_id_fkey;
ALTER TABLE planning.allocations DROP CONSTRAINT uq_allocations_stop;
ALTER TABLE planning.route_legs  DROP CONSTRAINT route_legs_pkey;
ALTER TABLE planning.trips       DROP CONSTRAINT trips_pkey;

ALTER TABLE planning.trips      ADD CONSTRAINT trips_pkey PRIMARY KEY (plan_id, trip_id);
ALTER TABLE planning.route_legs ADD CONSTRAINT route_legs_pkey PRIMARY KEY (plan_id, trip_id, leg_sequence);
ALTER TABLE planning.allocations
    ADD CONSTRAINT fk_allocations_trip FOREIGN KEY (plan_id, trip_id) REFERENCES planning.trips (plan_id, trip_id);
ALTER TABLE planning.route_legs
    ADD CONSTRAINT fk_route_legs_trip FOREIGN KEY (plan_id, trip_id) REFERENCES planning.trips (plan_id, trip_id);
ALTER TABLE planning.allocations
    ADD CONSTRAINT uq_allocations_stop UNIQUE (plan_id, trip_id, stop_sequence);

CREATE INDEX ix_trips_trip ON planning.trips (trip_id);
DROP INDEX planning.ix_allocations_trip;
CREATE INDEX ix_allocations_trip ON planning.allocations (plan_id, trip_id) WHERE trip_id IS NOT NULL;

COMMENT ON COLUMN planning.trips.trip_id IS
  'Stable across versions while the trip carries the same orders, whichever vehicle carries it (PLN-04, R-LOD-06).';

CREATE UNIQUE INDEX uq_runs_one_draft ON planning.runs (depot_code, service_date) WHERE status = 'draft';

ALTER TABLE planning.runs ADD COLUMN revision_reason text;
ALTER TABLE planning.runs ADD CONSTRAINT ck_runs_revision_reason
    CHECK (supersedes IS NOT NULL OR revision_reason IS NULL);

UPDATE iam.action_catalogue SET implemented = true
 WHERE action IN ('plan:Revise', 'plan:Replan');
