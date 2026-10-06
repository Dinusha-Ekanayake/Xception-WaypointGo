-- Roadmap FF-02: the loader may upload a photo of a damaged or short line.
-- A policy version is immutable, so the grant is a new default version of the
-- loader policy built from the current one; any policy change clears the cache.
-- Row-level security narrows it to the loader's depot (20261007T1200).
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

CREATE TEMP TABLE loader_photo_docs ON COMMIT DROP AS
SELECT p.policy_id,
       pg_temp.add_actions(pv.document, 'ReportAndSync', ARRAY['issue:AttachPhoto']) AS document,
       pv.document AS previous
FROM iam.policies p
JOIN iam.policy_versions pv ON pv.policy_id = p.policy_id AND pv.is_default
WHERE p.name = 'WaypointLoader';

DELETE FROM loader_photo_docs WHERE document = previous;

UPDATE iam.policy_versions pv
SET is_default = false
FROM loader_photo_docs d
WHERE pv.policy_id = d.policy_id AND pv.is_default;

INSERT INTO iam.policy_versions (policy_id, version_number, document, is_default)
SELECT d.policy_id,
       (SELECT max(version_number) + 1 FROM iam.policy_versions x WHERE x.policy_id = d.policy_id),
       d.document,
       true
FROM loader_photo_docs d;

UPDATE iam.policy_generation
SET generation = generation + 1, changed_at = now()
WHERE EXISTS (SELECT 1 FROM loader_photo_docs);
