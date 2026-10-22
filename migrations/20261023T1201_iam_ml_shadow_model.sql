-- Issue #12: ml:ShadowModel makes a registered model the shadow of its kind (R-ML-38).
-- The administrator already holds ml:* (20260930T1201); the auditor's explicit deny
-- names it like every other registry write, in a new version of its policy.

INSERT INTO iam.action_catalogue (action, module, description, implemented) VALUES
    ('ml:ShadowModel', 'intelligence', 'Make a registered model the shadow of its kind, scored beside the active one', true)
ON CONFLICT (action) DO UPDATE SET implemented = true;

CREATE OR REPLACE FUNCTION pg_temp.shadow_add_actions(doc jsonb, target_sid text, wanted text[]) RETURNS jsonb
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

CREATE TEMP TABLE shadow_policy_docs ON COMMIT DROP AS
SELECT p.policy_id,
       pg_temp.shadow_add_actions(pv.document, 'NeverWrite', ARRAY['ml:ShadowModel']) AS document,
       pv.document AS previous
FROM iam.policies p
JOIN iam.policy_versions pv ON pv.policy_id = p.policy_id AND pv.is_default
WHERE p.name = 'WaypointAuditor';

DELETE FROM shadow_policy_docs WHERE document = previous;

UPDATE iam.policy_versions pv
SET is_default = false
FROM shadow_policy_docs d
WHERE pv.policy_id = d.policy_id AND pv.is_default;

INSERT INTO iam.policy_versions (policy_id, version_number, document, is_default)
SELECT d.policy_id,
       (SELECT max(version_number) + 1 FROM iam.policy_versions x WHERE x.policy_id = d.policy_id),
       d.document,
       true
FROM shadow_policy_docs d;

-- A policy change clears every cached decision on the next request.
UPDATE iam.policy_generation
SET generation = generation + 1, changed_at = now()
WHERE EXISTS (SELECT 1 FROM shadow_policy_docs);
