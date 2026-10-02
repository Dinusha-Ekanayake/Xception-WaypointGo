-- What the platform scheduler needs from the database (issue #6, PLT-04, PLT-09,
-- PLT-10): a record of each run, and a way to add and detach audit partitions
-- without handing the application role the right to create tables.

-- ---- job runs -----------------------------------------------------------
--     One row per run of a scheduled job, so "did the cutoff run last night" has
--     an answer that does not depend on reading logs. The advisory lease decides
--     who runs; this records that it did.

CREATE TABLE IF NOT EXISTS integration.job_runs (
    run_id      bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job         text        NOT NULL,
    started_at  timestamptz NOT NULL,
    finished_at timestamptz,
    outcome     text        NOT NULL DEFAULT 'running',
    error       text,
    CONSTRAINT ck_job_run_outcome CHECK (outcome IN ('running', 'ok', 'failed'))
);

CREATE INDEX IF NOT EXISTS ix_job_runs_job_started ON integration.job_runs (job, started_at DESC);

COMMENT ON TABLE integration.job_runs IS
  'One row per run of a scheduled job. error holds an exception class, never data.';

-- The schema default grants only SELECT and INSERT (008). A run is finished by
-- an UPDATE, and old rows are purged by retention.
GRANT SELECT, INSERT, UPDATE, DELETE ON integration.job_runs TO waypoint_integration;

-- ---- audit partitions ---------------------------------------------------
--     Every command writes its audit row in its own transaction, so a missing
--     partition fails the command rather than losing the row: on the first day
--     with no partition the whole system stops writing. The scheduler therefore
--     creates them ahead (PLT-09).
--
--     Creating a partition is DDL, and waypoint_app owns nothing and must not be
--     given CREATE on the schema. These two functions are the whole of what it may
--     do: they run as the migration owner, check their arguments, and are
--     executable by the integration role alone.
--
--     Months are UTC. The partition key is timestamptz, and 005 and 008 wrote
--     their bounds as bare dates, which PostgreSQL read in the session time zone;
--     the function pins the zone so the bounds do not depend on who calls it.

CREATE OR REPLACE FUNCTION integration.ensure_audit_partition(p_month date)
RETURNS text
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
SET timezone = 'UTC'
AS $$
DECLARE
    first_day date := date_trunc('month', p_month)::date;
    part_name text := 'audit_log_' || to_char(first_day, 'YYYY_MM');
BEGIN
    IF first_day < date '2026-01-01' OR first_day > (now() + interval '5 years')::date THEN
        RAISE EXCEPTION 'audit partition month % is outside the supported range', first_day;
    END IF;

    IF to_regclass(format('integration.%I', part_name)) IS NULL THEN
        EXECUTE format(
            'CREATE TABLE integration.%I PARTITION OF integration.audit_log'
            ' FOR VALUES FROM (%L) TO (%L)',
            part_name,
            first_day::timestamp AT TIME ZONE 'UTC',
            (first_day + interval '1 month')::date::timestamp AT TIME ZONE 'UTC');
        -- Append only on the partition too (008): it is its own table.
        EXECUTE format(
            'REVOKE UPDATE, DELETE ON integration.%I FROM waypoint_integration', part_name);
    END IF;
    RETURN part_name;
END
$$;

-- Detaching keeps the data: the table survives, outside the audit_log parent, for
-- whoever archives it. Dropping is deliberately not offered here (P-14).
CREATE OR REPLACE FUNCTION integration.detach_audit_partition(p_name text)
RETURNS boolean
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF p_name !~ '^audit_log_[0-9]{4}_[0-9]{2}$' THEN
        RAISE EXCEPTION '% is not an audit partition name', p_name;
    END IF;
    IF NOT EXISTS (
        SELECT 1
          FROM pg_inherits i
          JOIN pg_class c ON c.oid = i.inhrelid
         WHERE i.inhparent = 'integration.audit_log'::regclass
           AND c.relname = p_name) THEN
        RETURN false;
    END IF;
    EXECUTE format('ALTER TABLE integration.audit_log DETACH PARTITION integration.%I', p_name);
    RETURN true;
END
$$;

REVOKE ALL ON FUNCTION integration.ensure_audit_partition(date) FROM PUBLIC;
REVOKE ALL ON FUNCTION integration.detach_audit_partition(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION integration.ensure_audit_partition(date) TO waypoint_integration;
GRANT EXECUTE ON FUNCTION integration.detach_audit_partition(text) TO waypoint_integration;
