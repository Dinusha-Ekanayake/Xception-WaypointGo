-- 004 Planning run: concurrency, immutability, decision provenance
--    plan_version is the business revision; row_version is the concurrency
--    revision. They are different things and both are needed.
-- ---------------------------------------------------------

ALTER TABLE ops.planning_runs
    ADD COLUMN IF NOT EXISTS row_version bigint NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS superseded_by uuid,
    ADD COLUMN IF NOT EXISTS superseded_at timestamptz,
    ADD COLUMN IF NOT EXISTS reference_version_id uuid,
    ADD COLUMN IF NOT EXISTS policy_version_id uuid;

DO $$ BEGIN
    ALTER TABLE ops.planning_runs
        ADD CONSTRAINT fk_plan_superseded_by FOREIGN KEY (superseded_by)
            REFERENCES ops.planning_runs(planning_run_id);
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
    ALTER TABLE ops.planning_runs
        ADD CONSTRAINT fk_plan_reference_version FOREIGN KEY (reference_version_id)
            REFERENCES ref.reference_versions(reference_version_id);
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
    ALTER TABLE ops.planning_runs
        ADD CONSTRAINT fk_plan_policy_version FOREIGN KEY (policy_version_id)
            REFERENCES ops.policy_versions(policy_version_id);
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

-- A published plan records all three inputs to its decisions, so any
-- historical allocation can be replayed against what was actually in force.
DO $$ BEGIN
    ALTER TABLE ops.planning_runs
        ADD CONSTRAINT ck_published_plan_is_reproducible CHECK (
            status <> 'published'
            OR (reference_version_id IS NOT NULL AND policy_version_id IS NOT NULL
                AND published_at IS NOT NULL AND published_by IS NOT NULL)
        );
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

-- Published plans are immutable. Revision creates a new plan_version that
-- supersedes the old one; it never edits it in place.
CREATE OR REPLACE FUNCTION ops.deny_published_plan_mutation() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.status = 'published' THEN
            RAISE EXCEPTION 'planning run % is published and cannot be deleted', OLD.planning_run_id;
        END IF;
        RETURN OLD;
    END IF;
    IF OLD.status = 'published' THEN
        -- only supersession and the concurrency revision may change
        IF (NEW.depot_id, NEW.service_date, NEW.plan_version, NEW.published_at, NEW.published_by)
           IS DISTINCT FROM
           (OLD.depot_id, OLD.service_date, OLD.plan_version, OLD.published_at, OLD.published_by)
        THEN
            RAISE EXCEPTION 'planning run % is published and is immutable', OLD.planning_run_id;
        END IF;
        IF NEW.status NOT IN ('published','superseded') THEN
            RAISE EXCEPTION 'published planning run % may only move to superseded', OLD.planning_run_id;
        END IF;
    END IF;
    RETURN NEW;
END $$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_planning_runs_immutable ON ops.planning_runs;
CREATE TRIGGER trg_planning_runs_immutable
    BEFORE UPDATE OR DELETE ON ops.planning_runs
    FOR EACH ROW EXECUTE FUNCTION ops.deny_published_plan_mutation();
