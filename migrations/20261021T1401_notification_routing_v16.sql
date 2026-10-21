-- Notification routing version 16. A version is never edited (R-NOT-09):
-- version 16 is version 15 with the quiet goods problems kept from the
-- dispatcher and the loader's shortfall flags kept from the store (R-ISS-16).
-- The consumers name the audience as facts, from the issues parameters, so the
-- quiet types are defined once: loud ('yes' or 'no') for the dispatcher's
-- rules, forStore ('yes' or 'no') for the store's.

ALTER TABLE notification.routing_versions NO FORCE ROW LEVEL SECURITY;
ALTER TABLE notification.routing_rules NO FORCE ROW LEVEL SECURITY;

INSERT INTO notification.routing_versions (rule_version, published_at, is_current, note)
SELECT 16, TIMESTAMPTZ '2026-10-21 14:01:00+05:30', false, 'R-ISS-16: quiet goods problems'
WHERE NOT EXISTS (SELECT 1 FROM notification.routing_versions WHERE rule_version = 16);

INSERT INTO notification.routing_rules
    (rule_version, event_type, recipient_role, scope_kind, push, when_fact, when_values, only_if_none_for,
     title_template, body_template)
SELECT 16, event_type, recipient_role, scope_kind, push, when_fact, when_values, only_if_none_for,
       title_template, body_template
  FROM notification.routing_rules
 WHERE rule_version = 15
ON CONFLICT (rule_version, event_type, recipient_role) DO NOTHING;

UPDATE notification.routing_rules SET when_fact = 'loud', when_values = ARRAY['yes']
 WHERE rule_version = 16 AND event_type IN ('issue.raised', 'issue.escalated') AND recipient_role = 'dispatcher';
UPDATE notification.routing_rules SET when_fact = 'forStore', when_values = ARRAY['yes']
 WHERE rule_version = 16 AND event_type IN ('issue.raised', 'issue.escalated', 'issue.resolved')
   AND recipient_role = 'store_manager';

UPDATE notification.routing_versions SET is_current = false WHERE is_current AND rule_version <> 16;
UPDATE notification.routing_versions SET is_current = true WHERE rule_version = 16;

ALTER TABLE notification.routing_versions FORCE ROW LEVEL SECURITY;
ALTER TABLE notification.routing_rules FORCE ROW LEVEL SECURITY;
