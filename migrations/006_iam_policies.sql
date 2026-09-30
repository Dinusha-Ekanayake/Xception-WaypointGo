-- 006 Policy based authorization.
--
--     Policy becomes data rather than code. An administrator authors a document,
--     attaches it to a user or a role, and effective permissions change with no
--     deployment. Evaluation stays in process: this is not an external policy
--     service, which SYSTEM-ARCHITECTURE still rules out.
--
--     Evaluation order, same as the model it borrows from:
--       default Deny -> any matching Deny wins -> otherwise any matching Allow.
--
--     Composition with the scope tables, which are kept:
--       effective access = policy AND scope.
--     Policies decide which actions are allowed. The scope tables decide which
--     rows are visible, because row-level security can filter on a table and
--     cannot read a JSON document. A policy can therefore never widen someone's
--     reach beyond their scope.
-- ---------------------------------------------------------------------

-- ---- what may be granted --------------------------------------------
-- Every action a policy may name. Authoring validates against this, so a typo
-- like 'order:Plase' is rejected at authoring time rather than denying silently
-- forever, which would look exactly like a permissions bug nobody can find.

CREATE TABLE iam.action_catalogue (
    action      text PRIMARY KEY,
    module      text NOT NULL,
    description text NOT NULL,
    -- False for an action that is designed but whose handler does not exist yet.
    -- Policies may reference it; the command bus will not route it.
    implemented boolean NOT NULL DEFAULT false,
    CONSTRAINT ck_action_shape CHECK (action ~ '^[a-z][a-z0-9]*:[A-Z][A-Za-z0-9]*$')
);

INSERT INTO iam.action_catalogue (action, module, description, implemented) VALUES
    ('reference:Import',        'referencedata', 'Stage, validate and publish a reference version', true),
    ('reference:Read',          'referencedata', 'Read outlets, vehicles, travel and calendar',     true),
    ('vehicle:SetDayStatus',    'referencedata', 'Mark a vehicle available, in workshop or unavailable', true),
    ('calendar:Override',       'referencedata', 'Override a calendar day',                          false),
    ('iam:CreateUser',          'identity',      'Create an account',                                false),
    ('iam:UpdateUser',          'identity',      'Change an account',                                false),
    ('iam:DisableUser',         'identity',      'Disable an account and revoke its sessions',       false),
    ('iam:GrantScope',          'identity',      'Grant depot or outlet access',                     false),
    ('iam:RevokeScope',         'identity',      'Revoke depot or outlet access',                    false),
    ('iam:AssignDriver',        'identity',      'Assign a driver to a vehicle for a period',        false),
    ('iam:CreatePolicy',        'identity',      'Author a policy',                                  false),
    ('iam:AttachPolicy',        'identity',      'Attach or detach a policy',                        false),
    ('iam:ReadPolicy',          'identity',      'Read policies and attachments',                    false),
    ('order:Place',             'ordering',      'Place an order for an outlet',                     false),
    ('order:Amend',             'ordering',      'Amend an order before allocation',                 false),
    ('order:Cancel',            'ordering',      'Cancel an order',                                  false),
    ('order:Read',              'ordering',      'Read orders',                                      false),
    ('plan:Generate',           'planning',      'Generate a draft allocation',                      false),
    ('plan:Override',           'planning',      'Override an allocation decision',                  false),
    ('plan:Publish',            'planning',      'Publish a plan',                                   false),
    ('plan:Read',               'planning',      'Read plans and deferrals',                         false),
    ('loading:Start',           'loading',       'Start a loading session',                          false),
    ('loading:Check',           'loading',       'Record a loading check',                           false),
    ('loading:Shortfall',       'loading',       'Flag missing or damaged goods',                    false),
    ('loading:Release',         'loading',       'Release a trip for departure',                     false),
    ('delivery:Start',          'execution',     'Start a stop',                                     false),
    ('delivery:Record',         'execution',     'Record a delivery outcome',                        false),
    ('delivery:CaptureProof',   'execution',     'Capture proof of delivery',                        false),
    ('delivery:ReportFault',    'execution',     'Report a vehicle or road fault',                   false),
    ('receipt:Confirm',         'receipt',       'Confirm receipt at the outlet',                    false),
    ('receipt:Dispute',         'receipt',       'Dispute a delivery',                               false),
    ('audit:Read',              'platform',      'Read the audit log',                               false);

-- ---- policies ---------------------------------------------------------

CREATE TABLE iam.policies (
    policy_id   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name        text NOT NULL UNIQUE,
    description text NOT NULL,
    -- Managed policies are authored once and attached to many principals.
    is_managed  boolean NOT NULL DEFAULT true,
    created_at  timestamptz NOT NULL DEFAULT now(),
    created_by  uuid REFERENCES iam.users(user_id),
    CONSTRAINT ck_policy_name CHECK (name ~ '^[A-Za-z][A-Za-z0-9_-]*$')
);

-- A version is immutable once written, exactly like a reference version: a
-- decision taken under an older policy must remain explainable.
CREATE TABLE iam.policy_versions (
    policy_version_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    policy_id         uuid NOT NULL REFERENCES iam.policies(policy_id) ON DELETE CASCADE,
    version_number    integer NOT NULL,
    document          jsonb NOT NULL,
    is_default        boolean NOT NULL DEFAULT false,
    created_at        timestamptz NOT NULL DEFAULT now(),
    created_by        uuid REFERENCES iam.users(user_id),
    UNIQUE (policy_id, version_number),
    CONSTRAINT ck_policy_version_number CHECK (version_number > 0)
);

