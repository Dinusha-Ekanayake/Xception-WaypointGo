-- Receipt's tables (issue #13). Schema and role came with 20260930T1200.
--
--     A receipt is the store's acceptance of one delivered order, a separate
--     record from the driver's proof in Execution (R-RCP-04). It is created from
--     delivery.completed, so it always refers to a real delivery (RCP-04): a
--     store can answer a receipt, never invent one.
--
--     Lines are per product (D-E). The expected quantity is the order's
--     descriptive line; capacity was decided on order totals and never here.
--
--     The auto-close deadline is stamped on each receipt from the parameter in
--     force when it opened (P-10), so changing the window never moves a deadline
--     already given. Nothing is deleted; the role has no DELETE.
--
--     Orders, deliveries and trips belong to other modules: plain ids, no
--     foreign key (D-B). Outlets point into the reference registry.

CREATE TABLE receipt.confirmations (
    receipt_id    uuid PRIMARY KEY,
    order_id      uuid        NOT NULL UNIQUE,
    delivery_id   uuid        NOT NULL UNIQUE,
    trip_id       uuid,
    outlet_id     text        NOT NULL REFERENCES ref.outlet_registry (outlet_id),
    depot_code    text        NOT NULL,
    status        text        NOT NULL,
    note          text,
    confirmed_by  uuid,
    confirmed_at  timestamptz,
    delivered_at  timestamptz NOT NULL,
    closes_at     timestamptz NOT NULL,
    late          boolean     NOT NULL DEFAULT false,
    -- What the driver recorded, kept as the receipt saw it, for the custody chain (R-RCP-08).
    delivered_by     uuid,
    delivered_units  integer,
    row_version   bigint      NOT NULL DEFAULT 1,
    created_at    timestamptz NOT NULL,
    updated_at    timestamptz NOT NULL,
    CONSTRAINT ck_confirmations_status CHECK (status IN ('pending','confirmed','partial','disputed','auto_closed')),
    -- Answered by a person, or by nobody: auto-close records silence, never a confirmer (R-RCP-05).
    CONSTRAINT ck_confirmations_answered CHECK (
        (status IN ('confirmed','partial','disputed')) = (confirmed_by IS NOT NULL AND confirmed_at IS NOT NULL)),
    CONSTRAINT ck_confirmations_dispute_reason CHECK (status <> 'disputed' OR note IS NOT NULL),
    CONSTRAINT ck_confirmations_window CHECK (closes_at > delivered_at),
    CONSTRAINT ck_confirmations_late CHECK (NOT late OR status IN ('partial','disputed')),
    CONSTRAINT ck_confirmations_units CHECK (delivered_units IS NULL OR delivered_units >= 0)
);

COMMENT ON TABLE receipt.confirmations IS
  'The store''s acceptance of one delivered order, separate from the driver''s proof (R-RCP-04).';
COMMENT ON COLUMN receipt.confirmations.closes_at IS
  'When silence becomes auto_closed (R-RCP-05), stamped from P-10 when the receipt opened.';
COMMENT ON COLUMN receipt.confirmations.late IS
  'The store answered after auto-close; still accepted and investigated (RCP-08).';

CREATE INDEX ix_confirmations_outlet_pending ON receipt.confirmations (outlet_id, delivered_at)
    WHERE status = 'pending';
CREATE INDEX ix_confirmations_due ON receipt.confirmations (closes_at) WHERE status = 'pending';
CREATE INDEX ix_confirmations_depot ON receipt.confirmations (depot_code, delivered_at);
CREATE INDEX ix_confirmations_trip ON receipt.confirmations (trip_id) WHERE trip_id IS NOT NULL;

CREATE TABLE receipt.confirmation_lines (
    receipt_id        uuid    NOT NULL REFERENCES receipt.confirmations (receipt_id),
    product_id        text    NOT NULL,
    expected_quantity integer NOT NULL,
    received_quantity integer,
    PRIMARY KEY (receipt_id, product_id),
    CONSTRAINT ck_confirmation_lines_expected CHECK (expected_quantity > 0),
    CONSTRAINT ck_confirmation_lines_received CHECK (received_quantity IS NULL OR received_quantity >= 0)
);

COMMENT ON TABLE receipt.confirmation_lines IS
  'Per product: what the order said would arrive and what the store says did (D-E).';

