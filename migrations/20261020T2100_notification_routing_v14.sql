-- Notification routing version 14. A version is never edited (R-NOT-09):
-- version 14 is version 13 with a resolved issue heard by its store (R-NOT-19).
-- The issue's outlet is told it is resolved and what was done; the dispatcher
-- who resolved it is its author and is not told (R-NOT-07).

ALTER TABLE notification.routing_versions NO FORCE ROW LEVEL SECURITY;
ALTER TABLE notification.routing_rules NO FORCE ROW LEVEL SECURITY;

INSERT INTO notification.routing_versions (rule_version, published_at, is_current, note)
SELECT 14, TIMESTAMPTZ '2026-10-20 21:00:00+05:30', false, 'R-NOT-19: the store hears its issue is resolved'
WHERE NOT EXISTS (SELECT 1 FROM notification.routing_versions WHERE rule_version = 14);

INSERT INTO notification.routing_rules
    (rule_version, event_type, recipient_role, scope_kind, push, when_fact, when_values, only_if_none_for,
     title_template, body_template)
SELECT 14, event_type, recipient_role, scope_kind, push, when_fact, when_values, only_if_none_for,
       title_template, body_template
  FROM notification.routing_rules
 WHERE rule_version = 13
ON CONFLICT (rule_version, event_type, recipient_role) DO NOTHING;

INSERT INTO notification.routing_rules
    (rule_version, event_type, recipient_role, scope_kind, push, when_fact, when_values, only_if_none_for,
     title_template, body_template)
VALUES
    (14, 'issue.resolved', 'store_manager', 'outlet', true, NULL, NULL, NULL,
     'Issue resolved', '{summary}')
ON CONFLICT (rule_version, event_type, recipient_role) DO NOTHING;

UPDATE notification.routing_versions SET is_current = false WHERE is_current AND rule_version <> 14;
UPDATE notification.routing_versions SET is_current = true WHERE rule_version = 14;

ALTER TABLE notification.routing_versions FORCE ROW LEVEL SECURITY;
ALTER TABLE notification.routing_rules FORCE ROW LEVEL SECURITY;
