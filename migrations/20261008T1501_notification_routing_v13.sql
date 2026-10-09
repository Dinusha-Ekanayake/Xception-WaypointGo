-- Notification routing version 13. A version is never edited (R-NOT-09):
-- version 13 is version 12 with a likely-late stop heard (roadmap T1-02,
-- R-ML-22). The event names its audience: while the flag is learning, or once
-- a store has had two this week, the depot's dispatchers are told and may pass
-- it on; once the flag's precision has earned it, the store is told.

ALTER TABLE notification.routing_versions NO FORCE ROW LEVEL SECURITY;
ALTER TABLE notification.routing_rules NO FORCE ROW LEVEL SECURITY;

INSERT INTO notification.routing_versions (rule_version, published_at, is_current, note)
SELECT 13, TIMESTAMPTZ '2026-10-08 15:01:00+05:30', false, 'T1-02: a published stop will likely be late'
WHERE NOT EXISTS (SELECT 1 FROM notification.routing_versions WHERE rule_version = 13);

INSERT INTO notification.routing_rules
    (rule_version, event_type, recipient_role, scope_kind, push, when_fact, when_values, only_if_none_for,
     title_template, body_template)
SELECT 13, event_type, recipient_role, scope_kind, push, when_fact, when_values, only_if_none_for,
       title_template, body_template
  FROM notification.routing_rules
 WHERE rule_version = 12
ON CONFLICT (rule_version, event_type, recipient_role) DO NOTHING;

INSERT INTO notification.routing_rules
    (rule_version, event_type, recipient_role, scope_kind, push, when_fact, when_values, only_if_none_for,
     title_template, body_template)
VALUES
    (13, 'stop.likely_late', 'dispatcher', 'depot', true, 'audience', ARRAY['DISPATCHER'], NULL,
     '{outletId} will likely be late · {percent}%', '{deliveryDay} · window closes {closes} · {expected}. Tell the store if it helps.'),
    (13, 'stop.likely_late', 'store_manager', 'outlet', true, 'audience', ARRAY['STORE'], NULL,
     'Your delivery may be late', '{deliveryDay} · {storeLine}')
ON CONFLICT (rule_version, event_type, recipient_role) DO NOTHING;

UPDATE notification.routing_versions SET is_current = false WHERE is_current AND rule_version <> 13;
UPDATE notification.routing_versions SET is_current = true WHERE rule_version = 13;

ALTER TABLE notification.routing_versions FORCE ROW LEVEL SECURITY;
ALTER TABLE notification.routing_rules FORCE ROW LEVEL SECURITY;
