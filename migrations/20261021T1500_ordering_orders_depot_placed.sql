-- Issue #7: the order-history export pages one depot's orders by placement time,
-- keyset on (placed_at, order_id), for retraining the demand forecast.

CREATE INDEX IF NOT EXISTS ix_orders_depot_placed ON ordering.orders (depot_code, placed_at, order_id);