CREATE TABLE receipt.confirmation_history (
    history_id  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    receipt_id  uuid        NOT NULL REFERENCES receipt.confirmations (receipt_id),
    from_status text,
    to_status   text        NOT NULL,
    reason      text        NOT NULL,
    actor_id    uuid,
    event_id    uuid,
    occurred_at timestamptz NOT NULL
);

COMMENT ON TABLE receipt.confirmation_history IS
  'Every status change with its actor, reason and time (rule 8). The system actor closes; a person answers.';

CREATE INDEX ix_confirmation_history_receipt ON receipt.confirmation_history (receipt_id, history_id);

-- ---- parameters -------------------------------------------------------------
-- Effective-dated, as planning.rule_parameters: a changed window is a new row,
-- never an edit, and a receipt already open keeps the deadline it was given.

CREATE TABLE receipt.parameters (
    parameter_key   text          NOT NULL,
    parameter_value numeric(12,4) NOT NULL,
    unit            text          NOT NULL,
    effective_from  date          NOT NULL,
    effective_to    date,
    note            text          NOT NULL,
    PRIMARY KEY (parameter_key, effective_from),
    CONSTRAINT ck_receipt_parameters_range CHECK (effective_to IS NULL OR effective_to > effective_from),
    CONSTRAINT ex_receipt_parameters_overlap EXCLUDE USING gist (
        parameter_key WITH =, daterange(effective_from, effective_to, '[)') WITH &&)
);

INSERT INTO receipt.parameters (parameter_key, parameter_value, unit, effective_from, note) VALUES
    ('auto_close.hours', 24, 'hours', DATE '2026-01-01', 'P-10, settled in issue #13');

-- ---- privileges -------------------------------------------------------------

GRANT SELECT, INSERT, UPDATE ON receipt.confirmations, receipt.confirmation_lines,
                                receipt.confirmation_history TO waypoint_receipt;
GRANT SELECT ON receipt.parameters TO waypoint_receipt;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA receipt TO waypoint_receipt;
-- A parameter changes by a new row from a migration, never at runtime.
REVOKE INSERT, UPDATE ON receipt.parameters FROM waypoint_receipt;

-- ---- row-level security -----------------------------------------------------
-- A store manager sees their outlets, a dispatcher their depots, the process
-- everything in this schema (RCP-05). FORCE holds the owner to it as well.

ALTER TABLE receipt.confirmations ENABLE ROW LEVEL SECURITY;
ALTER TABLE receipt.confirmations FORCE ROW LEVEL SECURITY;
CREATE POLICY confirmations_scope ON receipt.confirmations
    FOR ALL TO waypoint_receipt
    USING (app.actor_is_system() OR app.actor_has_outlet(outlet_id) OR app.actor_has_depot(depot_code))
    WITH CHECK (app.actor_is_system() OR app.actor_has_outlet(outlet_id) OR app.actor_has_depot(depot_code));

ALTER TABLE receipt.confirmation_lines ENABLE ROW LEVEL SECURITY;
ALTER TABLE receipt.confirmation_lines FORCE ROW LEVEL SECURITY;
CREATE POLICY confirmation_lines_scope ON receipt.confirmation_lines
    FOR ALL TO waypoint_receipt
    USING (EXISTS (SELECT 1 FROM receipt.confirmations c WHERE c.receipt_id = confirmation_lines.receipt_id))
    WITH CHECK (EXISTS (SELECT 1 FROM receipt.confirmations c WHERE c.receipt_id = confirmation_lines.receipt_id));

ALTER TABLE receipt.confirmation_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE receipt.confirmation_history FORCE ROW LEVEL SECURITY;
CREATE POLICY confirmation_history_scope ON receipt.confirmation_history
    FOR ALL TO waypoint_receipt
    USING (EXISTS (SELECT 1 FROM receipt.confirmations c WHERE c.receipt_id = confirmation_history.receipt_id))
    WITH CHECK (EXISTS (SELECT 1 FROM receipt.confirmations c WHERE c.receipt_id = confirmation_history.receipt_id));

ALTER TABLE receipt.parameters ENABLE ROW LEVEL SECURITY;
ALTER TABLE receipt.parameters FORCE ROW LEVEL SECURITY;
CREATE POLICY parameters_read ON receipt.parameters FOR SELECT TO waypoint_receipt USING (true);
