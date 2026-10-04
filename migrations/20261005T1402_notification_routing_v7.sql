-- Notification routing version 7. A version is never edited (R-NOT-09):
-- version 7 is version 6 with one message reworded, and becomes current.
--
--   R-NOT-17: in the store-led handover (issue #21) the driver's handover is the
--             store's cue to check the load and send its report, so the store
--             manager's "Delivery recorded ... proof is ready to review" now
--             says the delivery was handed over and asks for the check.

ALTER TABLE notification.routing_versions NO FORCE ROW LEVEL SECURITY;
ALTER TABLE notification.routing_rules NO FORCE ROW LEVEL SECURITY;

INSERT INTO notification.routing_versions (rule_version, published_at, is_current, note)
SELECT 7, TIMESTAMPTZ '2026-10-05 14:02:00+05:30', false, 'issue #21: the store checks a handed-over delivery'
WHERE NOT EXISTS (SELECT 1 FROM notification.routing_versions WHERE rule_version = 7);

INSERT INTO notification.routing_rules
    (rule_version, event_type, recipient_role, scope_kind, push, when_fact, when_values, only_if_none_for,
     title_template, body_template)
SELECT 7, event_type, recipient_role, scope_kind, push, when_fact, when_values, only_if_none_for,
       title_template, body_template
  FROM notification.routing_rules
 WHERE rule_version = 6
ON CONFLICT (rule_version, event_type, recipient_role) DO NOTHING;

-- The handover is the store's cue: told at once, on the phone too.
UPDATE notification.routing_rules
   SET push = true,
       title_template = 'Delivery handed over',
       body_template = 'Check what arrived and send your report. The driver accepts it with your PIN.'
 WHERE rule_version = 7 AND event_type = 'delivery.completed' AND recipient_role = 'store_manager';

UPDATE notification.routing_versions SET is_current = false WHERE is_current AND rule_version <> 7;
UPDATE notification.routing_versions SET is_current = true WHERE rule_version = 7;

ALTER TABLE notification.routing_versions FORCE ROW LEVEL SECURITY;
ALTER TABLE notification.routing_rules FORCE ROW LEVEL SECURITY;
