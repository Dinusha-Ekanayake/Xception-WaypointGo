-- The audit log says what was decided, about what, and in what state (issue #6;
-- SYSTEM-ARCHITECTURE 6.5).
--
--     Expand only. Every column is nullable, so nothing here rewrites a partition
--     or fails an insert from an instance that has not been upgraded yet: an old
--     instance writes the six columns it knows, a new one writes them all.
--
--     Adding a column to the partitioned parent adds it to every attached
--     partition. A partition detached by retention keeps the shape it had.

ALTER TABLE integration.audit_log
    ADD COLUMN IF NOT EXISTS command_id        uuid,
    ADD COLUMN IF NOT EXISTS target_type       text,
    ADD COLUMN IF NOT EXISTS target_id         text,
    -- before_state and after_state, not before and after: BEFORE is a keyword and
    -- an unquoted column of that name reads as a trigger clause in half the tools.
    ADD COLUMN IF NOT EXISTS before_state      jsonb,
    ADD COLUMN IF NOT EXISTS after_state       jsonb,
    -- The policy generation in force when the decision was made (iam.policy_generation).
    -- Every policy, attachment, role or scope change moves it, so two rows with the
    -- same value were decided under the same rules (POL-03).
    ADD COLUMN IF NOT EXISTS policy_generation bigint;

COMMENT ON COLUMN integration.audit_log.before_state IS
  'Redacted snapshot of the target before the command. Null when the handler did not capture one.';
COMMENT ON COLUMN integration.audit_log.after_state IS
  'Redacted snapshot of what the command returned. Personal fields are replaced, never stored.';

-- Indexes on the parent are created on every partition and on each one made later.
-- The log is small at this point, so the build lock is short; on a large log these
-- would be built per partition with CONCURRENTLY first.
CREATE INDEX IF NOT EXISTS ix_audit_target
    ON integration.audit_log (target_type, target_id, occurred_at DESC)
    WHERE target_type IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_audit_correlation
    ON integration.audit_log (correlation_id)
    WHERE correlation_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_audit_command
    ON integration.audit_log (command_id)
    WHERE command_id IS NOT NULL;
-- The auditor's default view: newest first, which is also the keyset order.
CREATE INDEX IF NOT EXISTS ix_audit_time
    ON integration.audit_log (occurred_at DESC, audit_id DESC);

-- audit:Read now has an endpoint behind it.
UPDATE iam.action_catalogue SET implemented = true WHERE action = 'audit:Read';
