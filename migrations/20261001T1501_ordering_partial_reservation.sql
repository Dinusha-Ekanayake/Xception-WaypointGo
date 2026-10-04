-- A partial warehouse answer is kept for the store to decide (issue #7; D-F
-- revised 2026-10-01).
--
--     The warehouse answers a short order with 202: what is available is locked
--     as a "reserved" warehouse order until expires_at. Waypoint keeps it as
--     partially_reserved, with the locked quantities' totals, and the store
--     either accepts it (order:AcceptShortfall, the warehouse confirm call) or
--     cancels the order. Left alone the lock expires and the order is cancelled.
--     A partially reserved order is not demand: Planning reads only confirmed
--     and deferred orders.

ALTER TABLE ordering.orders DROP CONSTRAINT ck_orders_status;
ALTER TABLE ordering.orders ADD CONSTRAINT ck_orders_status CHECK (status IN (
    'stock_unknown','partially_reserved','confirmed','allocated','deferred','unservable',
    'loading','in_transit','delivered','partially_delivered','failed','received',
    'unconfirmed','cancelled'));

ALTER TABLE ordering.orders ADD COLUMN reservation_expires_at timestamptz;
ALTER TABLE ordering.orders ADD CONSTRAINT ck_orders_partial_expires CHECK (
    status <> 'partially_reserved' OR reservation_expires_at IS NOT NULL);

COMMENT ON COLUMN ordering.orders.reservation_expires_at IS
  'When the warehouse releases a partial reservation the store has not accepted.';

-- ---- action ---------------------------------------------------------------

INSERT INTO iam.action_catalogue (action, module, description, implemented) VALUES
    ('order:AcceptShortfall', 'ordering', 'Accept a partial warehouse reservation', true)
ON CONFLICT (action) DO UPDATE SET implemented = true;

-- A policy version is immutable, so the grant is a new version for the store
-- manager and the auditor's deny list grows with it. Built from the current
-- default rather than restated, so a grant another branch added survives.
CREATE TEMP TABLE policy_v_next (name text PRIMARY KEY, sid text NOT NULL) ON COMMIT DROP;
INSERT INTO policy_v_next VALUES
    ('WaypointStoreManager', 'OrderAndReceive'),
    ('WaypointAuditor', 'NeverWrite');

CREATE TEMP TABLE policy_docs ON COMMIT DROP AS
SELECT p.policy_id,
       jsonb_set(
           pv.document, '{Statement}',
           (SELECT jsonb_agg(
                       CASE WHEN s ->> 'Sid' = n.sid
                                 AND NOT (s -> 'Action') ? 'order:AcceptShortfall'
                            THEN jsonb_set(s, '{Action}', (s -> 'Action') || '"order:AcceptShortfall"'::jsonb)
                            ELSE s END
                       ORDER BY ord)
              FROM jsonb_array_elements(pv.document -> 'Statement') WITH ORDINALITY AS t(s, ord))) AS document,
       pv.document AS previous
FROM iam.policies p
JOIN policy_v_next n ON n.name = p.name
JOIN iam.policy_versions pv ON pv.policy_id = p.policy_id AND pv.is_default;

DELETE FROM policy_docs WHERE document = previous;

UPDATE iam.policy_versions pv
SET is_default = false
FROM policy_docs d
WHERE pv.policy_id = d.policy_id AND pv.is_default;

INSERT INTO iam.policy_versions (policy_id, version_number, document, is_default)
SELECT d.policy_id,
       (SELECT max(version_number) + 1 FROM iam.policy_versions x WHERE x.policy_id = d.policy_id),
       d.document,
       true
FROM policy_docs d;
