-- 016 Row level security
--     Scope becomes a database contract instead of a WHERE clause every
--     developer must remember.
--
--     THREE WAYS TO GET THIS WRONG:
--       a) plain SET instead of SET LOCAL leaks the actor to the next
--          request that borrows the pooled connection
--       b) the runtime role holding BYPASSRLS makes every policy a no-op
--       c) the TABLE OWNER bypasses RLS by default, which is why
--          FORCE ROW LEVEL SECURITY is used below
--
--     The application sets, inside each transaction:
--         SET LOCAL app.actor_id = '<uuid>';
-- ---------------------------------------------------------

-- Roles and grants are migration 018. This migration is policies only.

CREATE OR REPLACE FUNCTION iam.current_actor() RETURNS uuid AS $$
    SELECT NULLIF(current_setting('app.actor_id', true), '')::uuid;
$$ LANGUAGE sql STABLE;

-- Actor unset returns NULL, every comparison yields NULL, no rows match.
-- The failure mode is closed, which is the one we want.

ALTER TABLE ops.orders               ENABLE ROW LEVEL SECURITY;
ALTER TABLE ops.orders               FORCE  ROW LEVEL SECURITY;
ALTER TABLE ops.delivery_records     ENABLE ROW LEVEL SECURITY;
ALTER TABLE ops.delivery_records     FORCE  ROW LEVEL SECURITY;
ALTER TABLE ops.receipt_confirmations ENABLE ROW LEVEL SECURITY;
ALTER TABLE ops.receipt_confirmations FORCE  ROW LEVEL SECURITY;

DROP POLICY IF EXISTS p_orders_scope ON ops.orders;
CREATE POLICY p_orders_scope ON ops.orders FOR ALL USING (
       EXISTS (SELECT 1 FROM iam.user_roles ur
                WHERE ur.user_id = iam.current_actor() AND ur.role_code = 'admin')
    OR EXISTS (SELECT 1 FROM iam.user_outlet_access uoa
                WHERE uoa.user_id = iam.current_actor() AND uoa.outlet_id = ops.orders.outlet_id)
    OR EXISTS (SELECT 1 FROM iam.user_depot_access uda
                 JOIN ref.outlets o ON o.depot_id = uda.depot_id
                WHERE uda.user_id = iam.current_actor() AND o.outlet_id = ops.orders.outlet_id)
);

DROP POLICY IF EXISTS p_delivery_scope ON ops.delivery_records;
CREATE POLICY p_delivery_scope ON ops.delivery_records FOR ALL USING (
       EXISTS (SELECT 1 FROM iam.user_roles ur
                WHERE ur.user_id = iam.current_actor() AND ur.role_code IN ('admin','dispatcher'))
    OR driver_user_id = iam.current_actor()
    OR EXISTS (SELECT 1 FROM ops.order_allocations a
                 JOIN ops.orders o ON o.order_id = a.order_id
                 JOIN iam.user_outlet_access uoa ON uoa.outlet_id = o.outlet_id
                WHERE a.allocation_id = ops.delivery_records.allocation_id
                  AND uoa.user_id = iam.current_actor())
);

DROP POLICY IF EXISTS p_receipt_scope ON ops.receipt_confirmations;
CREATE POLICY p_receipt_scope ON ops.receipt_confirmations FOR ALL USING (
       EXISTS (SELECT 1 FROM iam.user_roles ur
                WHERE ur.user_id = iam.current_actor() AND ur.role_code IN ('admin','dispatcher'))
    OR EXISTS (SELECT 1 FROM ops.orders o
                 JOIN iam.user_outlet_access uoa ON uoa.outlet_id = o.outlet_id
                WHERE o.order_id = ops.receipt_confirmations.order_id
                  AND uoa.user_id = iam.current_actor())
);
