CREATE TABLE ref.managed_updates (
    kind text NOT NULL CHECK (kind IN ('depot', 'outlet', 'vehicle')),
    natural_id text NOT NULL,
    payload jsonb NOT NULL,
    row_version bigint NOT NULL DEFAULT 1,
    updated_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (kind, natural_id)
);

GRANT SELECT, INSERT, UPDATE ON ref.managed_updates TO waypoint_referencedata;

INSERT INTO iam.action_catalogue (action, module, description, implemented) VALUES
    ('reference:UpdateDepot', 'referencedata', 'Update an operating depot', true),
    ('reference:UpdateOutlet', 'referencedata', 'Update a retail outlet', true),
    ('reference:UpdateVehicle', 'referencedata', 'Update a fleet vehicle', true)
ON CONFLICT (action) DO UPDATE SET implemented = true;

CREATE TEMP TABLE reference_update_policy ON COMMIT DROP AS
SELECT p.policy_id, pv.document AS old_document,
       jsonb_set(pv.document, '{Statement}',
           (SELECT jsonb_agg(CASE WHEN statement ->> 'Effect' = 'Allow'
                AND (statement -> 'Action') ? 'reference:Import'
              THEN jsonb_set(statement, '{Action}', (statement -> 'Action') ||
                '["reference:UpdateDepot","reference:UpdateOutlet","reference:UpdateVehicle"]'::jsonb)
              ELSE statement END ORDER BY ordinal)
            FROM jsonb_array_elements(pv.document -> 'Statement') WITH ORDINALITY AS entries(statement, ordinal))) AS new_document
FROM iam.policies p JOIN iam.policy_versions pv ON pv.policy_id = p.policy_id AND pv.is_default
WHERE p.name = 'WaypointAdministrator';

DELETE FROM reference_update_policy WHERE old_document = new_document;
UPDATE iam.policy_versions pv SET is_default = false FROM reference_update_policy draft
 WHERE pv.policy_id = draft.policy_id AND pv.is_default;
INSERT INTO iam.policy_versions (policy_id, version_number, document, is_default)
SELECT draft.policy_id, (SELECT max(version_number) + 1 FROM iam.policy_versions prior WHERE prior.policy_id = draft.policy_id),
       draft.new_document, true FROM reference_update_policy draft;
