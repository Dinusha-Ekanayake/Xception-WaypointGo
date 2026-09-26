-- 009 External warehouse: the whole anti-corruption footprint
--    Waypoint does not model stock. It records what the warehouse said.
-- ---------------------------------------------------------

ALTER TABLE ops.orders
    ADD COLUMN IF NOT EXISTS stock_status text NOT NULL DEFAULT 'not_checked',
    ADD COLUMN IF NOT EXISTS stock_reservation_ref text,
    ADD COLUMN IF NOT EXISTS stock_checked_at timestamptz,
    ADD COLUMN IF NOT EXISTS deferral_count integer NOT NULL DEFAULT 0;

DO $$ BEGIN
    ALTER TABLE ops.orders ADD CONSTRAINT ck_order_stock_status CHECK (stock_status IN (
        'not_checked','reserved','insufficient','adjusted','released','stock_unknown'
    ));
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
    ALTER TABLE ops.orders ADD CONSTRAINT ck_order_deferral_count CHECK (deferral_count >= 0);
EXCEPTION WHEN duplicate_object THEN NULL; END $$;
