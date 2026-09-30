-- 006 Order lifecycle: legal transitions as data
--    CHECK constraints already restrict VALUES. They do not restrict
--    TRANSITIONS, so 'delivered' -> 'confirmed' is currently accepted.
--    The domain enforces this graph; the table documents it.
-- ---------------------------------------------------------

CREATE TABLE IF NOT EXISTS ops.order_status_transitions (
    from_status text NOT NULL,
    to_status text NOT NULL,
    requires_reason boolean NOT NULL DEFAULT false,
    PRIMARY KEY (from_status, to_status)
);

INSERT INTO ops.order_status_transitions (from_status, to_status, requires_reason) VALUES
    ('draft','confirmed',false),
    ('draft','cancelled',true),
    ('confirmed','queued',false),
    ('confirmed','cancelled',true),
    ('queued','allocated',false),
    ('queued','deferred',true),
    ('queued','not_run',true),
    ('allocated','loading',false),
    ('allocated','deferred',true),
    ('loading','dispatched',false),
    ('loading','deferred',true),
    ('dispatched','delivered',false),
    ('dispatched','partially_delivered',true),
    ('dispatched','failed',true),
    ('delivered','received',false),
    ('partially_delivered','received',true),
    ('failed','queued',true),
    ('deferred','queued',false),
    ('deferred','cancelled',true)
ON CONFLICT DO NOTHING;
