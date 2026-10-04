-- 010 Product catalogue is a CACHED PROJECTION, not master data
--    It is reconstructed from order totals to within 1% on weight and
--    volume, and no row is a verified SKU.
--    THEREFORE: capacity constraints read ops.orders.order_weight_kg and
--    order_volume_m3. Never SUM(order_items). A 1% error on a 5,510 kg
--    truck is 55 kg of invisible overload.
--    Temperature comes from ops.orders.temperature_requirement, never
--    from ref.products.
-- ---------------------------------------------------------

ALTER TABLE ref.products
    ADD COLUMN IF NOT EXISTS source_system text NOT NULL DEFAULT 'warehouse_api',
    ADD COLUMN IF NOT EXISTS catalogue_version text,
    ADD COLUMN IF NOT EXISTS verified_real_sku boolean NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS inference_basis text,
    ADD COLUMN IF NOT EXISTS synced_at timestamptz;

COMMENT ON TABLE ref.products IS
    'Cached projection of the external warehouse catalogue. Never edited here. '
    'Descriptive only: capacity and temperature decisions read ops.orders.';
COMMENT ON TABLE ops.order_items IS
    'Descriptive order lines from the external catalogue. Never summed for capacity.';
