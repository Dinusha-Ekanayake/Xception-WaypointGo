-- Planning's tables (issue #9, step 4). Schema and role came with 20260930T1200.
--
--     A run is one depot and service day. Its children (trips, allocations,
--     deferrals, legs, fuel) are written while it is a draft and frozen once it
--     is published: a change is a new run that supersedes the old one, never an
--     edit (R-PLN-28). The trigger below enforces that for every role,
--     including the owner.
--
--     depot_code is copied onto every child so row-level security is one
--     predicate per table, with no join back to the run.
--
--     Rule parameters and priority keys are effective-dated data, not code, so
--     changing a budget or reordering priority is a new version, not a release
--     (POL-01, decision 6). Every run stamps the three versions it was built on.
--
--     Orders, outlets and vehicles are referenced by id. Outlets and vehicles
--     point into the reference registry, the only foreign keys a module may hold
--     outside its own schema (D-B). Orders belong to Ordering: no foreign key.

-- ---- rule sets and priority policies -------------------------------------

CREATE TABLE planning.rule_sets (
    rule_set_id    uuid PRIMARY KEY,
    effective_from date        NOT NULL,
    effective_to   date,
    note           text        NOT NULL,
    created_at     timestamptz NOT NULL,
    created_by     uuid,
    CONSTRAINT ck_rule_sets_range CHECK (effective_to IS NULL OR effective_to > effective_from),
    -- One rule set in force on any date.
    CONSTRAINT ex_rule_sets_overlap EXCLUDE USING gist (
        daterange(effective_from, effective_to, '[)') WITH &&)
);

COMMENT ON TABLE planning.rule_sets IS
  'Effective-dated rule parameters. Immutable except closing effective_to when a successor starts.';

CREATE TABLE planning.rule_parameters (
    rule_set_id     uuid          NOT NULL REFERENCES planning.rule_sets (rule_set_id),
    parameter_key   text          NOT NULL,
    parameter_value numeric(14,6) NOT NULL,
    unit            text          NOT NULL,
    PRIMARY KEY (rule_set_id, parameter_key)
);

COMMENT ON COLUMN planning.rule_parameters.parameter_key IS
  'A RuleSet constant, for example fresh.budget.min. A key the domain reads but the set lacks refuses the run (POL-10).';

CREATE TABLE planning.policy_versions (
    policy_version_id uuid PRIMARY KEY,
    kind              text        NOT NULL,
    keys              jsonb       NOT NULL,
    -- Null is every depot. A depot-scoped version wins over the global one on
    -- that depot, which is canary rollout with no special machinery (POL-08).
    depot_code        text,
    effective_from    date        NOT NULL,
    effective_to      date,
    note              text        NOT NULL,
    created_at        timestamptz NOT NULL,
    created_by        uuid,
    CONSTRAINT ck_policy_versions_kind CHECK (kind IN ('deferral_priority')),
    CONSTRAINT ck_policy_versions_keys CHECK (jsonb_typeof(keys) = 'array' AND jsonb_array_length(keys) > 0),
    CONSTRAINT ck_policy_versions_range CHECK (effective_to IS NULL OR effective_to > effective_from),
    CONSTRAINT ex_policy_versions_overlap EXCLUDE USING gist (
        kind WITH =,
        (coalesce(depot_code, '*')) WITH =,
        daterange(effective_from, effective_to, '[)') WITH &&)
);

COMMENT ON TABLE planning.policy_versions IS
  'Effective-dated decision tables. keys is the ordered PriorityPolicy.Key list, highest priority first (R-PLN-21).';

-- ---- runs ------------------------------------------------------------------

