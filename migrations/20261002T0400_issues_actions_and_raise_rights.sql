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

-- ---- role policies, version 3 ----------------------------------------
-- Version 2 (20260930T1201) granted issue:Raise on every resource. Version 3
-- copies it and narrows only that grant. A policy version is immutable, so this
-- is a new version and the default moves to it; version 2 stays explainable.

CREATE TEMP TABLE policy_v3 (name text PRIMARY KEY, document jsonb NOT NULL) ON COMMIT DROP;

INSERT INTO policy_v3 (name, document) VALUES
    ('WaypointLoader', '{
        "Version": "2026-10-02",
        "Statement": [
          {"Sid": "DockWork", "Effect": "Allow",
           "Action": ["loading:*", "plan:Read", "reference:Read"],
           "Resource": ["*"]},
          {"Sid": "ReadAndSync", "Effect": "Allow",
           "Action": ["issue:Read", "notification:*", "sync:Submit", "sync:Acknowledge", "sync:Read"],
           "Resource": ["*"]},
          {"Sid": "RaiseDockIssues", "Effect": "Allow",
           "Action": ["issue:Raise"],
           "Resource": ["wpt:issue:type:LOADING_SHORTFALL", "wpt:issue:type:DAMAGED_GOODS",
                        "wpt:issue:type:OTHER"]}
        ]}'::jsonb),
    ('WaypointDriver', '{
        "Version": "2026-10-02",
        "Statement": [
          {"Sid": "RoadWork", "Effect": "Allow",
           "Action": ["delivery:*", "plan:Read", "reference:Read"],
           "Resource": ["*"]},
          {"Sid": "ReadAndSync", "Effect": "Allow",
           "Action": ["issue:Read", "notification:*", "sync:Submit", "sync:Acknowledge", "sync:Read"],
           "Resource": ["*"]},
          {"Sid": "RaiseRoadIssues", "Effect": "Allow",
           "Action": ["issue:Raise"],
           "Resource": ["wpt:issue:type:FAILED_DELIVERY", "wpt:issue:type:VEHICLE_FAULT",
                        "wpt:issue:type:ROAD_DISRUPTION", "wpt:issue:type:LATE_DELIVERY",
                        "wpt:issue:type:DAMAGED_GOODS", "wpt:issue:type:OTHER"]}
        ]}'::jsonb),
    ('WaypointStoreManager', '{
        "Version": "2026-10-02",
        "Statement": [
          {"Sid": "OrderAndReceive", "Effect": "Allow",
           "Action": ["order:Place", "order:Amend", "order:Cancel", "order:Read", "receipt:*",
                      "warehouse:ReadCatalogue", "delivery:Read"],
           "Resource": ["*"]},
          {"Sid": "ReadAndSync", "Effect": "Allow",
           "Action": ["issue:Read", "notification:*", "sync:Submit", "sync:Acknowledge", "sync:Read"],
           "Resource": ["*"]},
          {"Sid": "RaiseStoreIssues", "Effect": "Allow",
           "Action": ["issue:Raise"],
           "Resource": ["wpt:issue:type:DAMAGED_GOODS", "wpt:issue:type:LATE_DELIVERY",
                        "wpt:issue:type:OTHER"]}
        ]}'::jsonb);

-- Every action a v3 document names must exist, or the policy would deny it
-- silently forever. Wildcard entries are checked as patterns.
DO $$
DECLARE
    missing text;
BEGIN
    SELECT string_agg(DISTINCT a.action, ', ') INTO missing
    FROM policy_v3 v,
         jsonb_array_elements(v.document -> 'Statement') s,
         jsonb_array_elements_text(s -> 'Action') AS a(action)
    WHERE NOT EXISTS (
        SELECT 1 FROM iam.action_catalogue c
        WHERE c.action LIKE replace(a.action, '*', '%')
    );
    IF missing IS NOT NULL THEN
        RAISE EXCEPTION 'Policy version 3 names actions absent from the catalogue: %', missing;
    END IF;
END $$;

UPDATE iam.policy_versions pv
SET is_default = false
FROM iam.policies p, policy_v3 v
WHERE pv.policy_id = p.policy_id AND p.name = v.name AND pv.is_default;

INSERT INTO iam.policy_versions (policy_id, version_number, document, is_default)
SELECT p.policy_id,
       (SELECT max(version_number) + 1 FROM iam.policy_versions x WHERE x.policy_id = p.policy_id),
       v.document,
       true
FROM iam.policies p
JOIN policy_v3 v ON v.name = p.name;
