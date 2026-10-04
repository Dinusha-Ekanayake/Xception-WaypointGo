-- Notification routing version 5. A version is never edited (R-NOT-09):
-- version 5 is version 4 plus one rule, and becomes current.
--
--   R-NOT-15: when a plan is published, each outlet on it hears that its order
--             is planned, with its stop number and planned arrival (issue #224).
--             A store that scheduled an order ahead learns the day before that
--             the date holds; a deferral is already told by order.deferred.
--             NotificationConsumers.OnPlanPublished adds one outlet target per stop.

ALTER TABLE notification.routing_versions NO FORCE ROW LEVEL SECURITY;
ALTER TABLE notification.routing_rules NO FORCE ROW LEVEL SECURITY;

INSERT INTO notification.routing_versions (rule_version, published_at, is_current, note)
SELECT 5, TIMESTAMPTZ '2026-10-04 19:20:00+05:30', false, 'issue #224: a store hears its order is planned'
WHERE NOT EXISTS (SELECT 1 FROM notification.routing_versions WHERE rule_version = 5);

INSERT INTO notification.routing_rules
    (rule_version, event_type, recipient_role, scope_kind, push, when_fact, when_values, only_if_none_for,
     title_template, body_template)
SELECT 5, event_type, recipient_role, scope_kind, push, when_fact, when_values, only_if_none_for,
       title_template, body_template
  FROM notification.routing_rules
 WHERE rule_version = 4
ON CONFLICT (rule_version, event_type, recipient_role) DO NOTHING;

INSERT INTO notification.routing_rules
    (rule_version, event_type, recipient_role, scope_kind, push, when_fact, when_values, only_if_none_for,
     title_template, body_template)
VALUES
    (5, 'plan.published', 'store_manager', 'outlet', true, NULL, NULL, NULL,
     'Delivery planned for {serviceDate}',
     'Your order is stop {stopNumber}, planned arrival {plannedArrival}.')
ON CONFLICT (rule_version, event_type, recipient_role) DO NOTHING;

UPDATE notification.routing_versions SET is_current = false WHERE is_current AND rule_version <> 5;
UPDATE notification.routing_versions SET is_current = true WHERE rule_version = 5;

ALTER TABLE notification.routing_versions FORCE ROW LEVEL SECURITY;
ALTER TABLE notification.routing_rules FORCE ROW LEVEL SECURITY;