CREATE TABLE planning.runs (
    plan_id                    uuid PRIMARY KEY,
    depot_code                 text        NOT NULL,
    service_date               date        NOT NULL,
    plan_version               integer     NOT NULL,
    row_version                bigint      NOT NULL DEFAULT 1,
    status                     text        NOT NULL,
    reference_version_id       uuid        NOT NULL REFERENCES ref.reference_versions (reference_version_id),
    rule_set_id                uuid        NOT NULL REFERENCES planning.rule_sets (rule_set_id),
    priority_policy_version_id uuid        NOT NULL REFERENCES planning.policy_versions (policy_version_id),
    supersedes                 uuid        REFERENCES planning.runs (plan_id),
    demand_fingerprint         text        NOT NULL,
    stale                      boolean     NOT NULL DEFAULT false,
    partial                    boolean     NOT NULL DEFAULT false,
    engine                     text        NOT NULL,
    planned_without_predictor  boolean     NOT NULL DEFAULT true,
    generated_at               timestamptz NOT NULL,
    generated_by               uuid        NOT NULL,
    command_id                 uuid,
    published_at               timestamptz,
    published_by               uuid,
    updated_at                 timestamptz NOT NULL,
    CONSTRAINT ck_runs_status CHECK (status IN ('draft','published','superseded','cancelled')),
    CONSTRAINT ck_runs_plan_version CHECK (plan_version >= 1),
    CONSTRAINT ck_runs_supersedes_self CHECK (supersedes IS DISTINCT FROM plan_id),
    -- A plan that was ever published says when and by whom (rule 8).
    CONSTRAINT ck_runs_published_stamp CHECK (
        (status IN ('published','superseded')) = (published_at IS NOT NULL AND published_by IS NOT NULL)),
    CONSTRAINT uq_runs_version UNIQUE (depot_code, service_date, plan_version)
);

COMMENT ON TABLE planning.runs IS
  'One planning run for a depot and day. plan_version is the business revision, row_version the concurrency revision.';
COMMENT ON COLUMN planning.runs.demand_fingerprint IS
  'Hash of the demand the run was built on. Publication refuses when current demand hashes differently (PLN-07).';

-- One current plan per depot and day.
CREATE UNIQUE INDEX uq_runs_one_published ON planning.runs (depot_code, service_date)
    WHERE status = 'published';
CREATE INDEX ix_runs_depot_day   ON planning.runs (depot_code, service_date, plan_version DESC);
CREATE INDEX ix_runs_supersedes  ON planning.runs (supersedes) WHERE supersedes IS NOT NULL;
CREATE INDEX ix_runs_reference   ON planning.runs (reference_version_id);
CREATE INDEX ix_runs_rule_set    ON planning.runs (rule_set_id);
CREATE INDEX ix_runs_policy      ON planning.runs (priority_policy_version_id);

-- ---- children --------------------------------------------------------------

CREATE TABLE planning.trips (
    trip_id           uuid PRIMARY KEY,
    plan_id           uuid          NOT NULL REFERENCES planning.runs (plan_id),
    depot_code        text          NOT NULL,
    vehicle_id        text          NOT NULL REFERENCES ref.vehicle_registry (vehicle_id),
    -- At least 1; the ceiling is P-03 in the rule set, read by TripCount, not a CHECK.
    trip_number       smallint      NOT NULL,
    brand_code        text          NOT NULL,
    district_name     text          NOT NULL,
    temperature       text          NOT NULL,
    weight_kg         numeric(10,3) NOT NULL,
    volume_m3         numeric(10,4) NOT NULL,
    planned_minutes   numeric(8,2)  NOT NULL,
    planned_departure time          NOT NULL,
    litres            numeric(10,3) NOT NULL,
    CONSTRAINT ck_trips_number CHECK (trip_number >= 1),
    CONSTRAINT ck_trips_temperature CHECK (temperature IN ('chilled','ambient')),
    CONSTRAINT ck_trips_measures CHECK (weight_kg >= 0 AND volume_m3 >= 0 AND planned_minutes >= 0 AND litres >= 0),
    CONSTRAINT uq_trips_vehicle_number UNIQUE (plan_id, vehicle_id, trip_number)
);

COMMENT ON COLUMN planning.trips.planned_minutes IS
  'Booklet formula: outbound + inter-stop x (stops - 1) + allowances, no return leg (R-PLN-08, A-26).';
COMMENT ON COLUMN planning.trips.litres IS
  'Including the return leg, because fuel is physical (R-PLN-24, D-K).';

