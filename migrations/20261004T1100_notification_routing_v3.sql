-- Notification routing version 3. A version is never edited (R-NOT-09):
-- version 3 is version 2 plus two rules, and becomes current.
--
--   R-NOT-12: a revised plan tells the outlets it now reaches on another trip or
--             at another time, and only those. NotificationConsumers.OnPlanRevised
--             adds one outlet target for each of the event's affectedOutletIds.
--   R-NOT-13: a dispatcher's message about an order the plan could not serve goes
--             to the store managers of its outlet (plan.store_contacted).

-- Both tables force row-level security and the migrator runs with row
-- security off, so the force is lifted for these writes and put back after
-- (AGENTS.md, Data and Migration Rules). Nothing reads them as their owner.
ALTER TABLE notification.routing_versions NO FORCE ROW LEVEL SECURITY;
ALTER TABLE notification.routing_rules NO FORCE ROW LEVEL SECURITY;

INSERT INTO notification.routing_versions (rule_version, published_at, is_current, note)
SELECT 3, TIMESTAMPTZ '2026-10-04 09:00:00+05:30', false,
       'plan screen: a revision tells only the outlets it changes; a dispatcher can message a store'
WHERE NOT EXISTS (SELECT 1 FROM notification.routing_versions WHERE rule_version = 3);

INSERT INTO notification.routing_rules
    (rule_version, event_type, recipient_role, scope_kind, push, when_fact, when_values, only_if_none_for,
     title_template, body_template)
SELECT 3, event_type, recipient_role, scope_kind, push, when_fact, when_values, only_if_none_for,
       title_template, body_template
  FROM notification.routing_rules
 WHERE rule_version = 2
ON CONFLICT (rule_version, event_type, recipient_role) DO NOTHING;

INSERT INTO notification.routing_rules
    (rule_version, event_type, recipient_role, scope_kind, push, when_fact, when_values, only_if_none_for,
     title_template, body_template)
VALUES
    (3, 'plan.revised', 'store_manager', 'outlet', true, NULL, NULL, NULL,
     'Your delivery plan changed',
     'The plan for {serviceDate} changed for your outlet: {reason}.'),
    (3, 'plan.store_contacted', 'store_manager', 'outlet', true, NULL, NULL, NULL,
     'Message from the dispatcher',
     '{message}')
ON CONFLICT (rule_version, event_type, recipient_role) DO NOTHING;

UPDATE notification.routing_versions SET is_current = false WHERE is_current AND rule_version <> 3;
UPDATE notification.routing_versions SET is_current = true WHERE rule_version = 3;

ALTER TABLE notification.routing_versions FORCE ROW LEVEL SECURITY;
ALTER TABLE notification.routing_rules FORCE ROW LEVEL SECURITY;
