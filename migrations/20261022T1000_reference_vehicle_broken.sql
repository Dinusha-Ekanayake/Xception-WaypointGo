-- Item 5 of @jv-ransika's plan (docs/issues/vehicle-broken/PLAN.md, R-FLT-06): a
-- driver marks the vehicle they drive today broken or OK, and the change reaches
-- Planning and the dispatcher.

-- ---- the driver may set their own vehicle's day status ------------------------
-- The handler limits a driver to the vehicle they drive today and to broken or OK;
-- the policy only lets the command reach it (decision B18 replaced).
CREATE OR REPLACE FUNCTION pg_temp.broken_statement(doc jsonb) RETURNS jsonb
LANGUAGE sql IMMUTABLE AS $$
    SELECT CASE
        WHEN EXISTS (SELECT 1 FROM jsonb_array_elements(doc -> 'Statement') s WHERE s ->> 'Sid' = 'MarkOwnVehicle')
        THEN doc
        ELSE jsonb_set(doc, '{Statement}',
                       (doc -> 'Statement') || jsonb_build_array(jsonb_build_object(
                           'Sid', 'MarkOwnVehicle',
                           'Effect', 'Allow',
                           'Action', jsonb_build_array('vehicle:SetDayStatus'),
                           'Resource', jsonb_build_array('wpt:ref:vehicle:*'))))
    END
$$;

CREATE TEMP TABLE broken_policy_docs ON COMMIT DROP AS
SELECT p.policy_id, pg_temp.broken_statement(pv.document) AS document, pv.document AS previous
FROM iam.policies p
JOIN iam.policy_versions pv ON pv.policy_id = p.policy_id AND pv.is_default
WHERE p.name = 'WaypointDriver';

DELETE FROM broken_policy_docs WHERE document = previous;

UPDATE iam.policy_versions pv SET is_default = false
FROM broken_policy_docs d WHERE pv.policy_id = d.policy_id AND pv.is_default;

INSERT INTO iam.policy_versions (policy_id, version_number, document, is_default)
SELECT d.policy_id, (SELECT max(version_number) + 1 FROM iam.policy_versions x WHERE x.policy_id = d.policy_id),
       d.document, true
FROM broken_policy_docs d;

UPDATE iam.policy_generation SET generation = generation + 1, changed_at = now()
WHERE EXISTS (SELECT 1 FROM broken_policy_docs);

-- ---- notification routing version 19 --------------------------------------------
-- Version 18 with the dispatcher told in plain words, at once (push): "VEH012 out
-- of use" and what follows, or "VEH012 back in use". The words come from the route.
ALTER TABLE notification.routing_versions NO FORCE ROW LEVEL SECURITY;
ALTER TABLE notification.routing_rules NO FORCE ROW LEVEL SECURITY;

INSERT INTO notification.routing_versions (rule_version, published_at, is_current, note)
SELECT 19, TIMESTAMPTZ '2026-10-22 10:00:00+05:30', false, 'R-FLT-06: a vehicle out of use or back in use, told at once'
WHERE NOT EXISTS (SELECT 1 FROM notification.routing_versions WHERE rule_version = 19);

INSERT INTO notification.routing_rules
    (rule_version, event_type, recipient_role, scope_kind, push, when_fact, when_values, only_if_none_for,
     title_template, body_template)
SELECT 19, event_type, recipient_role, scope_kind, push, when_fact, when_values, only_if_none_for,
       title_template, body_template
  FROM notification.routing_rules
 WHERE rule_version = 18 AND NOT (event_type = 'vehicle.status_changed' AND recipient_role = 'dispatcher')
ON CONFLICT (rule_version, event_type, recipient_role) DO NOTHING;

INSERT INTO notification.routing_rules
    (rule_version, event_type, recipient_role, scope_kind, push, when_fact, when_values, only_if_none_for,
     title_template, body_template)
VALUES
    (19, 'vehicle.status_changed', 'dispatcher', 'depot', true, NULL, NULL, NULL,
     '{vehicleId} {statusLabel}', '{next} {reason}')
ON CONFLICT (rule_version, event_type, recipient_role) DO NOTHING;

UPDATE notification.routing_versions SET is_current = false WHERE is_current AND rule_version <> 19;
UPDATE notification.routing_versions SET is_current = true WHERE rule_version = 19;

ALTER TABLE notification.routing_versions FORCE ROW LEVEL SECURITY;
ALTER TABLE notification.routing_rules FORCE ROW LEVEL SECURITY;
