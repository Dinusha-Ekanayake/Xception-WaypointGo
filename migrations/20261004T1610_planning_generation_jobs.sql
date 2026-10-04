-- Planning v2: generating a plan is a queued job, not work inside the command.
--
-- plan:Generate records a job and returns at once; a worker claims it, builds
-- the problem in a short transaction, runs the engine with no transaction open
-- and writes the draft in another. So two dispatchers clicking Generate get the
-- same job (one active job per depot and day), a serialization retry never
-- re-runs the engine, and several backend instances share the queue through
-- FOR UPDATE SKIP LOCKED with a lease that a crashed worker lets lapse (R-PLN-41).
--
-- Jobs are never deleted; they end done or failed, with the reason (rule 8).

CREATE TABLE planning.generation_jobs (
    job_id             uuid        PRIMARY KEY,
    depot_code         text        NOT NULL,
    service_date       date        NOT NULL,
    status             text        NOT NULL CHECK (status IN ('queued', 'running', 'done', 'failed')),
    keep_decisions     boolean     NOT NULL DEFAULT false,
    requested_by       uuid        NOT NULL,
    command_id         uuid        NOT NULL,
    attempts           integer     NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    lease_until        timestamptz,
    plan_id            uuid,
    result             jsonb       CHECK (result IS NULL OR jsonb_typeof(result) = 'object'),
    error              text,
    created_at         timestamptz NOT NULL,
    started_at         timestamptz,
    finished_at        timestamptz,
    row_version        bigint      NOT NULL DEFAULT 1
);

-- One active job per depot and day: a second Generate finds the first.
CREATE UNIQUE INDEX uq_generation_jobs_active ON planning.generation_jobs (depot_code, service_date)
    WHERE status IN ('queued', 'running');
CREATE INDEX ix_generation_jobs_claim ON planning.generation_jobs (created_at)
    WHERE status IN ('queued', 'running');
CREATE INDEX ix_generation_jobs_day ON planning.generation_jobs (depot_code, service_date, created_at DESC);

GRANT SELECT, INSERT, UPDATE ON planning.generation_jobs TO waypoint_planning;

ALTER TABLE planning.generation_jobs ENABLE ROW LEVEL SECURITY;
ALTER TABLE planning.generation_jobs FORCE ROW LEVEL SECURITY;
CREATE POLICY generation_jobs_scope ON planning.generation_jobs FOR ALL TO waypoint_planning
    USING (app.actor_is_system() OR app.actor_has_depot(depot_code))
    WITH CHECK (app.actor_is_system() OR app.actor_has_depot(depot_code));

COMMENT ON TABLE planning.generation_jobs IS
  'Queued plan generations: one active per depot and day, claimed with SKIP LOCKED and a lease (R-PLN-41).';
