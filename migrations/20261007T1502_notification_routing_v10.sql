-- Notification routing version 10. A version is never edited (R-NOT-09):
-- version 10 is version 9 with the live decisions of roadmap FF-09, and becomes
-- current. The store is asked; the dispatcher hears the answer, or that no
-- answer came by the deadline, so they can phone; the driver hears a Yes, which
-- changes what happens at the stop.

ALTER TABLE notification.routing_versions NO FORCE ROW LEVEL SECURITY;
ALTER TABLE notification.routing_rules NO FORCE ROW LEVEL SECURITY;

INSERT INTO notification.routing_versions (rule_version, published_at, is_current, note)
SELECT 10, TIMESTAMPTZ '2026-10-07 15:02:00+05:30', false, 'FF-09: live decisions between the dispatcher and a store'
WHERE NOT EXISTS (SELECT 1 FROM notification.routing_versions WHERE rule_version = 10);

INSERT INTO notification.routing_rules
    (rule_version, event_type, recipient_role, scope_kind, push, when_fact, when_values, only_if_none_for,
     title_template, body_template)
SELECT 10, event_type, recipient_role, scope_kind, push, when_fact, when_values, only_if_none_for,
       title_template, body_template
  FROM notification.routing_rules
 WHERE rule_version = 9
ON CONFLICT (rule_version, event_type, recipient_role) DO NOTHING;

INSERT INTO notification.routing_rules
    (rule_version, event_type, recipient_role, scope_kind, push, when_fact, when_values, only_if_none_for,
     title_template, body_template)
VALUES
    (10, 'decision.requested', 'store_manager', 'outlet', true, NULL, NULL, NULL,
     'Your answer is needed', '{question} Answer by {deadline}.'),
    (10, 'decision.answered', 'dispatcher', 'depot', true, NULL, NULL, NULL,
     '{outletId} answered {answer}', '{question}'),
    (10, 'decision.answered', 'driver', 'vehicle', true, 'answer', ARRAY['Yes'], NULL,
     '{outletId} answered Yes', '{driverNote}'),
    (10, 'decision.expired', 'dispatcher', 'depot', true, NULL, NULL, NULL,
     'No answer from {outletId}', '{question} Call the store.')
ON CONFLICT (rule_version, event_type, recipient_role) DO NOTHING;

UPDATE notification.routing_versions SET is_current = false WHERE is_current AND rule_version <> 10;
UPDATE notification.routing_versions SET is_current = true WHERE rule_version = 10;

ALTER TABLE notification.routing_versions FORCE ROW LEVEL SECURITY;
ALTER TABLE notification.routing_rules FORCE ROW LEVEL SECURITY;
