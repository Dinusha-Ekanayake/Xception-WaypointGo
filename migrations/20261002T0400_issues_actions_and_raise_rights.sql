-- Issues' handlers and read endpoints now enforce these actions (issue #13),
-- and who may raise which kind of issue becomes policy data (decision 2).
--
--     RaiseIssueHandler names the issue type as the resource,
--     wpt:issue:type:<TYPE>, so a role policy decides which types a role may
--     raise and a change is a new policy version, not a release. The dispatcher
--     keeps issue:* on every resource. A store disputes a delivery through
--     receipt:Dispute, so it raises no RECEIPT_DISPUTE directly.

UPDATE iam.action_catalogue SET implemented = true
 WHERE action IN ('issue:Raise', 'issue:Assign', 'issue:Resolve', 'issue:RecordReplacement',
                  'issue:ScheduleRedelivery', 'issue:Close', 'issue:Cancel', 'issue:Read');

-- ---- raise rights: the next version of three role policies -------------
-- A policy version is immutable, so the change is a new version and the default
-- moves to it. Built from each role's current default rather than restated, so
-- a grant another branch added survives (as 20261001T1501 does): issue:Raise is
-- taken out of whatever statement grants it today, empty statements are dropped,
-- and one statement granting issue:Raise on the role's types is appended.

CREATE TEMP TABLE raise_rights (name text PRIMARY KEY, sid text NOT NULL, types text[] NOT NULL) ON COMMIT DROP;
INSERT INTO raise_rights VALUES
    ('WaypointLoader',       'RaiseDockIssues',  ARRAY['LOADING_SHORTFALL','DAMAGED_GOODS','OTHER']),
    ('WaypointDriver',       'RaiseRoadIssues',  ARRAY['FAILED_DELIVERY','VEHICLE_FAULT','ROAD_DISRUPTION',
                                                       'LATE_DELIVERY','DAMAGED_GOODS','OTHER']),
    ('WaypointStoreManager', 'RaiseStoreIssues', ARRAY['DAMAGED_GOODS','LATE_DELIVERY','OTHER']);

CREATE TEMP TABLE raise_docs ON COMMIT DROP AS
SELECT p.policy_id,
       jsonb_set(
           pv.document, '{Statement}',
           coalesce(
               (SELECT jsonb_agg(
                           CASE WHEN (s -> 'Action') ? 'issue:Raise'
                                THEN jsonb_set(s, '{Action}', (s -> 'Action') - 'issue:Raise')
                                ELSE s END
                           ORDER BY ord)
                  FROM jsonb_array_elements(pv.document -> 'Statement') WITH ORDINALITY AS t(s, ord)
                 WHERE s ->> 'Sid' IS DISTINCT FROM r.sid
                   AND NOT ((s -> 'Action') ? 'issue:Raise' AND jsonb_array_length(s -> 'Action') = 1)),
               '[]'::jsonb)
           || jsonb_build_array(
                  jsonb_build_object(
                      'Sid', r.sid,
                      'Effect', 'Allow',
                      'Action', jsonb_build_array('issue:Raise'),
                      'Resource', (SELECT jsonb_agg('wpt:issue:type:' || t ORDER BY t) FROM unnest(r.types) AS t)))) AS document,
       pv.document AS previous
FROM iam.policies p
JOIN raise_rights r ON r.name = p.name
JOIN iam.policy_versions pv ON pv.policy_id = p.policy_id AND pv.is_default;

-- Each of the three roles must have had a default to build from.
DO $$
BEGIN
    IF (SELECT count(*) FROM raise_docs) <> 3 THEN
        RAISE EXCEPTION 'raise rights: expected three role policies with a default version, found %',
            (SELECT count(*) FROM raise_docs);
    END IF;
END $$;

DELETE FROM raise_docs WHERE document = previous;

UPDATE iam.policy_versions pv
SET is_default = false
FROM raise_docs d
WHERE pv.policy_id = d.policy_id AND pv.is_default;

INSERT INTO iam.policy_versions (policy_id, version_number, document, is_default)
SELECT d.policy_id,
       (SELECT max(version_number) + 1 FROM iam.policy_versions x WHERE x.policy_id = d.policy_id),
       d.document,
       true
FROM raise_docs d;
