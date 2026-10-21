-- R-EXE-32 (docs/issues/trip-problems-driver/PLAN.md): the driver reports a
-- closed store or a stop that cannot unload; the dispatcher decides. The stop is
-- not touched. Each report is kept beside it, append only (rule 8); one made after
-- the stop ended (an offline phone, the dispatcher already skipped it) is kept and
-- marked, and announces nothing.

CREATE TABLE IF NOT EXISTS execution.stop_problems (
    report_id    uuid        PRIMARY KEY,
    delivery_id  uuid        NOT NULL REFERENCES execution.delivery_records (delivery_id),
    problem      text        NOT NULL,
    after_finish boolean     NOT NULL,
    reported_by  uuid        NOT NULL,
    command_id   uuid        NOT NULL UNIQUE,
    reported_at  timestamptz NOT NULL,
    CONSTRAINT ck_stop_problems_problem CHECK (problem IN ('store_closed', 'cannot_unload'))
);

CREATE INDEX IF NOT EXISTS ix_stop_problems_delivery ON execution.stop_problems (delivery_id, reported_at);

COMMENT ON TABLE execution.stop_problems IS
  'A driver''s report of a closed store or a stop that cannot unload; the dispatcher decides (R-EXE-32, rule 8).';

GRANT SELECT, INSERT ON execution.stop_problems TO waypoint_execution;

ALTER TABLE execution.stop_problems ENABLE ROW LEVEL SECURITY;
ALTER TABLE execution.stop_problems FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS stop_problems_read ON execution.stop_problems;
CREATE POLICY stop_problems_read ON execution.stop_problems
    FOR SELECT TO waypoint_execution
    USING (EXISTS (SELECT 1 FROM execution.delivery_records d WHERE d.delivery_id = stop_problems.delivery_id));
DROP POLICY IF EXISTS stop_problems_insert ON execution.stop_problems;
CREATE POLICY stop_problems_insert ON execution.stop_problems
    FOR INSERT TO waypoint_execution
    WITH CHECK (EXISTS (SELECT 1 FROM execution.delivery_records d
                        WHERE d.delivery_id = stop_problems.delivery_id
                          AND (app.actor_is_system() OR app.actor_drives(d.vehicle_id, d.service_date))));

-- ---- the action -----------------------------------------------------------------
-- Drivers hold delivery:*, so the grant is already theirs; the auditor's explicit
-- deny names it like every other delivery write.

INSERT INTO iam.action_catalogue (action, module, description, implemented) VALUES
    ('delivery:ReportStopProblem', 'execution', 'Report a closed store or a stop that cannot unload, for the dispatcher to decide', true)
ON CONFLICT (action) DO UPDATE SET implemented = true;

CREATE OR REPLACE FUNCTION pg_temp.problem_add_actions(doc jsonb, target_sid text, wanted text[]) RETURNS jsonb
LANGUAGE sql IMMUTABLE AS $$
    SELECT jsonb_set(
               doc, '{Statement}',
               (SELECT jsonb_agg(
                           CASE WHEN s ->> 'Sid' = target_sid
                                THEN jsonb_set(
                                         s, '{Action}',
                                         (s -> 'Action')
                                         || (SELECT coalesce(jsonb_agg(w), '[]'::jsonb)
                                               FROM unnest(wanted) AS w
                                              WHERE NOT jsonb_exists(s -> 'Action', w)))
                                ELSE s END
                           ORDER BY ord)
                  FROM jsonb_array_elements(doc -> 'Statement') WITH ORDINALITY AS t(s, ord)))
$$;

CREATE TEMP TABLE problem_policy_docs ON COMMIT DROP AS
SELECT p.policy_id,
       pg_temp.problem_add_actions(pv.document, 'NeverWrite', ARRAY['delivery:ReportStopProblem']) AS document,
       pv.document AS previous
FROM iam.policies p
JOIN iam.policy_versions pv ON pv.policy_id = p.policy_id AND pv.is_default
WHERE p.name = 'WaypointAuditor';

DELETE FROM problem_policy_docs WHERE document = previous;

UPDATE iam.policy_versions pv
SET is_default = false
FROM problem_policy_docs d
WHERE pv.policy_id = d.policy_id AND pv.is_default;

INSERT INTO iam.policy_versions (policy_id, version_number, document, is_default)
SELECT d.policy_id,
       (SELECT max(version_number) + 1 FROM iam.policy_versions x WHERE x.policy_id = d.policy_id),
       d.document,
       true
FROM problem_policy_docs d;

-- A policy change clears every cached decision on the next request.
UPDATE iam.policy_generation
SET generation = generation + 1, changed_at = now()
WHERE EXISTS (SELECT 1 FROM problem_policy_docs);
