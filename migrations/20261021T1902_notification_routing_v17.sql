-- Notification routing version 17. A version is never edited (R-NOT-09):
-- version 17 is version 16 with the driver's report of a closed store or a stop
-- that cannot unload heard by the depot's dispatchers, who decide, and by the
-- store (R-EXE-32, R-NOT-21).

ALTER TABLE notification.routing_versions NO FORCE ROW LEVEL SECURITY;
ALTER TABLE notification.routing_rules NO FORCE ROW LEVEL SECURITY;

INSERT INTO notification.routing_versions (rule_version, published_at, is_current, note)
SELECT 17, TIMESTAMPTZ '2026-10-21 19:02:00+05:30', false, 'R-NOT-21: a closed store or a stop that cannot unload reaches the dispatcher and the store'
WHERE NOT EXISTS (SELECT 1 FROM notification.routing_versions WHERE rule_version = 17);

INSERT INTO notification.routing_rules
    (rule_version, event_type, recipient_role, scope_kind, push, when_fact, when_values, only_if_none_for,
     title_template, body_template)
SELECT 17, event_type, recipient_role, scope_kind, push, when_fact, when_values, only_if_none_for,
       title_template, body_template
  FROM notification.routing_rules
 WHERE rule_version = 16
ON CONFLICT (rule_version, event_type, recipient_role) DO NOTHING;

INSERT INTO notification.routing_rules
    (rule_version, event_type, recipient_role, scope_kind, push, when_fact, when_values, only_if_none_for,
     title_template, body_template)
VALUES
    (17, 'delivery.problem_reported', 'dispatcher', 'depot', true, NULL, NULL, NULL,
     '{outletId} · {problem}', '{vehicleId} is at {outletId}. Decide in the trip''s messages: skip the store, or tell the driver to wait.'),
    (17, 'delivery.problem_reported', 'store_manager', 'outlet', true, NULL, NULL, NULL,
     '{headline}', 'The dispatcher decides what happens to today''s delivery and will tell you in the trip''s messages.')
ON CONFLICT (rule_version, event_type, recipient_role) DO NOTHING;

UPDATE notification.routing_versions SET is_current = false WHERE is_current AND rule_version <> 17;
UPDATE notification.routing_versions SET is_current = true WHERE rule_version = 17;

ALTER TABLE notification.routing_versions FORCE ROW LEVEL SECURITY;
ALTER TABLE notification.routing_rules FORCE ROW LEVEL SECURITY;
