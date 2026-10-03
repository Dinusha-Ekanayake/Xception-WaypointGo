-- Notification routing version 3 (issue #136). A version is never edited
-- (R-NOT-09): version 3 is version 2 plus the rules for message.posted, and
-- becomes current.
--
--   R-NOT-14: a message on a thread reaches the people it is for, and nobody
--             else on the thread but the dispatcher. NotificationConsumers
--             .OnMessagePosted names the depot, the vehicle and the outlets the
--             message is for, with the fact audience; the loader, driver and
--             store rules fire only for their own audience.
--             The dispatcher is told of every message on the depot's trips,
--             whoever it is for: everything appears under the dispatcher's bell.
--             The author is never told (R-NOT-07).

ALTER TABLE notification.routing_versions NO FORCE ROW LEVEL SECURITY;
ALTER TABLE notification.routing_rules NO FORCE ROW LEVEL SECURITY;

INSERT INTO notification.routing_versions (rule_version, published_at, is_current, note)
SELECT 3, TIMESTAMPTZ '2026-10-04 12:00:00+05:30', false, 'issue #136: messages on a trip''s thread'
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
    (3, 'message.posted', 'dispatcher', 'depot', true, NULL, NULL, NULL,
     '{heading}', '{excerpt}'),
    (3, 'message.posted', 'loader', 'depot', true, 'audience', ARRAY['loader','all'], NULL,
     '{heading}', '{excerpt}'),
    (3, 'message.posted', 'driver', 'vehicle', true, 'audience', ARRAY['driver','all'], NULL,
     '{heading}', '{excerpt}'),
    (3, 'message.posted', 'store_manager', 'outlet', true, 'audience', ARRAY['outlet','all'], NULL,
     '{heading}', '{excerpt}')
ON CONFLICT (rule_version, event_type, recipient_role) DO NOTHING;

UPDATE notification.routing_versions SET is_current = false WHERE is_current AND rule_version <> 3;
UPDATE notification.routing_versions SET is_current = true WHERE rule_version = 3;

ALTER TABLE notification.routing_versions FORCE ROW LEVEL SECURITY;
ALTER TABLE notification.routing_rules FORCE ROW LEVEL SECURITY;
