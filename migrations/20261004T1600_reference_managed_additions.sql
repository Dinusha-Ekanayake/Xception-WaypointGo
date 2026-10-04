-- Administrator-created reference rows survive a later source import.
CREATE TABLE ref.managed_additions (
    kind text NOT NULL CHECK (kind IN ('depot', 'outlet', 'vehicle')),
    natural_id text NOT NULL,
    payload jsonb NOT NULL,
    created_by uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (kind, natural_id)
);

INSERT INTO iam.action_catalogue (action, module, description, implemented) VALUES
    ('reference:CreateDepot', 'referencedata', 'Register a new operating depot', true),
    ('reference:CreateOutlet', 'referencedata', 'Register a new retail outlet', true),
    ('reference:CreateVehicle', 'referencedata', 'Register a new fleet vehicle', true)
ON CONFLICT (action) DO UPDATE SET implemented = true;

-- The administrator policy predates these commands. Publish a new immutable
-- version with their explicit actions, leaving earlier decisions intact.
CREATE TEMP TABLE reference_create_policy ON COMMIT DROP AS
SELECT p.policy_id, pv.document AS old_document,
       jsonb_set(pv.document, '{Statement}',
           (SELECT jsonb_agg(
               CASE WHEN statement ->> 'Effect' = 'Allow'
                     AND (statement -> 'Action') ? 'reference:Import'
                    THEN jsonb_set(statement, '{Action}',
                         (statement -> 'Action') ||
                         '["reference:CreateDepot","reference:CreateOutlet","reference:CreateVehicle"]'::jsonb)
                    ELSE statement END ORDER BY ordinal)
            FROM jsonb_array_elements(pv.document -> 'Statement')
                 WITH ORDINALITY AS entries(statement, ordinal))) AS new_document
FROM iam.policies p
JOIN iam.policy_versions pv ON pv.policy_id = p.policy_id AND pv.is_default
WHERE p.name = 'WaypointAdministrator';

DELETE FROM reference_create_policy WHERE old_document = new_document;

UPDATE iam.policy_versions pv SET is_default = false
FROM reference_create_policy draft WHERE pv.policy_id = draft.policy_id AND pv.is_default;

INSERT INTO iam.policy_versions (policy_id, version_number, document, is_default)
SELECT draft.policy_id,
       (SELECT max(version_number) + 1 FROM iam.policy_versions prior
        WHERE prior.policy_id = draft.policy_id),
       draft.new_document, true
FROM reference_create_policy draft;
