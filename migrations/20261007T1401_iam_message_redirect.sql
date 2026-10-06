-- Roadmap FF-04: message:Redirect, the dispatcher moving a channel post to the
-- store's private line. A new catalogue row and a new dispatcher policy version.
INSERT INTO iam.action_catalogue (action, module, description, implemented) VALUES
    ('message:Redirect', 'messaging', 'Move a post off a trip''s channel to the store''s private line, with a reason', true)
ON CONFLICT (action) DO UPDATE SET implemented = true;

CREATE OR REPLACE FUNCTION pg_temp.grant_redirect(doc jsonb) RETURNS jsonb
LANGUAGE sql IMMUTABLE AS $$
    SELECT CASE
        WHEN EXISTS (SELECT 1 FROM jsonb_array_elements(doc -> 'Statement') s WHERE s ->> 'Sid' = 'TripChannel')
        THEN doc
        ELSE jsonb_set(doc, '{Statement}', (doc -> 'Statement') || jsonb_build_array(jsonb_build_object(
                 'Sid', 'TripChannel', 'Effect', 'Allow',
                 'Action', jsonb_build_array('message:Redirect'),
                 'Resource', jsonb_build_array('wpt:message:*'))))
    END
$$;

CREATE TEMP TABLE redirect_policy_docs ON COMMIT DROP AS
SELECT p.policy_id, pg_temp.grant_redirect(pv.document) AS document, pv.document AS previous
FROM iam.policies p
JOIN iam.policy_versions pv ON pv.policy_id = p.policy_id AND pv.is_default
WHERE p.name = 'WaypointDispatcher';

DELETE FROM redirect_policy_docs WHERE document = previous;

UPDATE iam.policy_versions pv SET is_default = false
FROM redirect_policy_docs d WHERE pv.policy_id = d.policy_id AND pv.is_default;

INSERT INTO iam.policy_versions (policy_id, version_number, document, is_default)
SELECT d.policy_id, (SELECT max(version_number) + 1 FROM iam.policy_versions x WHERE x.policy_id = d.policy_id), d.document, true
FROM redirect_policy_docs d;

UPDATE iam.policy_generation SET generation = generation + 1, changed_at = now()
WHERE EXISTS (SELECT 1 FROM redirect_policy_docs);
