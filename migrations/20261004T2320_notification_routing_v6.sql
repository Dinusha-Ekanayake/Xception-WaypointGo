-- Notification routing version 6. A version is never edited (R-NOT-09):
-- version 6 is version 5 plus one rule, and becomes current.
--
--   R-NOT-16: when a day a store already booked worsens to busy or at risk,
--             the outlet's store manager is told once per order and status,
--             with the reason (issue #224, slice 3). The order still goes for
--             that day; the plan made the afternoon before decides.
--   R-NOT-15:  the store's "Delivery planned for" title reads the day as the
--             glossary writes it ("Fri 9 Oct", fact serviceDay) rather than an
--             ISO date. Version 5 is not edited; version 6 carries the fix.

ALTER TABLE notification.routing_versions NO FORCE ROW LEVEL SECURITY;
ALTER TABLE notification.routing_rules NO FORCE ROW LEVEL SECURITY;

INSERT INTO notification.routing_versions (rule_version, published_at, is_current, note)
SELECT 6, TIMESTAMPTZ '2026-10-04 23:20:00+05:30', false, 'issue #224: a store hears a booked day turned busy'
WHERE NOT EXISTS (SELECT 1 FROM notification.routing_versions WHERE rule_version = 6);

INSERT INTO notification.routing_rules
    (rule_version, event_type, recipient_role, scope_kind, push, when_fact, when_values, only_if_none_for,
     title_template, body_template)
SELECT 6, event_type, recipient_role, scope_kind, push, when_fact, when_values, only_if_none_for,
       title_template, body_template
  FROM notification.routing_rules
 WHERE rule_version = 5
ON CONFLICT (rule_version, event_type, recipient_role) DO NOTHING;

INSERT INTO notification.routing_rules
    (rule_version, event_type, recipient_role, scope_kind, push, when_fact, when_values, only_if_none_for,
     title_template, body_template)
VALUES
    (6, 'order.outlook_changed', 'store_manager', 'outlet', true, NULL, NULL, NULL,
     '{deliveryDay} is {statusWords}',
     '{reason}. Your order may move a day; dispatch plans it the afternoon before.')
ON CONFLICT (rule_version, event_type, recipient_role) DO NOTHING;

UPDATE notification.routing_rules
   SET title_template = 'Delivery planned for {serviceDay}'
 WHERE rule_version = 6 AND event_type = 'plan.published' AND recipient_role = 'store_manager';

UPDATE notification.routing_versions SET is_current = false WHERE is_current AND rule_version <> 6;
UPDATE notification.routing_versions SET is_current = true WHERE rule_version = 6;

ALTER TABLE notification.routing_versions FORCE ROW LEVEL SECURITY;
ALTER TABLE notification.routing_rules FORCE ROW LEVEL SECURITY;
