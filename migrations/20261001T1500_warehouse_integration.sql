-- The warehouse integration's own tables (issue #7). Schema and role came with
-- 20260930T1200.
--
--     Waypoint does not model stock (R-STK-04). It records what it asked the
--     warehouse, what the warehouse answered, and what it last saw, so a call
--     whose answer was lost can be found again instead of repeated (R-STK-11).
--     The warehouse API takes no idempotency key and stores no client
--     reference, so that record is the only memory of an attempt.
--
--     Nothing here is deleted; the role has no DELETE. Placements and inbound
--     events reach terminal states.

-- ---- catalogue ------------------------------------------------------------
-- A projection of GET /products. Never edited here; replaced by the next sync.
-- No stock columns: stock lives at the warehouse.

CREATE TABLE warehouse.products (
    product_id         text PRIMARY KEY,
    brand              text          NOT NULL,
    temperature        text,
    unit_weight_kg     numeric(12,4) NOT NULL,
    unit_volume_m3     numeric(12,6) NOT NULL,
    base_product_id    text,
    basis              text          NOT NULL,
    verified_real_sku  boolean       NOT NULL DEFAULT false,
    catalogue_version  text          NOT NULL,
    synced_at          timestamptz   NOT NULL,
    CONSTRAINT ck_products_temperature CHECK (temperature IN ('chilled','ambient')),
    CONSTRAINT ck_products_measures CHECK (unit_weight_kg >= 0 AND unit_volume_m3 >= 0)
);

COMMENT ON TABLE warehouse.products IS
  'Cached projection of the warehouse catalogue (CAT-01). Reconstructed, accurate to ~1%; display only, never capacity.';

CREATE INDEX ix_products_brand ON warehouse.products (brand, product_id);

-- One row per sync attempt, good or bad, so the age of the last good copy and
-- the reason for a failure are both on record (architecture rule 8).
CREATE TABLE warehouse.catalogue_syncs (
    sync_id            uuid PRIMARY KEY,
    started_at         timestamptz NOT NULL,
    finished_at        timestamptz NOT NULL,
    outcome            text        NOT NULL,
    catalogue_version  text,
    product_count      integer,
    error              text,
    CONSTRAINT ck_catalogue_syncs_outcome CHECK (outcome IN ('changed','unchanged','failed'))
);

CREATE INDEX ix_catalogue_syncs_good ON warehouse.catalogue_syncs (finished_at DESC)
    WHERE outcome <> 'failed';

-- ---- placements -----------------------------------------------------------
-- One per Waypoint order reference. state:
--   queued     never sent (circuit open); safe to send
--   attempting sent, answer not yet recorded
--   unknown    sent, answer lost (timeout, 5xx): look before sending again
--   placed     201, the warehouse holds a pending order
--   partial    202, a reservation awaiting the store's decision
--   rejected   409/404/422, nothing held
--   ambiguous  more than one warehouse order could be ours; a person decides
--   released   the warehouse order was cancelled, or nothing was ever created
--   merged     an amendment's new warehouse order, now carried by the order's
--              own placement
-- compensate_only: Waypoint no longer wants this placement (the order was
-- cancelled, or the amendment was refused), so a lost attempt is looked for and
-- cancelled if found, and never sent again.
-- attempts keeps every POST we sent, with its lines and time, for matching.

CREATE TABLE warehouse.placements (
    placement_id          uuid PRIMARY KEY,
    order_ref             text        NOT NULL UNIQUE,
    order_id              uuid        NOT NULL,
    depot_code            text        NOT NULL,
    warehouse_code        text        NOT NULL,
    lines                 jsonb       NOT NULL,
    attempts              jsonb       NOT NULL DEFAULT '[]'::jsonb,
    state                 text        NOT NULL,
    compensate_only       boolean     NOT NULL DEFAULT false,
    warehouse_order_ref   text        UNIQUE,
    warehouse_status      text,
    temperature           text,
    weight_kg             numeric(10,3),
    volume_m3             numeric(10,4),
    item_count            integer,
    expires_at            timestamptz,
    result                jsonb,
    last_error            text,
    retry_count           integer     NOT NULL DEFAULT 0,
    next_attempt_at       timestamptz,
    created_at            timestamptz NOT NULL,
    updated_at            timestamptz NOT NULL,
    row_version           bigint      NOT NULL DEFAULT 1,
    CONSTRAINT ck_placements_state CHECK (state IN (
        'queued','attempting','unknown','placed','partial','rejected','ambiguous','released',
        'merged')),
    CONSTRAINT ck_placements_warehouse CHECK (warehouse_code IN ('KDY','PLG')),
    CONSTRAINT ck_placements_ref_when_held CHECK (
        warehouse_order_ref IS NOT NULL OR state NOT IN ('placed','partial'))
);

COMMENT ON TABLE warehouse.placements IS
  'What Waypoint asked the warehouse and what it answered. The memory that makes a retry a lookup, not a second order (R-STK-11).';

