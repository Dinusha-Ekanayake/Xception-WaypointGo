-- Roadmap F-12: the payload redaction looks for position batches older than the
-- retention window that still carry their points. Once redacted a row leaves
-- this partial index, so it stays the size of the unredacted backlog.
CREATE INDEX IF NOT EXISTS ix_operations_position_payloads
    ON sync.operations (received_at)
    WHERE kind = 'delivery:RecordPositions' AND command #> '{payload,points}' IS NOT NULL;
