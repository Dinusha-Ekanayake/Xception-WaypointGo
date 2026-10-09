-- Notification routing version 9. A version is never edited (R-NOT-09):
-- version 9 is version 8 with the store manager also told of a post on a trip's
-- live channel (roadmap FF-04), and becomes current. The event names only the
-- outlets still active in the channel, so a store whose goods are all delivered
-- is not told. The driver's rule fires for 'driver' and 'all' only, as before:
-- the driver is never told of the channel.

ALTER TABLE notification.routing_versions NO FORCE ROW LEVEL SECURITY;
ALTER TABLE notification.routing_rules NO FORCE ROW LEVEL SECURITY;

INSERT INTO notification.routing_versions (rule_version, published_at, is_current, note)
SELECT 9, TIMESTAMPTZ '2026-10-07 14:02:00+05:30', false, 'FF-04: the store hears the trip''s live channel'
WHERE NOT EXISTS (SELECT 1 FROM notification.routing_versions WHERE rule_version = 9);

INSERT INTO notification.routing_rules
    (rule_version, event_type, recipient_role, scope_kind, push, when_fact, when_values, only_if_none_for,
     title_template, body_template)
SELECT 9, event_type, recipient_role, scope_kind, push, when_fact, when_values, only_if_none_for,
       title_template, body_template
  FROM notification.routing_rules
 WHERE rule_version = 8
ON CONFLICT (rule_version, event_type, recipient_role) DO NOTHING;

UPDATE notification.routing_rules
   SET when_values = ARRAY['outlet', 'all', 'channel']
 WHERE rule_version = 9 AND event_type = 'message.posted' AND recipient_role = 'store_manager';

UPDATE notification.routing_versions SET is_current = false WHERE is_current AND rule_version <> 9;
UPDATE notification.routing_versions SET is_current = true WHERE rule_version = 9;

ALTER TABLE notification.routing_versions FORCE ROW LEVEL SECURITY;
ALTER TABLE notification.routing_rules FORCE ROW LEVEL SECURITY;
