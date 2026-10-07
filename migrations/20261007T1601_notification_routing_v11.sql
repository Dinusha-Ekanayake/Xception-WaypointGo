-- Notification routing version 11. A version is never edited (R-NOT-09):
-- version 11 is version 10 with the attention watch heard (issue #268, FF-09),
-- and becomes current. A window about to be missed rings the depot's
-- dispatchers even when the Live screen is closed; a failed stop is already
-- told by delivery.failed, so it is not told twice. A critical item nobody
-- acknowledged is reminded, at most as often as the watch's cap allows.

ALTER TABLE notification.routing_versions NO FORCE ROW LEVEL SECURITY;
ALTER TABLE notification.routing_rules NO FORCE ROW LEVEL SECURITY;

INSERT INTO notification.routing_versions (rule_version, published_at, is_current, note)
SELECT 11, TIMESTAMPTZ '2026-10-07 16:01:00+05:30', false, 'FF-09: the attention watch rings the dispatcher'
WHERE NOT EXISTS (SELECT 1 FROM notification.routing_versions WHERE rule_version = 11);

INSERT INTO notification.routing_rules
    (rule_version, event_type, recipient_role, scope_kind, push, when_fact, when_values, only_if_none_for,
     title_template, body_template)
SELECT 11, event_type, recipient_role, scope_kind, push, when_fact, when_values, only_if_none_for,
       title_template, body_template
  FROM notification.routing_rules
 WHERE rule_version = 10
ON CONFLICT (rule_version, event_type, recipient_role) DO NOTHING;

INSERT INTO notification.routing_rules
    (rule_version, event_type, recipient_role, scope_kind, push, when_fact, when_values, only_if_none_for,
     title_template, body_template)
VALUES
    (11, 'attention.raised', 'dispatcher', 'depot', true, 'kind', ARRAY['WINDOW_AT_RISK'], NULL,
     '{outletId} may miss its window', '{vehicleId} · {left}'),
    (11, 'attention.reminded', 'dispatcher', 'depot', true, NULL, NULL, NULL,
     'Still waiting: {what} at {outletId}', 'Nobody has acted on it yet.')
ON CONFLICT (rule_version, event_type, recipient_role) DO NOTHING;

UPDATE notification.routing_versions SET is_current = false WHERE is_current AND rule_version <> 11;
UPDATE notification.routing_versions SET is_current = true WHERE rule_version = 11;

ALTER TABLE notification.routing_versions FORCE ROW LEVEL SECURITY;
ALTER TABLE notification.routing_rules FORCE ROW LEVEL SECURITY;
