-- Notification routing version 7. A version is never edited (R-NOT-09):
-- version 7 is version 6 plus two rules, and becomes current.
--
--   R-EXE-25: when the driver arrives at the outlet, its store manager is told
--             the vehicle is there, so someone comes out to unload and check.
--   R-EXE-24: when the driver reports at the depot with the vehicle, the
--             depot's loaders are told, so one takes that trip to load.

ALTER TABLE notification.routing_versions NO FORCE ROW LEVEL SECURITY;
ALTER TABLE notification.routing_rules NO FORCE ROW LEVEL SECURITY;

INSERT INTO notification.routing_versions (rule_version, published_at, is_current, note)
SELECT 7, TIMESTAMPTZ '2026-10-05 14:02:00+05:30', false, 'main flow: arrival at the store, driver at the depot'
WHERE NOT EXISTS (SELECT 1 FROM notification.routing_versions WHERE rule_version = 7);

INSERT INTO notification.routing_rules
    (rule_version, event_type, recipient_role, scope_kind, push, when_fact, when_values, only_if_none_for,
     title_template, body_template)
SELECT 7, event_type, recipient_role, scope_kind, push, when_fact, when_values, only_if_none_for,
       title_template, body_template
  FROM notification.routing_rules
 WHERE rule_version = 6
ON CONFLICT (rule_version, event_type, recipient_role) DO NOTHING;

INSERT INTO notification.routing_rules
    (rule_version, event_type, recipient_role, scope_kind, push, when_fact, when_values, only_if_none_for,
     title_template, body_template)
VALUES
    (7, 'delivery.arrived', 'store_manager', 'outlet', true, NULL, NULL, NULL,
     '{vehicleId} is at your store',
     'The driver has arrived. Please help unload, check the goods and confirm the receipt.'),
    (7, 'vehicle.at_depot', 'loader', 'depot', true, NULL, NULL, NULL,
     '{vehicleId} is at the dock',
     'The driver is at the depot with {vehicleId}. Take its trip and load it.')
ON CONFLICT (rule_version, event_type, recipient_role) DO NOTHING;

UPDATE notification.routing_versions SET is_current = false WHERE is_current AND rule_version <> 7;
UPDATE notification.routing_versions SET is_current = true WHERE rule_version = 7;

ALTER TABLE notification.routing_versions FORCE ROW LEVEL SECURITY;
ALTER TABLE notification.routing_rules FORCE ROW LEVEL SECURITY;
