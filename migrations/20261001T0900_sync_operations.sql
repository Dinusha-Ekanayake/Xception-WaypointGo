-- Sync: the server record of every operation a device queued while offline
-- (issue #28, MODULES.md section 10).
--
-- One row per submitted operation, keyed by the command id the device minted,
-- so a replayed batch finds its earlier outcome instead of running again. The
-- command itself still goes through the command bus and its receipt; this
-- table records what the device was told, in the order it asked.
--
-- Rows are never deleted. An operation ends APPLIED, CONFLICT, REJECTED or
-- DISCARDED, and a conflict keeps the version it was based on (rule 6).

CREATE TABLE IF NOT EXISTS sync.operations (
    operation_id       uuid        PRIMARY KEY,
    actor_id           uuid        NOT NULL REFERENCES iam.users (user_id),
    device_id          uuid        NOT NULL,
    sequence           bigint      NOT NULL,
    kind               text        NOT NULL,
    command            jsonb       NOT NULL,
    status             text        NOT NULL DEFAULT 'RECEIVED'
        CHECK (status IN ('RECEIVED', 'APPLIED', 'CONFLICT', 'REJECTED', 'DISCARDED')),
    problem_code       text,
    problem_detail     text,
    base_row_version   bigint,
    client_recorded_at timestamptz,
    received_at        timestamptz NOT NULL DEFAULT now(),
    applied_at         timestamptz,
    acknowledged_at    timestamptz,
    row_version        bigint      NOT NULL DEFAULT 1
);

CREATE INDEX IF NOT EXISTS operations_actor_idx
    ON sync.operations (actor_id, received_at, operation_id);
CREATE INDEX IF NOT EXISTS operations_device_idx
    ON sync.operations (device_id, sequence);

GRANT SELECT, INSERT, UPDATE ON sync.operations TO waypoint_sync;

-- A device sees its own account's operations and nobody else's. Who may review
-- another person's conflict is decision D-O, still open; until it is settled
-- nothing reads across accounts.
ALTER TABLE sync.operations ENABLE ROW LEVEL SECURITY;
ALTER TABLE sync.operations FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS p_operations_own ON sync.operations;
CREATE POLICY p_operations_own ON sync.operations
    TO waypoint_sync
    USING (actor_id = app.current_actor())
    WITH CHECK (actor_id = app.current_actor());

UPDATE iam.action_catalogue SET implemented = true
    WHERE action IN ('sync:Submit', 'sync:Acknowledge', 'sync:Read');
