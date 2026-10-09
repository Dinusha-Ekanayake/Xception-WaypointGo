-- Notification routing version 12. A version is never edited (R-NOT-09):
-- version 12 is version 11 with an unseen message heard (R-NOT-18). A message
-- for the driver still unread after ten minutes tells the depot's dispatchers
-- once, so they can phone; the driver is often driving.

ALTER TABLE notification.routing_versions NO FORCE ROW LEVEL SECURITY;
ALTER TABLE notification.routing_rules NO FORCE ROW LEVEL SECURITY;

INSERT INTO notification.routing_versions (rule_version, published_at, is_current, note)
SELECT 12, TIMESTAMPTZ '2026-10-07 20:00:00+05:30', false, 'R-NOT-18: the dispatcher hears of a message the driver has not seen'
WHERE NOT EXISTS (SELECT 1 FROM notification.routing_versions WHERE rule_version = 12);

INSERT INTO notification.routing_rules
    (rule_version, event_type, recipient_role, scope_kind, push, when_fact, when_values, only_if_none_for,
     title_template, body_template)
SELECT 12, event_type, recipient_role, scope_kind, push, when_fact, when_values, only_if_none_for,
       title_template, body_template
  FROM notification.routing_rules
 WHERE rule_version = 11
ON CONFLICT (rule_version, event_type, recipient_role) DO NOTHING;

INSERT INTO notification.routing_rules
    (rule_version, event_type, recipient_role, scope_kind, push, when_fact, when_values, only_if_none_for,
     title_template, body_template)
VALUES
    (12, 'message.unseen', 'dispatcher', 'depot', true, NULL, NULL, NULL,
     '{vehicleId}: the driver has not seen a message', '{heading} at {sent}: {excerpt}. Unread {minutes} min; phone if it is urgent.')
ON CONFLICT (rule_version, event_type, recipient_role) DO NOTHING;

UPDATE notification.routing_versions SET is_current = false WHERE is_current AND rule_version <> 12;
UPDATE notification.routing_versions SET is_current = true WHERE rule_version = 12;

ALTER TABLE notification.routing_versions FORCE ROW LEVEL SECURITY;
ALTER TABLE notification.routing_rules FORCE ROW LEVEL SECURITY;

-- The job looks only at drivers' unread message notifications.
CREATE INDEX IF NOT EXISTS ix_notifications_unseen_messages
    ON notification.notifications (created_at)
 WHERE read_at IS NULL AND event_type = 'message.posted';
