-- A store manager may read their outlet and the calendar (issue #5, R-IAM-28).
--
--     The store screens read the outlet for its name, dock and window, and the
--     calendar to say before confirming that a delivery date rolls forward
--     (D-I). The seeded store policy never had reference:Read, so against a real
--     backend both reads were refused and the screen showed neither.
--
--     It was safe to leave out while reference reads were unscoped: the action
--     on "*" would have let one outlet read every other outlet and the fleet.
--     Now that an outlet read is checked against the actor's own scope
--     (ReferenceScope), the permission can be given, and it is given narrowly:
--     outlets and calendar days, not vehicles, depot lists or versions.
--
--     A policy version is immutable (R-IAM-04), so this is a new default
--     version, and the generation moves with it (R-IAM-21).
-- ---------------------------------------------------------------------

CREATE TEMP TABLE store_reference_doc ON COMMIT DROP AS
SELECT p.policy_id,
       jsonb_set(
           pv.document, '{Statement}',
           (pv.document -> 'Statement')
           || jsonb_build_array(
                  jsonb_build_object(
                      'Sid', 'ReadOwnOutletAndCalendar',
                      'Effect', 'Allow',
                      'Action', jsonb_build_array('reference:Read'),
                      'Resource', jsonb_build_array('wpt:ref:outlet:*', 'wpt:ref:calendar:*')))) AS document
FROM iam.policies p
JOIN iam.policy_versions pv ON pv.policy_id = p.policy_id AND pv.is_default
WHERE p.name = 'WaypointStoreManager'
  -- Repeatable in spirit: a document that already carries the statement is left alone.
  AND NOT EXISTS (
        SELECT 1 FROM jsonb_array_elements(pv.document -> 'Statement') s
         WHERE s ->> 'Sid' = 'ReadOwnOutletAndCalendar');

UPDATE iam.policy_versions pv
SET is_default = false
FROM store_reference_doc d
WHERE pv.policy_id = d.policy_id AND pv.is_default;

INSERT INTO iam.policy_versions (policy_id, version_number, document, is_default)
SELECT d.policy_id,
       (SELECT max(version_number) + 1 FROM iam.policy_versions x WHERE x.policy_id = d.policy_id),
       d.document,
       true
FROM store_reference_doc d;

UPDATE iam.policies p
SET row_version = p.row_version + 1
FROM store_reference_doc d
WHERE p.policy_id = d.policy_id;

UPDATE iam.policy_generation
SET generation = generation + 1, changed_at = now()
WHERE EXISTS (SELECT 1 FROM store_reference_doc);