CREATE INDEX ix_trips_vehicle ON planning.trips (vehicle_id);

CREATE TABLE planning.allocations (
    plan_id         uuid         NOT NULL REFERENCES planning.runs (plan_id),
    order_id        uuid         NOT NULL,
    depot_code      text         NOT NULL,
    outlet_id       text         NOT NULL REFERENCES ref.outlet_registry (outlet_id),
    decision        text         NOT NULL,
    trip_id         uuid         REFERENCES planning.trips (trip_id),
    stop_sequence   integer,
    planned_arrival time,
    window_open     time,
    window_close    time,
    service_minutes numeric(6,2) NOT NULL,
    binding_rule    text,
    reason          text         NOT NULL,
    checks          jsonb        NOT NULL DEFAULT '[]',
    PRIMARY KEY (plan_id, order_id),
    CONSTRAINT ck_allocations_decision CHECK (decision IN ('served','deferred','unservable')),
    CONSTRAINT ck_allocations_served_on_trip CHECK (
        (decision = 'served') = (trip_id IS NOT NULL)
        AND (trip_id IS NULL) = (stop_sequence IS NULL)
        AND (trip_id IS NULL) = (planned_arrival IS NULL)),
    -- R-PLN-19: a deferral or an unservable order names its rule, never a generic message.
    CONSTRAINT ck_allocations_binding_rule CHECK (decision = 'served' OR binding_rule IS NOT NULL),
    CONSTRAINT ck_allocations_sequence CHECK (stop_sequence IS NULL OR stop_sequence >= 1),
    CONSTRAINT ck_allocations_checks CHECK (jsonb_typeof(checks) = 'array'),
    CONSTRAINT uq_allocations_stop UNIQUE (trip_id, stop_sequence)
);

COMMENT ON COLUMN planning.allocations.checks IS
  'Every constraint evaluated for this decision, with its slack: the UI is one of the registry''s four readers.';

CREATE INDEX ix_allocations_order  ON planning.allocations (order_id);
CREATE INDEX ix_allocations_trip   ON planning.allocations (trip_id) WHERE trip_id IS NOT NULL;
CREATE INDEX ix_allocations_outlet ON planning.allocations (outlet_id);

CREATE TABLE planning.deferrals (
    plan_id      uuid        NOT NULL REFERENCES planning.runs (plan_id),
    order_id     uuid        NOT NULL,
    depot_code   text        NOT NULL,
    outlet_id    text        NOT NULL REFERENCES ref.outlet_registry (outlet_id),
    service_date date        NOT NULL,
    rule_id      text        NOT NULL,
    reason       text        NOT NULL,
    skip_count   integer     NOT NULL,
    actor_id     uuid        NOT NULL,
    deferred_at  timestamptz NOT NULL,
    PRIMARY KEY (plan_id, order_id),
    CONSTRAINT ck_deferrals_skip_count CHECK (skip_count >= 1)
);

COMMENT ON TABLE planning.deferrals IS
  'Who deferred which order, why, and how often it has now been skipped (rule 8, R-PLN-20). The engine is the system actor.';

CREATE INDEX ix_deferrals_day    ON planning.deferrals (depot_code, service_date);
CREATE INDEX ix_deferrals_order  ON planning.deferrals (order_id);
CREATE INDEX ix_deferrals_outlet ON planning.deferrals (outlet_id);

CREATE TABLE planning.route_legs (
    trip_id           uuid         NOT NULL REFERENCES planning.trips (trip_id),
    leg_sequence      integer      NOT NULL,
    plan_id           uuid         NOT NULL REFERENCES planning.runs (plan_id),
    depot_code        text         NOT NULL,
    -- Null is the depot: the first leg leaves it. There is no return leg (A-26).
    from_outlet_id    text         REFERENCES ref.outlet_registry (outlet_id),
    to_outlet_id      text         NOT NULL REFERENCES ref.outlet_registry (outlet_id),
    planned_departure time         NOT NULL,
    planned_arrival   time         NOT NULL,
    planned_minutes   numeric(8,2) NOT NULL,
    PRIMARY KEY (trip_id, leg_sequence),
    CONSTRAINT ck_route_legs_sequence CHECK (leg_sequence >= 1),
    CONSTRAINT ck_route_legs_minutes CHECK (planned_minutes >= 0)
);

