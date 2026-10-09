-- Roadmap B-05: ml:RecordKpiWeek, the dispatcher lead or depot manager recording
-- a week's business KPI figures from paper and spreadsheets (decisions are recorded).
--
-- A new action needs a catalogue row and a handler (R-IAM). Only the dispatcher
-- policy gets it; row-level security limits it to the depots the dispatcher
-- oversees. The administrator already holds every action.

INSERT INTO iam.action_catalogue (action, module, description, implemented) VALUES
    ('ml:RecordKpiWeek', 'ml', 'Record a depot''s weekly business KPI figures for the pilot baseline', true)
ON CONFLICT (action) DO UPDATE SET implemented = true;

-- A policy version is immutable: the dispatcher policy gets a new default
-- version with one more statement, written once even if this runs against a
-- database that already has it.
CREATE OR REPLACE FUNCTION pg_temp.grant_kpi_week(doc jsonb) RETURNS jsonb
LANGUAGE sql IMMUTABLE AS $$
    SELECT CASE
        WHEN EXISTS (SELECT 1 FROM jsonb_array_elements(doc -> 'Statement') s
                      WHERE s ->> 'Sid' = 'KpiWeeks')
        THEN doc
        ELSE jsonb_set(doc, '{Statement}',
                       (doc -> 'Statement') || jsonb_build_array(jsonb_build_object(
                           'Sid', 'KpiWeeks',
                           'Effect', 'Allow',
                           'Action', jsonb_build_array('ml:RecordKpiWeek'),
                           'Resource', jsonb_build_array('wpt:ml:kpi:*'))))
    END
$$;

CREATE TEMP TABLE kpi_policy_docs ON COMMIT DROP AS
SELECT p.policy_id,
       pg_temp.grant_kpi_week(pv.document) AS document,
       pv.document AS previous
FROM iam.policies p
JOIN iam.policy_versions pv ON pv.policy_id = p.policy_id AND pv.is_default
WHERE p.name = 'WaypointDispatcher';

DELETE FROM kpi_policy_docs WHERE document = previous;

UPDATE iam.policy_versions pv
SET is_default = false
FROM kpi_policy_docs d
WHERE pv.policy_id = d.policy_id AND pv.is_default;

INSERT INTO iam.policy_versions (policy_id, version_number, document, is_default)
SELECT d.policy_id,
       (SELECT max(version_number) + 1 FROM iam.policy_versions x WHERE x.policy_id = d.policy_id),
       d.document,
       true
FROM kpi_policy_docs d;

-- A policy change clears every cached decision on the next request.
UPDATE iam.policy_generation
SET generation = generation + 1, changed_at = now()
WHERE EXISTS (SELECT 1 FROM kpi_policy_docs);
