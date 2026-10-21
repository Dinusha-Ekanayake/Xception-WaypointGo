-- Trip-problems piece 3 (R-ISS-17): a loader who finds the goods of a line
-- they flagged takes the flag back before release, with a reason. That is the
-- existing issue:RecordReplacement ("found at the depot"), which resolves the
-- issue and, through shortfall.resolved, the shortfall in Loading, so no new
-- command or event. A policy version is immutable, so the grant is a new default
-- version of the loader policy built from the current one; scope stays the
-- loader's depot (IssueScope and row-level security), and the handler refuses
-- once the trip is released.
INSERT INTO iam.action_catalogue (action, module, description, implemented) VALUES
    ('issue:RecordReplacement', 'issues', 'Record a replacement for a shortfall', true)
ON CONFLICT (action) DO UPDATE SET implemented = true;

CREATE OR REPLACE FUNCTION pg_temp.add_actions(doc jsonb, target_sid text, wanted text[]) RETURNS jsonb
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

CREATE TEMP TABLE loader_found_docs ON COMMIT DROP AS
SELECT p.policy_id,
       pg_temp.add_actions(pv.document, 'ReportAndSync', ARRAY['issue:RecordReplacement']) AS document,
       pv.document AS previous
FROM iam.policies p
JOIN iam.policy_versions pv ON pv.policy_id = p.policy_id AND pv.is_default
WHERE p.name = 'WaypointLoader';

DELETE FROM loader_found_docs WHERE document = previous;

UPDATE iam.policy_versions pv
SET is_default = false
FROM loader_found_docs d
WHERE pv.policy_id = d.policy_id AND pv.is_default;

INSERT INTO iam.policy_versions (policy_id, version_number, document, is_default)
SELECT d.policy_id,
       (SELECT max(version_number) + 1 FROM iam.policy_versions x WHERE x.policy_id = d.policy_id),
       d.document,
       true
FROM loader_found_docs d;

UPDATE iam.policy_generation
SET generation = generation + 1, changed_at = now()
WHERE EXISTS (SELECT 1 FROM loader_found_docs);