-- Exactly one default version per policy, enforced rather than assumed.
CREATE UNIQUE INDEX uq_policy_default_version
    ON iam.policy_versions (policy_id) WHERE is_default;

CREATE TABLE iam.policy_attachments (
    attachment_id  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    policy_id      uuid NOT NULL REFERENCES iam.policies(policy_id) ON DELETE CASCADE,
    principal_type text NOT NULL,
    -- A user id, or a role code. Text because the two principal kinds are keyed
    -- differently; the application checks the target exists.
    principal_id   text NOT NULL,
    attached_at    timestamptz NOT NULL DEFAULT now(),
    attached_by    uuid REFERENCES iam.users(user_id),
    UNIQUE (policy_id, principal_type, principal_id),
    CONSTRAINT ck_principal_type CHECK (principal_type IN ('user','role'))
);

CREATE INDEX ix_policy_attachments_principal
    ON iam.policy_attachments (principal_type, principal_id);

-- ---- seeded role policies --------------------------------------------
-- These are data, not schema. An administrator edits them at runtime; adding a
-- brand new action is the only change that still needs a release.

INSERT INTO iam.policies (name, description) VALUES
    ('WaypointAdministrator', 'Full administrative access to accounts, policy and reference data'),
    ('WaypointDispatcher',    'Plan, publish and resolve exceptions for the dispatcher''s depots'),
    ('WaypointLoader',        'Dock work: loading sessions, checks and shortfalls'),
    ('WaypointDriver',        'Road work: stop outcomes, proof of delivery and fault reports'),
    ('WaypointStoreManager',  'Place orders and confirm receipt for the manager''s outlets'),
    ('WaypointAuditor',       'Read-only access across the operation for investigating a dispute');

INSERT INTO iam.policy_versions (policy_id, version_number, document, is_default)
SELECT p.policy_id, 1, d.document, true
FROM iam.policies p
JOIN (VALUES
    ('WaypointAdministrator', '{
        "Version": "2026-09-27",
        "Statement": [
          {"Sid": "Administer", "Effect": "Allow",
           "Action": ["iam:*", "reference:*", "vehicle:*", "calendar:*", "audit:Read"],
           "Resource": ["*"]}
        ]}'::jsonb),
    ('WaypointDispatcher', '{
        "Version": "2026-09-27",
        "Statement": [
          {"Sid": "PlanAndPublish", "Effect": "Allow",
           "Action": ["plan:*", "order:Read", "reference:Read", "vehicle:SetDayStatus"],
           "Resource": ["*"]},
          {"Sid": "NeverAdminister", "Effect": "Deny",
           "Action": ["iam:CreateUser", "iam:DisableUser", "iam:CreatePolicy", "iam:AttachPolicy"],
           "Resource": ["*"]}
        ]}'::jsonb),
    ('WaypointLoader', '{
        "Version": "2026-09-27",
        "Statement": [
          {"Sid": "DockWork", "Effect": "Allow",
           "Action": ["loading:*", "plan:Read", "reference:Read"],
           "Resource": ["*"]}
        ]}'::jsonb),
    ('WaypointDriver', '{
        "Version": "2026-09-27",
        "Statement": [
          {"Sid": "RoadWork", "Effect": "Allow",
           "Action": ["delivery:*", "plan:Read", "reference:Read"],
           "Resource": ["*"]}
        ]}'::jsonb),
    ('WaypointStoreManager', '{
        "Version": "2026-09-27",
        "Statement": [
          {"Sid": "OrderAndReceive", "Effect": "Allow",
           "Action": ["order:Place", "order:Amend", "order:Cancel", "order:Read", "receipt:*"],
           "Resource": ["*"]}
        ]}'::jsonb),
    ('WaypointAuditor', '{
        "Version": "2026-09-27",
        "Statement": [
          {"Sid": "ReadEverything", "Effect": "Allow",
           "Action": ["order:Read", "plan:Read", "reference:Read", "iam:ReadPolicy", "audit:Read"],
           "Resource": ["*"]},
          {"Sid": "NeverWrite", "Effect": "Deny",
           "Action": ["order:Place", "order:Amend", "order:Cancel", "plan:Generate",
                      "plan:Override", "plan:Publish", "loading:*", "delivery:*",
                      "receipt:Confirm", "receipt:Dispute", "iam:*", "reference:Import",
                      "vehicle:SetDayStatus"],
           "Resource": ["*"]}
        ]}'::jsonb)
) AS d(name, document) ON d.name = p.name;

-- Each seeded policy is attached to the role of the same purpose.
INSERT INTO iam.policy_attachments (policy_id, principal_type, principal_id)
SELECT p.policy_id, 'role', m.role_code
FROM iam.policies p
JOIN (VALUES
    ('WaypointAdministrator', 'admin'),
    ('WaypointDispatcher',    'dispatcher'),
    ('WaypointLoader',        'loader'),
    ('WaypointDriver',        'driver'),
    ('WaypointStoreManager',  'store_manager'),
    ('WaypointAuditor',       'auditor')
) AS m(name, role_code) ON m.name = p.name;
