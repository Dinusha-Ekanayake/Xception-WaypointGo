-- Catalogue rows and role policies for every command the remaining modules
-- will implement (issue #01, contracts).
--
-- A new action needs two halves: a catalogue row and a handler (R-IAM). This
-- adds the rows now, all implemented = false, so each module lands its handler
-- against an action that already exists and a policy that already grants it.
-- CommandPathIntegrationTest flips the expectation per action: a handler whose
-- row still says false fails the build.

INSERT INTO iam.action_catalogue (action, module, description, implemented) VALUES
    ('order:CloseForDay',            'ordering',     'Close ordering for a depot and service day',          false),
    ('plan:Defer',                   'planning',     'Defer an order from a draft plan with a reason',       false),
    ('plan:Revise',                  'planning',     'Create a new version from a published plan',           false),
    ('plan:Replan',                  'planning',     'Replan affected trips after a vehicle change',         false),
    ('loading:RequestInterchange',   'loading',      'Request a substitute vehicle for a trip',              false),
    ('loading:Handover',             'loading',      'Hand a loading session to another loader',             false),
    ('loading:Read',                 'loading',      'Read manifests, ready trips and shortfalls',           false),
    ('delivery:RecordArrival',       'execution',    'Record arrival at a stop',                             false),
    ('delivery:ReportVehicleStatus', 'execution',    'Report the vehicle''s status from the road',           false),
    ('delivery:Read',                'execution',    'Read run sheets and delivery records',                 false),
    ('receipt:ConfirmPartial',       'receipt',      'Confirm a partial receipt with received quantities',   false),
    ('receipt:Read',                 'receipt',      'Read receipts and pending confirmations',              false),
    ('issue:Raise',                  'issues',       'Raise an operational issue',                           false),
    ('issue:Assign',                 'issues',       'Assign an issue to a person',                          false),
    ('issue:Resolve',                'issues',       'Resolve an issue with an action and a reason',         false),
    ('issue:RecordReplacement',      'issues',       'Record a replacement for a shortfall',                 false),
    ('issue:ScheduleRedelivery',     'issues',       'Request a redelivery of an order',                     false),
    ('issue:Close',                  'issues',       'Close a resolved issue',                               false),
    ('issue:Cancel',                 'issues',       'Cancel an issue raised in error',                      false),
    ('issue:Read',                   'issues',       'Read issues',                                          false),
    ('notification:Read',            'notification', 'Read one''s own inbox',                                false),
    ('notification:MarkRead',        'notification', 'Mark notifications read',                              false),
    ('notification:Subscribe',       'notification', 'Subscribe a device to web push',                       false),
    ('notification:Unsubscribe',     'notification', 'Unsubscribe a device from web push',                   false),
    ('sync:Submit',                  'sync',         'Submit queued offline operations',                     false),
    ('sync:Acknowledge',             'sync',         'Acknowledge a synced operation',                       false),
    ('sync:Discard',                 'sync',         'Discard a conflicting operation with a reason',        false),
    ('sync:Resolve',                 'sync',         'Reapply a conflicting operation on the current version', false),
    ('sync:Read',                    'sync',         'Read pending operations and conflicts',                false),
    ('warehouse:ReadCatalogue',      'warehouse',    'Read the cached product catalogue',                    false),
    ('warehouse:ReplayInbound',      'warehouse',    'Replay a quarantined inbound warehouse event',         false),
    ('warehouse:DiscardInbound',     'warehouse',    'Discard a quarantined inbound warehouse event',        false),
    ('warehouse:Reconcile',          'warehouse',    'Reconcile orders with the warehouse',                  false),
    ('ml:Read',                      'intelligence', 'Read models, predictions and forecasts',               false),
    ('ml:RegisterModel',             'intelligence', 'Register a model version',                             false),
    ('ml:ActivateModel',             'intelligence', 'Activate a model version',                             false),
    ('ml:RetireModel',               'intelligence', 'Retire a model version',                               false),
    ('iam:ChangeRole',               'identity',     'Change an account''s role and rotate its sessions',    false),
    ('iam:RegisterDevice',           'identity',     'Register a device',                                    false),
    ('iam:RetireDevice',             'identity',     'Retire a device',                                      false),
    ('iam:DetachPolicy',             'identity',     'Detach a policy from a user or role',                  false),
    ('platform:ReplayEvent',         'platform',     'Replay a dead-lettered event',                         false)
ON CONFLICT (action) DO NOTHING;

-- ---- role policies, version 2 ----------------------------------------
-- A policy version is immutable, so the new grants are a new version and the
-- default moves to it. Version 1 stays, so a decision taken under it remains
-- explainable. The default is cleared before it is set because exactly one
-- default per policy is enforced by uq_policy_default_version.

CREATE TEMP TABLE policy_v2 (name text PRIMARY KEY, document jsonb NOT NULL) ON COMMIT DROP;

INSERT INTO policy_v2 (name, document) VALUES
    ('WaypointAdministrator', '{
        "Version": "2026-09-30",
        "Statement": [
          {"Sid": "Administer", "Effect": "Allow",
           "Action": ["iam:*", "reference:*", "vehicle:*", "calendar:*", "audit:Read",
                      "platform:ReplayEvent", "warehouse:*", "ml:*"],
           "Resource": ["*"]},
          {"Sid": "ReadOperations", "Effect": "Allow",
           "Action": ["order:Read", "plan:Read", "loading:Read", "delivery:Read",
                      "receipt:Read", "issue:Read", "sync:Read"],
           "Resource": ["*"]}
        ]}'::jsonb),
    ('WaypointDispatcher', '{
        "Version": "2026-09-30",
        "Statement": [
          {"Sid": "PlanAndPublish", "Effect": "Allow",
           "Action": ["plan:*", "order:Read", "order:CloseForDay", "reference:Read",
                      "vehicle:SetDayStatus"],
           "Resource": ["*"]},
          {"Sid": "ResolveExceptions", "Effect": "Allow",
           "Action": ["issue:*", "sync:Read", "sync:Discard", "sync:Resolve"],
           "Resource": ["*"]},
          {"Sid": "WatchTheDay", "Effect": "Allow",
           "Action": ["loading:Read", "delivery:Read", "receipt:Read", "notification:*",
                      "warehouse:ReadCatalogue", "ml:Read"],
           "Resource": ["*"]},
          {"Sid": "NeverAdminister", "Effect": "Deny",
           "Action": ["iam:CreateUser", "iam:DisableUser", "iam:CreatePolicy", "iam:AttachPolicy",
                      "iam:DetachPolicy", "iam:ChangeRole"],
           "Resource": ["*"]}
        ]}'::jsonb),
    ('WaypointLoader', '{
        "Version": "2026-09-30",
        "Statement": [
          {"Sid": "DockWork", "Effect": "Allow",
           "Action": ["loading:*", "plan:Read", "reference:Read"],
           "Resource": ["*"]},
          {"Sid": "ReportAndSync", "Effect": "Allow",
           "Action": ["issue:Raise", "issue:Read", "notification:*",
                      "sync:Submit", "sync:Acknowledge", "sync:Read"],
           "Resource": ["*"]}
        ]}'::jsonb),
    ('WaypointDriver', '{
        "Version": "2026-09-30",
        "Statement": [
          {"Sid": "RoadWork", "Effect": "Allow",
           "Action": ["delivery:*", "plan:Read", "reference:Read"],
           "Resource": ["*"]},
          {"Sid": "ReportAndSync", "Effect": "Allow",
           "Action": ["issue:Raise", "issue:Read", "notification:*",
                      "sync:Submit", "sync:Acknowledge", "sync:Read"],
           "Resource": ["*"]}
        ]}'::jsonb),
    ('WaypointStoreManager', '{
        "Version": "2026-09-30",
        "Statement": [
          {"Sid": "OrderAndReceive", "Effect": "Allow",
           "Action": ["order:Place", "order:Amend", "order:Cancel", "order:Read", "receipt:*",
                      "warehouse:ReadCatalogue", "delivery:Read"],
           "Resource": ["*"]},
          {"Sid": "ReportAndSync", "Effect": "Allow",
           "Action": ["issue:Raise", "issue:Read", "notification:*",
                      "sync:Submit", "sync:Acknowledge", "sync:Read"],
           "Resource": ["*"]}
        ]}'::jsonb),
    ('WaypointAuditor', '{
        "Version": "2026-09-30",
        "Statement": [
          {"Sid": "ReadEverything", "Effect": "Allow",
           "Action": ["order:Read", "plan:Read", "reference:Read", "iam:ReadPolicy", "audit:Read",
                      "loading:Read", "delivery:Read", "receipt:Read", "issue:Read",
                      "sync:Read", "warehouse:ReadCatalogue", "ml:Read"],
           "Resource": ["*"]},
          {"Sid": "NeverWrite", "Effect": "Deny",
           "Action": ["order:Place", "order:Amend", "order:Cancel", "order:CloseForDay",
                      "plan:Generate", "plan:Override", "plan:Publish", "plan:Defer",
                      "plan:Revise", "plan:Replan",
                      "loading:Start", "loading:Check", "loading:Shortfall", "loading:Release",
                      "loading:RequestInterchange", "loading:Handover",
                      "delivery:Start", "delivery:Record", "delivery:CaptureProof",
                      "delivery:ReportFault", "delivery:RecordArrival", "delivery:ReportVehicleStatus",
                      "receipt:Confirm", "receipt:Dispute", "receipt:ConfirmPartial",
                      "issue:Raise", "issue:Assign", "issue:Resolve", "issue:RecordReplacement",
                      "issue:ScheduleRedelivery", "issue:Close", "issue:Cancel",
                      "sync:Submit", "sync:Acknowledge", "sync:Discard", "sync:Resolve",
                      "warehouse:ReplayInbound", "warehouse:DiscardInbound", "warehouse:Reconcile",
                      "ml:RegisterModel", "ml:ActivateModel", "ml:RetireModel",
                      "platform:ReplayEvent", "iam:*", "reference:Import", "vehicle:SetDayStatus",
                      "calendar:Override"],
           "Resource": ["*"]}
        ]}'::jsonb);

-- Every action a v2 document names must exist, or the policy would deny it
-- silently forever. Wildcard entries are checked as patterns.
DO $$
DECLARE
    missing text;
BEGIN
    SELECT string_agg(DISTINCT a.action, ', ') INTO missing
    FROM policy_v2 v,
         jsonb_array_elements(v.document -> 'Statement') s,
         jsonb_array_elements_text(s -> 'Action') AS a(action)
    WHERE NOT EXISTS (
        SELECT 1 FROM iam.action_catalogue c
        WHERE c.action LIKE replace(a.action, '*', '%')
    );
    IF missing IS NOT NULL THEN
        RAISE EXCEPTION 'Policy version 2 names actions absent from the catalogue: %', missing;
    END IF;
END $$;

UPDATE iam.policy_versions pv
SET is_default = false
FROM iam.policies p, policy_v2 v
WHERE pv.policy_id = p.policy_id AND p.name = v.name AND pv.is_default;

INSERT INTO iam.policy_versions (policy_id, version_number, document, is_default)
SELECT p.policy_id,
       (SELECT max(version_number) + 1 FROM iam.policy_versions x WHERE x.policy_id = p.policy_id),
       v.document,
       true
FROM iam.policies p
JOIN policy_v2 v ON v.name = p.name;
