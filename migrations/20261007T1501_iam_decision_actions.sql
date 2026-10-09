-- Roadmap FF-09: the dispatcher asks a store a question on a live trip, and the
-- store answers it. The dispatcher holds issue:* already; the store manager is
-- given issue:AnswerDecision, never issue:AskDecision.
INSERT INTO iam.action_catalogue (action, module, description, implemented) VALUES
    ('issue:AskDecision',    'issues', 'Ask one store a question on a live trip: receive late, or help at the dock', true),
    ('issue:AnswerDecision', 'issues', 'Answer a question asked of the store, or record an answer given by phone', true)
ON CONFLICT (action) DO UPDATE SET implemented = true;

CREATE OR REPLACE FUNCTION pg_temp.allow_answer(doc jsonb) RETURNS jsonb
LANGUAGE sql IMMUTABLE AS $$
    SELECT CASE
        WHEN EXISTS (SELECT 1 FROM jsonb_array_elements(doc -> 'Statement') s WHERE s ->> 'Sid' = 'AnswerDecisions')
        THEN doc
        ELSE jsonb_set(doc, '{Statement}', (doc -> 'Statement') || jsonb_build_array(jsonb_build_object(
                 'Sid', 'AnswerDecisions',
                 'Effect', 'Allow',
                 'Action', jsonb_build_array('issue:AnswerDecision'),
                 'Resource', jsonb_build_array('*'))))
    END
$$;

CREATE TEMP TABLE decision_policy_docs ON COMMIT DROP AS
SELECT p.policy_id, pg_temp.allow_answer(pv.document) AS document, pv.document AS previous
FROM iam.policies p
JOIN iam.policy_versions pv ON pv.policy_id = p.policy_id AND pv.is_default
WHERE p.name = 'WaypointStoreManager';

DELETE FROM decision_policy_docs WHERE document = previous;

UPDATE iam.policy_versions pv SET is_default = false
FROM decision_policy_docs d WHERE pv.policy_id = d.policy_id AND pv.is_default;

INSERT INTO iam.policy_versions (policy_id, version_number, document, is_default)
SELECT d.policy_id, (SELECT max(version_number) + 1 FROM iam.policy_versions x WHERE x.policy_id = d.policy_id), d.document, true
FROM decision_policy_docs d;

UPDATE iam.policy_generation SET generation = generation + 1, changed_at = now();
