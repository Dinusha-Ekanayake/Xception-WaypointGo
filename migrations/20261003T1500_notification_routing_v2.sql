-- Notification routing version 2 (issue #118). A version is never edited
-- (R-NOT-09): version 2 is version 1 plus two rules, and becomes current.
--
--   R-NOT-10: a released trip tells the depot's other loaders that it left.
--             The loader who released it is the actor and is not told (R-NOT-07).
--   R-NOT-11: a released trip tells each outlet on it its stop number and
--             expected arrival, so the store can schedule staff (R-RCP-02).
--             NotificationConsumers.OnTripReleased adds one outlet target per
--             stop with the facts stopNumber and plannedArrival.

-- Both tables force row-level security and the migrator runs with row
-- security off, so the force is lifted for these writes and put back after
-- (AGENTS.md, Data and Migration Rules). Nothing reads them as their owner.
ALTER TABLE notification.routing_versions NO FORCE ROW LEVEL SECURITY;
ALTER TABLE notification.routing_rules NO FORCE ROW LEVEL SECURITY;

INSERT INTO notification.routing_versions (rule_version, published_at, is_current, note)
SELECT 2, TIMESTAMPTZ '2026-10-03 21:00:00+05:30', false, 'issue #118: loaders hear a release; outlets hear their expected arrival'
WHERE NOT EXISTS (SELECT 1 FROM notification.routing_versions WHERE rule_version = 2);

INSERT INTO notification.routing_rules
    (rule_version, event_type, recipient_role, scope_kind, push, when_fact, when_values, only_if_none_for,
     title_template, body_template)
SELECT 2, event_type, recipient_role, scope_kind, push, when_fact, when_values, only_if_none_for,
       title_template, body_template
  FROM notification.routing_rules
 WHERE rule_version = 1
ON CONFLICT (rule_version, event_type, recipient_role) DO NOTHING;

INSERT INTO notification.routing_rules
    (rule_version, event_type, recipient_role, scope_kind, push, when_fact, when_values, only_if_none_for,
     title_template, body_template)
VALUES
    (2, 'trip.released', 'loader', 'depot', true, NULL, NULL, NULL,
     'Trip released · {vehicleId}',
     '{vehicleId} left for {serviceDate} with {stopCount} stops.'),
    (2, 'trip.released', 'store_manager', 'outlet', true, NULL, NULL, NULL,
     '{vehicleId} is on the way',
     -- Dollar-quoted: a doubled quote beside {...} confuses the JDBC escape scan.
     $$You're stop {stopNumber} of {stopCount}. Expected {plannedArrival}.$$)
ON CONFLICT (rule_version, event_type, recipient_role) DO NOTHING;

UPDATE notification.routing_versions SET is_current = false WHERE is_current AND rule_version <> 2;
UPDATE notification.routing_versions SET is_current = true WHERE rule_version = 2;

ALTER TABLE notification.routing_versions FORCE ROW LEVEL SECURITY;
ALTER TABLE notification.routing_rules FORCE ROW LEVEL SECURITY;
