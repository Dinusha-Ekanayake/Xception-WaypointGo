-- Issue #136: the messaging actions, and the roles that use them.
--
-- A new action needs a catalogue row and a handler (R-IAM). The dispatcher, the
-- loader, the driver and the store manager may read and post on the threads they
-- belong to; the policy grants the actions and row-level security decides which
-- threads and which messages (effective access is policy AND scope). The
-- administrator already holds every action.

INSERT INTO iam.action_catalogue (action, module, description, implemented) VALUES
    ('message:Read', 'messaging', 'Read the threads and messages one belongs to', true),
    ('message:Post', 'messaging', 'Write on a thread one belongs to', true)
ON CONFLICT (action) DO UPDATE SET implemented = true;

-- A policy version is immutable: each role policy gets a new default version
-- with one more statement, written once even if this runs against a database
-- that already has it.
CREATE OR REPLACE FUNCTION pg_temp.grant_messaging(doc jsonb) RETURNS jsonb
LANGUAGE sql IMMUTABLE AS $$
    SELECT CASE
        WHEN EXISTS (SELECT 1 FROM jsonb_array_elements(doc -> 'Statement') s
                      WHERE s ->> 'Sid' = 'TripMessages')
        THEN doc
        ELSE jsonb_set(doc, '{Statement}',
                       (doc -> 'Statement') || jsonb_build_array(jsonb_build_object(
                           'Sid', 'TripMessages',
                           'Effect', 'Allow',
                           'Action', jsonb_build_array('message:Read', 'message:Post'),
                           'Resource', jsonb_build_array('wpt:message:*'))))
    END
$$;

CREATE TEMP TABLE messaging_policy_docs ON COMMIT DROP AS
SELECT p.policy_id,
       pg_temp.grant_messaging(pv.document) AS document,
       pv.document AS previous
FROM iam.policies p
JOIN iam.policy_versions pv ON pv.policy_id = p.policy_id AND pv.is_default
WHERE p.name IN ('WaypointDispatcher', 'WaypointLoader', 'WaypointDriver', 'WaypointStoreManager');

DELETE FROM messaging_policy_docs WHERE document = previous;

UPDATE iam.policy_versions pv
SET is_default = false
FROM messaging_policy_docs d
WHERE pv.policy_id = d.policy_id AND pv.is_default;

INSERT INTO iam.policy_versions (policy_id, version_number, document, is_default)
SELECT d.policy_id,
       (SELECT max(version_number) + 1 FROM iam.policy_versions x WHERE x.policy_id = d.policy_id),
       d.document,
       true
FROM messaging_policy_docs d;

-- A policy change clears every cached decision on the next request.
UPDATE iam.policy_generation
SET generation = generation + 1, changed_at = now()
WHERE EXISTS (SELECT 1 FROM messaging_policy_docs);