COMMENT ON TABLE planning.route_legs IS
  'Planned times only. Actual times belong to Execution.';

CREATE INDEX ix_route_legs_plan ON planning.route_legs (plan_id);
CREATE INDEX ix_route_legs_from ON planning.route_legs (from_outlet_id);
CREATE INDEX ix_route_legs_to   ON planning.route_legs (to_outlet_id);

CREATE TABLE planning.fuel_usage (
    plan_id       uuid          NOT NULL REFERENCES planning.runs (plan_id),
    vehicle_id    text          NOT NULL REFERENCES ref.vehicle_registry (vehicle_id),
    depot_code    text          NOT NULL,
    service_date  date          NOT NULL,
    week_starting date          NOT NULL,
    litres        numeric(10,3) NOT NULL,
    PRIMARY KEY (plan_id, vehicle_id),
    CONSTRAINT ck_fuel_usage_monday CHECK (extract(isodow FROM week_starting) = 1),
    CONSTRAINT ck_fuel_usage_week CHECK (service_date >= week_starting AND service_date < week_starting + 7),
    CONSTRAINT ck_fuel_usage_litres CHECK (litres >= 0)
);

COMMENT ON TABLE planning.fuel_usage IS
  'Litres per vehicle per run. Weekly usage counts published runs only; a draft never consumes quota (D-K, B15).';

CREATE INDEX ix_fuel_usage_week ON planning.fuel_usage (vehicle_id, week_starting);

-- ---- immutability ----------------------------------------------------------
-- Adapted from the design-only docs/architecture/schema/migrations/004.

-- Rule sets and policy versions are immutable; the only change is closing one
-- when its successor starts. Parameters never change at all.
CREATE FUNCTION planning.deny_version_edit() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_TABLE_NAME = 'rule_parameters' THEN
        RAISE EXCEPTION 'rule parameters are immutable; create a new rule set'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF (to_jsonb(NEW) - 'effective_to') IS DISTINCT FROM (to_jsonb(OLD) - 'effective_to')
       OR OLD.effective_to IS NOT NULL THEN
        RAISE EXCEPTION '% is immutable; only an open effective_to may be closed', TG_TABLE_NAME
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER trg_rule_sets_immutable BEFORE UPDATE ON planning.rule_sets
    FOR EACH ROW EXECUTE FUNCTION planning.deny_version_edit();
CREATE TRIGGER trg_rule_parameters_immutable BEFORE UPDATE ON planning.rule_parameters
    FOR EACH ROW EXECUTE FUNCTION planning.deny_version_edit();
CREATE TRIGGER trg_policy_versions_immutable BEFORE UPDATE ON planning.policy_versions
    FOR EACH ROW EXECUTE FUNCTION planning.deny_version_edit();

-- A published run may change in exactly one way: to superseded, with its row
-- version and timestamp. Superseded and cancelled are terminal. A draft is free,
-- because the legal state graph is the domain's to enforce.
CREATE FUNCTION planning.deny_published_run_edit() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status IN ('superseded','cancelled') THEN
        RAISE EXCEPTION 'plan % is % and cannot change', OLD.plan_id, OLD.status
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF OLD.status = 'published' THEN
        IF NEW.status <> 'superseded'
           OR (to_jsonb(NEW) - 'status' - 'row_version' - 'updated_at')
              IS DISTINCT FROM (to_jsonb(OLD) - 'status' - 'row_version' - 'updated_at') THEN
            RAISE EXCEPTION 'plan % is published and immutable; revise it into a new version (R-PLN-28)', OLD.plan_id
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER trg_runs_immutable BEFORE UPDATE ON planning.runs
    FOR EACH ROW EXECUTE FUNCTION planning.deny_published_run_edit();

