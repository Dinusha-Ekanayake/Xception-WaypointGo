-- 005 Platform: the cross-cutting tables every module needs.
--
--     These land at baseline rather than with the module that first needs them,
--     because retrofitting idempotency, audit or an outbox means touching every
--     write path already written.
-- ---------------------------------------------------------------------

CREATE SCHEMA IF NOT EXISTS integration;

COMMENT ON SCHEMA integration IS
  'Infrastructure, not business state: idempotency, audit and reliable messaging.';

-- ---- idempotency ------------------------------------------------------
-- A retry after a lost response must return the original result, not a
-- duplicate-key error and not a second execution.

CREATE TABLE integration.command_receipts (
    command_id    uuid        NOT NULL,
    actor_id      uuid        NOT NULL,
    command_kind  text        NOT NULL,
    payload_hash  text        NOT NULL,
    result_status integer     NOT NULL,
    result_body   jsonb,
    created_at    timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (command_id, actor_id)
);

CREATE INDEX ix_command_receipts_created ON integration.command_receipts (created_at);

-- ---- audit ------------------------------------------------------------
-- Append only, written in the same transaction as the change it describes.
-- Denied attempts are recorded too: a 403 that leaves no trace is how an
-- access problem becomes invisible.

CREATE TABLE integration.audit_log (
    audit_id       bigint GENERATED ALWAYS AS IDENTITY,
    occurred_at    timestamptz NOT NULL DEFAULT now(),
    actor_id       uuid,
    device_id      uuid,
    action         text NOT NULL,
    resource       text,
    decision       text NOT NULL,
    reason         text,
    correlation_id text,
    PRIMARY KEY (audit_id, occurred_at),
    CONSTRAINT ck_audit_decision CHECK (decision IN ('ALLOW','DENY'))
) PARTITION BY RANGE (occurred_at);

-- The scheduler creates partitions ahead; these two keep a fresh database usable.
CREATE TABLE integration.audit_log_2026_09 PARTITION OF integration.audit_log
    FOR VALUES FROM ('2026-09-01') TO ('2026-10-01');
CREATE TABLE integration.audit_log_2026_10 PARTITION OF integration.audit_log
    FOR VALUES FROM ('2026-10-01') TO ('2026-11-01');

CREATE INDEX ix_audit_actor ON integration.audit_log (actor_id, occurred_at DESC);
CREATE INDEX ix_audit_action ON integration.audit_log (action, occurred_at DESC);

-- ---- reliable messaging -----------------------------------------------
-- The state change and the intent to publish commit together. A relay publishes
-- after commit, at least once, and consumers are idempotent by event_id.
-- Nothing publishes from this yet; the table exists so later modules do not
-- have to retrofit it.

CREATE TABLE integration.outbox_events (
    event_id        uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type  text NOT NULL,
    aggregate_id    text NOT NULL,
    event_type      text NOT NULL,
    payload         jsonb NOT NULL,
    occurred_at     timestamptz NOT NULL DEFAULT now(),
    status          text NOT NULL DEFAULT 'pending',
    attempts        integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    last_error      text,
    dead_lettered_at timestamptz,
    CONSTRAINT ck_outbox_status CHECK (status IN ('pending','processing','published','failed','dead'))
);

-- The relay claims work with FOR UPDATE SKIP LOCKED against this index;
-- without it several workers block on the first locked row and the relay
-- serialises to one effective instance.
CREATE INDEX ix_outbox_due ON integration.outbox_events (next_attempt_at)
    WHERE status IN ('pending','failed');

-- ---- module role ------------------------------------------------------

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'waypoint_integration') THEN
        CREATE ROLE waypoint_integration NOLOGIN;
    END IF;
END $$;

ALTER ROLE waypoint_integration NOINHERIT NOLOGIN NOBYPASSRLS NOSUPERUSER;
GRANT waypoint_integration TO waypoint_app;

GRANT USAGE ON SCHEMA integration TO waypoint_integration;
GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA integration TO waypoint_integration;
ALTER DEFAULT PRIVILEGES IN SCHEMA integration
    GRANT SELECT, INSERT, UPDATE ON TABLES TO waypoint_integration;

-- Audit is append only even for the module that owns it.
REVOKE UPDATE, DELETE ON integration.audit_log FROM waypoint_integration;

-- Every module writes audit and the outbox inside its own transaction, so each
-- needs insert rights there without gaining the rest of the schema.
GRANT USAGE ON SCHEMA integration TO waypoint_ref, waypoint_iam;
GRANT INSERT ON integration.audit_log, integration.outbox_events TO waypoint_ref, waypoint_iam;
GRANT SELECT, INSERT ON integration.command_receipts TO waypoint_ref, waypoint_iam;
