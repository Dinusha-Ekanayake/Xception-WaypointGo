-- Issue #224: a store manager may read how likely a delivery date is to be kept
-- (R-ML-07), for their own outlet and their own orders.
--
--     ml:ReadOutlook is a new action (a catalogue row and a handler, R-IAM-*).
--     The outlook answers in totals for the outlet's depot and never names
--     another outlet or order; the outlet is checked against the actor's scope
--     before anything is read. ml:Read stays the dispatcher's: it reads
--     forecasts, scorings and models, which a store has no business with.
--
--     A policy version is immutable (R-IAM-04), so this is a new default
--     version, and the generation moves with it (R-IAM-21).

INSERT INTO iam.action_catalogue (action, module, description, implemented) VALUES
    ('ml:ReadOutlook', 'intelligence', 'Read how likely an outlet''s delivery dates and orders are to be kept', true)
ON CONFLICT (action) DO NOTHING;

CREATE TEMP TABLE store_outlook_doc ON COMMIT DROP AS
SELECT p.policy_id,
       jsonb_set(
           pv.document, '{Statement}',
           (pv.document -> 'Statement')
           || jsonb_build_array(
                  jsonb_build_object(
                      'Sid', 'ReadOwnDeliveryOutlook',
                      'Effect', 'Allow',
                      'Action', jsonb_build_array('ml:ReadOutlook'),
                      'Resource', jsonb_build_array('wpt:ml:outlet:*', 'wpt:ml:order:*')))) AS document
FROM iam.policies p
JOIN iam.policy_versions pv ON pv.policy_id = p.policy_id AND pv.is_default
WHERE p.name = 'WaypointStoreManager'
  AND NOT EXISTS (
        SELECT 1 FROM jsonb_array_elements(pv.document -> 'Statement') s
         WHERE s ->> 'Sid' = 'ReadOwnDeliveryOutlook');

UPDATE iam.policy_versions pv
SET is_default = false
FROM store_outlook_doc d
WHERE pv.policy_id = d.policy_id AND pv.is_default;

INSERT INTO iam.policy_versions (policy_id, version_number, document, is_default)
SELECT d.policy_id,
       (SELECT max(version_number) + 1 FROM iam.policy_versions x WHERE x.policy_id = d.policy_id),
       d.document,
       true
FROM store_outlook_doc d;

UPDATE iam.policies p
SET row_version = p.row_version + 1
FROM store_outlook_doc d
WHERE p.policy_id = d.policy_id;

UPDATE iam.policy_generation
SET generation = generation + 1, changed_at = now()
WHERE EXISTS (SELECT 1 FROM store_outlook_doc);