-- Children are written only while their run is a draft. The parent lookup runs
-- as the invoking role, so row-level security applies to it too: a run the
-- actor cannot see is a run it cannot add to.
CREATE FUNCTION planning.require_draft_parent() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    parent_status text;
BEGIN
    SELECT status INTO parent_status FROM planning.runs WHERE plan_id = NEW.plan_id;
    IF parent_status IS DISTINCT FROM 'draft' THEN
        RAISE EXCEPTION 'plan % is %, not a draft; its % are frozen', NEW.plan_id,
            coalesce(parent_status, 'not visible'), TG_TABLE_NAME
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF TG_OP = 'UPDATE' AND NEW.plan_id IS DISTINCT FROM OLD.plan_id THEN
        RAISE EXCEPTION '% rows cannot move between plans', TG_TABLE_NAME
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER trg_trips_draft_only BEFORE INSERT OR UPDATE ON planning.trips
    FOR EACH ROW EXECUTE FUNCTION planning.require_draft_parent();
CREATE TRIGGER trg_allocations_draft_only BEFORE INSERT OR UPDATE ON planning.allocations
    FOR EACH ROW EXECUTE FUNCTION planning.require_draft_parent();
CREATE TRIGGER trg_deferrals_draft_only BEFORE INSERT OR UPDATE ON planning.deferrals
    FOR EACH ROW EXECUTE FUNCTION planning.require_draft_parent();
CREATE TRIGGER trg_route_legs_draft_only BEFORE INSERT OR UPDATE ON planning.route_legs
    FOR EACH ROW EXECUTE FUNCTION planning.require_draft_parent();
CREATE TRIGGER trg_fuel_usage_draft_only BEFORE INSERT OR UPDATE ON planning.fuel_usage
    FOR EACH ROW EXECUTE FUNCTION planning.require_draft_parent();

-- ---- seeds -------------------------------------------------------------------
-- Policy configuration, not operational data, so seeded here as the IAM
-- policies are. Values equal RuleSet.bookletParameters() and
-- PriorityPolicy.DEFAULT_KEYS; PlanningSchemaIntegrationTest holds them equal.

INSERT INTO planning.rule_sets (rule_set_id, effective_from, note, created_at)
VALUES ('01923c00-0000-7000-8000-000000000001', DATE '2026-01-01',
        'Booklet parameters P-01 to P-08, P-12, P-15, P-17 to P-19 (issue #9)', now());

INSERT INTO planning.rule_parameters (rule_set_id, parameter_key, parameter_value, unit) VALUES
    ('01923c00-0000-7000-8000-000000000001', 'fresh.budget.min',                 270,      'min'),
    ('01923c00-0000-7000-8000-000000000001', 'daytime.budget.min',               480,      'min'),
    ('01923c00-0000-7000-8000-000000000001', 'trips.max.per.vehicle',            2,        'trips'),
    ('01923c00-0000-7000-8000-000000000001', 'capacity.epsilon',                 0.000001, 'ratio'),
    ('01923c00-0000-7000-8000-000000000001', 'fresh.departure.minute.of.day',    210,      'min'),
    ('01923c00-0000-7000-8000-000000000001', 'daytime.departure.minute.of.day',  480,      'min'),
    ('01923c00-0000-7000-8000-000000000001', 'escalation.skips',                 1,        'runs'),
    ('01923c00-0000-7000-8000-000000000001', 'strict.window.min',                120,      'min'),
    ('01923c00-0000-7000-8000-000000000001', 'cadence.days.Fresh',               1,        'days'),
    ('01923c00-0000-7000-8000-000000000001', 'cadence.days.Style',               7,        'days'),
    ('01923c00-0000-7000-8000-000000000001', 'cadence.days.Tech',                1,        'days'),
    ('01923c00-0000-7000-8000-000000000001', 'engine.budget.ms',                 10000,    'ms');

