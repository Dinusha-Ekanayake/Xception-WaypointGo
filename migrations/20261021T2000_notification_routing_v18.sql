-- Notification routing version 18. A version is never edited (R-NOT-09):
-- version 18 is version 17 with a breakdown or a blocked road heard by the stores
-- still waiting for the vehicle (R-NOT-22). Only the kind of delay, never the
-- driver's words; stores already served read the notice in the trip's room.

ALTER TABLE notification.routing_versions NO FORCE ROW LEVEL SECURITY;
ALTER TABLE notification.routing_rules NO FORCE ROW LEVEL SECURITY;

INSERT INTO notification.routing_versions (rule_version, published_at, is_current, note)
SELECT 18, TIMESTAMPTZ '2026-10-21 20:00:00+05:30', false, 'R-NOT-22: stores still waiting hear a breakdown or a blocked road'
WHERE NOT EXISTS (SELECT 1 FROM notification.routing_versions WHERE rule_version = 18);

INSERT INTO notification.routing_rules
    (rule_version, event_type, recipient_role, scope_kind, push, when_fact, when_values, only_if_none_for,
     title_template, body_template)
SELECT 18, event_type, recipient_role, scope_kind, push, when_fact, when_values, only_if_none_for,
       title_template, body_template
  FROM notification.routing_rules
 WHERE rule_version = 17
ON CONFLICT (rule_version, event_type, recipient_role) DO NOTHING;

INSERT INTO notification.routing_rules
    (rule_version, event_type, recipient_role, scope_kind, push, when_fact, when_values, only_if_none_for,
     title_template, body_template)
VALUES
    (18, 'vehicle.fault_reported', 'store_manager', 'outlet', true, NULL, NULL, NULL,
     '{vehicleId} is delayed: {delay}', 'Your delivery may come later than expected. The dispatcher will tell you in the trip''s messages.'),
    (18, 'road.disruption_reported', 'store_manager', 'outlet', true, NULL, NULL, NULL,
     '{vehicleId} is delayed: {delay}', 'Your delivery may come later than expected. The dispatcher will tell you in the trip''s messages.')
ON CONFLICT (rule_version, event_type, recipient_role) DO NOTHING;

UPDATE notification.routing_versions SET is_current = false WHERE is_current AND rule_version <> 18;
UPDATE notification.routing_versions SET is_current = true WHERE rule_version = 18;

ALTER TABLE notification.routing_versions FORCE ROW LEVEL SECURITY;
ALTER TABLE notification.routing_rules FORCE ROW LEVEL SECURITY;
