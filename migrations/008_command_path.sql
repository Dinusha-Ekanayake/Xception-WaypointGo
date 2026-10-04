-- 008 The command path becomes real.
--
--     Until now the command bus had no handlers and no endpoint: it was wired,
--     unit tested and had never run a command. POST /api/commands and the first
--     two handlers land with this migration, so two things need correcting.
-- ---------------------------------------------------------------------

-- ---- what the catalogue claims ---------------------------------------
-- iam.action_catalogue.implemented means "a handler or an endpoint enforces
-- this action". It was optimistic in 006. An action marked implemented that
-- nothing enforces is worse than one marked false, because an administrator
-- grants it and believes something is now guarded.

-- Policy administration is enforced by identity/web/PolicyAdminController.
UPDATE iam.action_catalogue SET implemented = true
 WHERE action IN ('iam:CreatePolicy', 'iam:AttachPolicy', 'iam:ReadPolicy');

-- Reference has no web layer yet, so nothing checks reference:Read. It returns
-- to true with the read endpoints, not before.
UPDATE iam.action_catalogue SET implemented = false
 WHERE action = 'reference:Read';

-- ---- the audit log stops accepting writes on 1 November 2026 ----------
--     Every command commits its audit row in the same transaction as the change
--     it describes, which is the point. It also means a missing partition does
--     not lose an audit row, it fails the command: on 2026-11-01 the whole
--     system would have stopped writing.
--
--     005 said "the scheduler creates partitions ahead". There is no scheduler.
--     Until there is, the range is extended by hand and far enough out that the
--     next extension is not urgent. No DEFAULT partition: attaching a real
--     partition later would then need a full scan of it under an exclusive lock.

CREATE TABLE IF NOT EXISTS integration.audit_log_2026_11 PARTITION OF integration.audit_log
    FOR VALUES FROM ('2026-11-01') TO ('2026-12-01');
CREATE TABLE IF NOT EXISTS integration.audit_log_2026_12 PARTITION OF integration.audit_log
    FOR VALUES FROM ('2026-12-01') TO ('2027-01-01');
CREATE TABLE IF NOT EXISTS integration.audit_log_2027_01 PARTITION OF integration.audit_log
    FOR VALUES FROM ('2027-01-01') TO ('2027-02-01');
CREATE TABLE IF NOT EXISTS integration.audit_log_2027_02 PARTITION OF integration.audit_log
    FOR VALUES FROM ('2027-02-01') TO ('2027-03-01');
CREATE TABLE IF NOT EXISTS integration.audit_log_2027_03 PARTITION OF integration.audit_log
    FOR VALUES FROM ('2027-03-01') TO ('2027-04-01');
CREATE TABLE IF NOT EXISTS integration.audit_log_2027_04 PARTITION OF integration.audit_log
    FOR VALUES FROM ('2027-04-01') TO ('2027-05-01');
CREATE TABLE IF NOT EXISTS integration.audit_log_2027_05 PARTITION OF integration.audit_log
    FOR VALUES FROM ('2027-05-01') TO ('2027-06-01');
CREATE TABLE IF NOT EXISTS integration.audit_log_2027_06 PARTITION OF integration.audit_log
    FOR VALUES FROM ('2027-06-01') TO ('2027-07-01');

-- ---- append only means append only on the partitions too --------------
--     005 revoked UPDATE and DELETE on integration.audit_log, but a partition is
--     its own table with its own privileges, and the schema-wide grant reached
--     each one. The parent being append only is decorative while a partition can
--     be written to directly.

DO $$
DECLARE part regclass;
BEGIN
    FOR part IN
        SELECT inhrelid::regclass
          FROM pg_inherits
         WHERE inhparent = 'integration.audit_log'::regclass
    LOOP
        EXECUTE format('REVOKE UPDATE, DELETE ON %s FROM waypoint_integration', part);
    END LOOP;
END $$;

-- Future partitions inherit from the schema default, which grants UPDATE. Narrow
-- the default so a partition created later is append only without remembering to
-- revoke it.
ALTER DEFAULT PRIVILEGES IN SCHEMA integration
    REVOKE UPDATE ON TABLES FROM waypoint_integration;
ALTER DEFAULT PRIVILEGES IN SCHEMA integration
    GRANT SELECT, INSERT ON TABLES TO waypoint_integration;

-- The outbox relay does need UPDATE: it marks an event published or failed.
GRANT UPDATE ON integration.outbox_events TO waypoint_integration;