INSERT INTO planning.policy_versions (policy_version_id, kind, keys, effective_from, note, created_at)
VALUES ('01923c00-0000-7000-8000-000000000002', 'deferral_priority',
        '["PRIOR_SKIP","FRESH","CHILLED","STRICT_WINDOW","CADENCE","EARLIEST_CLOSE","LONGEST_DISTANCE","LARGEST_VOLUME","LONGEST_UNSERVED"]',
        DATE '2026-01-01', 'Decision 1 of the issue #9 plan (R-PLN-21, revised)', now());

-- ---- privileges --------------------------------------------------------------
-- Default privileges from 20260930T1200 already grant these; stated here so the
-- intent does not depend on who ran which migration. Never DELETE.

GRANT SELECT, INSERT, UPDATE ON planning.rule_sets, planning.rule_parameters, planning.policy_versions,
                                planning.runs, planning.trips, planning.allocations, planning.deferrals,
                                planning.route_legs, planning.fuel_usage
    TO waypoint_planning;
-- A parameter never changes, so an attempt is a permission error rather than an
-- UPDATE that row-level security quietly narrows to zero rows.
REVOKE UPDATE ON planning.rule_parameters FROM waypoint_planning;

-- ---- row-level security ------------------------------------------------------
-- Operational rows: the dispatcher's depots, or the process itself. FORCE so
-- the owner is held to it as well.

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['runs','trips','allocations','deferrals','route_legs','fuel_usage'] LOOP
        EXECUTE format('ALTER TABLE planning.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE planning.%I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY %I ON planning.%I FOR ALL TO waypoint_planning'
            ' USING (app.actor_is_system() OR app.actor_has_depot(depot_code))'
            ' WITH CHECK (app.actor_is_system() OR app.actor_has_depot(depot_code))',
            t || '_scope', t);
    END LOOP;
END $$;

-- Configuration is read by every planning reader: a dispatcher has to see which
-- budget deferred an order. Writing a global version is reserved to the process
-- until an authoring command exists; a depot-scoped canary may be written by
-- someone scoped to that depot.
ALTER TABLE planning.rule_sets ENABLE ROW LEVEL SECURITY;
ALTER TABLE planning.rule_sets FORCE ROW LEVEL SECURITY;
CREATE POLICY rule_sets_read ON planning.rule_sets FOR SELECT TO waypoint_planning USING (true);
CREATE POLICY rule_sets_write ON planning.rule_sets FOR INSERT TO waypoint_planning
    WITH CHECK (app.actor_is_system());
CREATE POLICY rule_sets_close ON planning.rule_sets FOR UPDATE TO waypoint_planning
    USING (app.actor_is_system()) WITH CHECK (app.actor_is_system());

ALTER TABLE planning.rule_parameters ENABLE ROW LEVEL SECURITY;
ALTER TABLE planning.rule_parameters FORCE ROW LEVEL SECURITY;
CREATE POLICY rule_parameters_read ON planning.rule_parameters FOR SELECT TO waypoint_planning USING (true);
CREATE POLICY rule_parameters_write ON planning.rule_parameters FOR INSERT TO waypoint_planning
    WITH CHECK (app.actor_is_system());

ALTER TABLE planning.policy_versions ENABLE ROW LEVEL SECURITY;
ALTER TABLE planning.policy_versions FORCE ROW LEVEL SECURITY;
CREATE POLICY policy_versions_read ON planning.policy_versions FOR SELECT TO waypoint_planning USING (true);
CREATE POLICY policy_versions_write ON planning.policy_versions FOR INSERT TO waypoint_planning
    WITH CHECK (app.actor_is_system() OR (depot_code IS NOT NULL AND app.actor_has_depot(depot_code)));
CREATE POLICY policy_versions_close ON planning.policy_versions FOR UPDATE TO waypoint_planning
    USING (app.actor_is_system() OR (depot_code IS NOT NULL AND app.actor_has_depot(depot_code)))
    WITH CHECK (app.actor_is_system() OR (depot_code IS NOT NULL AND app.actor_has_depot(depot_code)));

-- ---- catalogue -----------------------------------------------------------------
-- PlanController now enforces plan:Read. The command actions stay unimplemented
-- until their handlers land (step 5).

UPDATE iam.action_catalogue SET implemented = true WHERE action = 'plan:Read';
