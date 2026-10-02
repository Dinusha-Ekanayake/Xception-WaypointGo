-- Proof artifacts kept in the database (decision 2026-10-01, issue #12).
--
--     The database is the one store every deployment already backs up, so the
--     evidence lives beside the records it supports instead of on one host's
--     disk (A-33). The bytes are apart from execution.attachments so a list of
--     attachments never reads them. Artifacts written to PROOF_DIR before this
--     stay readable there.
--
--     Retention (P-14): past an attachment's retain_until the bytes are cleared
--     and purged_at set. The row, its size and its SHA-256 stay, so it remains
--     on record that the proof existed and what it was. Nothing is deleted.
--
--     Read and written only by the process: an upload is authorized against
--     the delivery before the bytes are stored, and a read is authorized by its
--     signed link.

CREATE TABLE execution.proof_content (
    storage_key  text        PRIMARY KEY,
    content_type text        NOT NULL,
    size_bytes   integer     NOT NULL,
    content      bytea,
    stored_at    timestamptz NOT NULL,
    purged_at    timestamptz,
    CONSTRAINT ck_proof_content_size CHECK (size_bytes > 0),
    CONSTRAINT ck_proof_content_purge CHECK ((content IS NULL) = (purged_at IS NOT NULL))
);

COMMENT ON TABLE execution.proof_content IS
  'The bytes of a proof photo or signature, keyed as execution.attachments.storage_key. Cleared, never deleted, past retention.';

GRANT SELECT, INSERT, UPDATE ON execution.proof_content TO waypoint_execution;

ALTER TABLE execution.proof_content ENABLE ROW LEVEL SECURITY;
ALTER TABLE execution.proof_content FORCE ROW LEVEL SECURITY;
CREATE POLICY proof_content_process ON execution.proof_content
    FOR ALL TO waypoint_execution
    USING (app.actor_is_system()) WITH CHECK (app.actor_is_system());

ALTER TABLE execution.attachments ADD COLUMN purged_at timestamptz;

COMMENT ON COLUMN execution.attachments.purged_at IS
  'When the artifact''s bytes were cleared past retain_until (P-14). The row and its hash stay as the record that it existed.';
