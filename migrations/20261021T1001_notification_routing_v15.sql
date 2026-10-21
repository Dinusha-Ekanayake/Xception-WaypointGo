-- Notification routing version 15. A version is never edited (R-NOT-09):
-- version 15 is version 14 with a stop the dispatcher skipped heard by its store
-- and the vehicle's driver (R-EXE-31, R-NOT-20). The dispatcher who skipped it is
-- its author and is not told (R-NOT-07).

ALTER TABLE notification.routing_versions NO FORCE ROW LEVEL SECURITY;
ALTER TABLE notification.routing_rules NO FORCE ROW LEVEL SECURITY;

INSERT INTO notification.routing_versions (rule_version, published_at, is_current, note)
SELECT 15, TIMESTAMPTZ '2026-10-21 10:01:00+05:30', false, 'R-NOT-20: a skipped stop reaches its store and driver'
WHERE NOT EXISTS (SELECT 1 FROM notification.routing_versions WHERE rule_version = 15);

INSERT INTO notification.routing_rules
    (rule_version, event_type, recipient_role, scope_kind, push, when_fact, when_values, only_if_none_for,
     title_template, body_template)
SELECT 15, event_type, recipient_role, scope_kind, push, when_fact, when_values, only_if_none_for,
       title_template, body_template
  FROM notification.routing_rules
 WHERE rule_version = 14
ON CONFLICT (rule_version, event_type, recipient_role) DO NOTHING;

INSERT INTO notification.routing_rules
    (rule_version, event_type, recipient_role, scope_kind, push, when_fact, when_values, only_if_none_for,
     title_template, body_template)
VALUES
    (15, 'delivery.skipped', 'store_manager', 'outlet', true, NULL, NULL, NULL,
     '{headline}', 'Reason: {reason}'),
    (15, 'delivery.skipped', 'driver', 'vehicle', true, NULL, NULL, NULL,
     'Stop removed · {outletId}', 'The dispatcher took {outletId} off your route. Reason: {reason}')
ON CONFLICT (rule_version, event_type, recipient_role) DO NOTHING;

UPDATE notification.routing_versions SET is_current = false WHERE is_current AND rule_version <> 15;
UPDATE notification.routing_versions SET is_current = true WHERE rule_version = 15;

ALTER TABLE notification.routing_versions FORCE ROW LEVEL SECURITY;
ALTER TABLE notification.routing_rules FORCE ROW LEVEL SECURITY;