CREATE INDEX ix_placements_order    ON warehouse.placements (order_id);
CREATE INDEX ix_placements_due      ON warehouse.placements (next_attempt_at)
    WHERE state IN ('queued','unknown','attempting');
CREATE INDEX ix_placements_open     ON warehouse.placements (updated_at)
    WHERE state IN ('placed','partial');

-- ---- outbound status calls ------------------------------------------------
-- Cancel, ship and deliver, driven by events. A row per (warehouse order, target)
-- so a redelivered event is a no-op and a failed call is retried until it lands
-- or is recorded as diverged, never looped (STK-10).

CREATE TABLE warehouse.status_requests (
    request_id           uuid PRIMARY KEY,
    order_id             uuid        NOT NULL,
    warehouse_order_ref  text        NOT NULL,
    target_status        text        NOT NULL,
    cause                text        NOT NULL,
    state                text        NOT NULL,
    attempts             integer     NOT NULL DEFAULT 0,
    next_attempt_at      timestamptz,
    last_error           text,
    created_at           timestamptz NOT NULL,
    updated_at           timestamptz NOT NULL,
    row_version          bigint      NOT NULL DEFAULT 1,
    CONSTRAINT uq_status_requests UNIQUE (warehouse_order_ref, target_status),
    CONSTRAINT ck_status_requests_target CHECK (target_status IN ('cancelled','shipped','delivered')),
    CONSTRAINT ck_status_requests_state CHECK (state IN ('pending','done','diverged','dead'))
);

CREATE INDEX ix_status_requests_due ON warehouse.status_requests (next_attempt_at)
    WHERE state = 'pending';

-- ---- discrepancies --------------------------------------------------------
-- Raised by the reconciler and by diverged status calls. Raise only: nothing is
-- corrected automatically except an orphan Waypoint never recorded (STK-08).

CREATE TABLE warehouse.discrepancies (
    discrepancy_id       uuid PRIMARY KEY,
    order_id             uuid,
    warehouse_order_ref  text,
    kind                 text        NOT NULL,
    waypoint_status      text,
    warehouse_status     text,
    detail               text        NOT NULL,
    raised_at            timestamptz NOT NULL,
    resolved_at          timestamptz,
    CONSTRAINT ck_discrepancies_kind CHECK (kind IN (
        'status_mismatch','cancelled_outside','orphan_released','ambiguous_match',
        'invalid_transition','unknown_reservation'))
);

-- One open discrepancy of a kind per warehouse order, so a reconciler run every
-- few minutes does not raise the same thing forever.
CREATE UNIQUE INDEX uq_discrepancies_open
    ON warehouse.discrepancies (warehouse_order_ref, kind) WHERE resolved_at IS NULL;
CREATE INDEX ix_discrepancies_order ON warehouse.discrepancies (order_id);

-- ---- inbound webhook inbox ------------------------------------------------
-- Land first, process later. MODULES.md places the inbox in the warehouse
-- schema; the design file 019 predates schema-per-module.

CREATE TABLE warehouse.inbound_events (
    inbound_event_id    uuid PRIMARY KEY,
    source_system       text        NOT NULL,
    source_event_id     text        NOT NULL,
    event_type          text        NOT NULL,
    payload             jsonb       NOT NULL,
    signature_verified  boolean     NOT NULL DEFAULT false,
    verification_error  text,
    received_at         timestamptz NOT NULL,
    status              text        NOT NULL DEFAULT 'received',
    attempts            integer     NOT NULL DEFAULT 0,
    next_attempt_at     timestamptz,
    processed_at        timestamptz,
    last_error          text,
    decided_by          uuid,
    decision_reason     text,
    CONSTRAINT uq_inbound_source_event UNIQUE (source_system, source_event_id),
    CONSTRAINT ck_inbound_status CHECK (status IN (
        'received','processing','processed','failed','quarantined','dead','discarded')),
    -- SEC-18: an unverified event can never reach a processing state.
    CONSTRAINT ck_inbound_unverified_is_quarantined CHECK (
        signature_verified OR status IN ('received','quarantined','dead','discarded'))
);

CREATE INDEX ix_inbound_due ON warehouse.inbound_events (next_attempt_at)
    WHERE status IN ('received','failed');
CREATE INDEX ix_inbound_quarantined ON warehouse.inbound_events (received_at)
    WHERE status = 'quarantined';

-- ---- privileges -----------------------------------------------------------

GRANT SELECT, INSERT, UPDATE ON warehouse.products, warehouse.catalogue_syncs,
                                warehouse.placements, warehouse.status_requests,
                                warehouse.discrepancies, warehouse.inbound_events
    TO waypoint_warehouse;

-- ---- actions --------------------------------------------------------------
-- implemented means "a handler or an endpoint enforces this action" (008).

UPDATE iam.action_catalogue SET implemented = true
 WHERE action IN ('warehouse:ReadCatalogue', 'warehouse:ReplayInbound',
                  'warehouse:DiscardInbound', 'warehouse:Reconcile');
