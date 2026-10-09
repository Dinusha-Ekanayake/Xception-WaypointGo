-- Roadmap T2-01: ml:RecordCapacityDecision, the dispatcher recording what they
-- decided about a short week on the capacity plan, with a reason (decisions are recorded).
--
-- A new action needs a catalogue row and a handler (R-IAM). Only the dispatcher
-- policy gets it; row-level security limits it to the depots the dispatcher
-- oversees. The administrator already holds every action.

INSERT INTO iam.action_catalogue (action, module, description, implemented) VALUES
    ('ml:RecordCapacityDecision', 'ml', 'Record a decision about a week the capacity plan shows short, with a reason', true)
ON CONFLICT (action) DO UPDATE SET implemented = true;

-- A policy version is immutable: the dispatcher policy gets a new default
-- version with one more statement, written once even if this runs against a
-- database that already has it.
CREATE OR REPLACE FUNCTION pg_temp.grant_capacity_decision(doc jsonb) RETURNS jsonb
LANGUAGE sql IMMUTABLE AS $$
    SELECT CASE
        WHEN EXISTS (SELECT 1 FROM jsonb_array_elements(doc -> 'Statement') s
                      WHERE s ->> 'Sid' = 'CapacityDecisions')
        THEN doc
        ELSE jsonb_set(doc, '{Statement}',
                       (doc -> 'Statement') || jsonb_build_array(jsonb_build_object(
                           'Sid', 'CapacityDecisions',
                           'Effect', 'Allow',
                           'Action', jsonb_build_array('ml:RecordCapacityDecision'),
                           'Resource', jsonb_build_array('wpt:ml:capacity:*'))))
    END
$$;

CREATE TEMP TABLE capacity_policy_docs ON COMMIT DROP AS
SELECT p.policy_id,
       pg_temp.grant_capacity_decision(pv.document) AS document,
       pv.document AS previous
FROM iam.policies p
JOIN iam.policy_versions pv ON pv.policy_id = p.policy_id AND pv.is_default
WHERE p.name = 'WaypointDispatcher';

DELETE FROM capacity_policy_docs WHERE document = previous;

UPDATE iam.policy_versions pv
SET is_default = false
FROM capacity_policy_docs d
WHERE pv.policy_id = d.policy_id AND pv.is_default;

INSERT INTO iam.policy_versions (policy_id, version_number, document, is_default)
SELECT d.policy_id,
       (SELECT max(version_number) + 1 FROM iam.policy_versions x WHERE x.policy_id = d.policy_id),
       d.document,
       true
FROM capacity_policy_docs d;

-- A policy change clears every cached decision on the next request.
UPDATE iam.policy_generation
SET generation = generation + 1, changed_at = now()
WHERE EXISTS (SELECT 1 FROM capacity_policy_docs);
