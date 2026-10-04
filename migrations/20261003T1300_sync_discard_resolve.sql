-- Sync: a person reviews a write the server held, by discarding it or by
-- resolving it with a replacement on the current version (issue #28).
--
-- Decision D-O (2026-10-02): only the operation's owner reviews it. That is
-- what p_operations_own already enforces, so no policy on another account's
-- rows is added here; the role policies only gain the two actions.
--
--   DISCARDED  the person dropped it, with a reason.
--   RESOLVED   the person redid it on the current version; replaced_by names the
--              operation that carries the redo. One command, one transaction:
--              the redo is its own operation, applied or held like any other.

DO $$
DECLARE
    status_check text;
BEGIN
    SELECT con.conname INTO status_check
      FROM pg_constraint con
      JOIN pg_class rel ON rel.oid = con.conrelid
      JOIN pg_namespace ns ON ns.oid = rel.relnamespace
     WHERE ns.nspname = 'sync' AND rel.relname = 'operations' AND con.contype = 'c'
       AND pg_get_constraintdef(con.oid) LIKE '%status%';
    IF status_check IS NOT NULL THEN
        EXECUTE format('ALTER TABLE sync.operations DROP CONSTRAINT %I', status_check);
    END IF;
END $$;

ALTER TABLE sync.operations
    ADD CONSTRAINT operations_status_check
    CHECK (status IN ('RECEIVED', 'APPLIED', 'CONFLICT', 'REJECTED', 'DISCARDED', 'RESOLVED'));

ALTER TABLE sync.operations ADD COLUMN IF NOT EXISTS replaced_by    uuid;
ALTER TABLE sync.operations ADD COLUMN IF NOT EXISTS settled_reason text;
ALTER TABLE sync.operations ADD COLUMN IF NOT EXISTS settled_at     timestamptz;

CREATE INDEX IF NOT EXISTS operations_replaced_by_idx
    ON sync.operations (replaced_by) WHERE replaced_by IS NOT NULL;

UPDATE iam.action_catalogue SET implemented = true
 WHERE action IN ('sync:Discard', 'sync:Resolve');

-- ---- the three offline roles review their own held writes --------------
-- A policy version is immutable, so each change is a new version and the
-- default moves to it. Built from the role's current default, so grants other
-- branches added survive; a role that already allows both actions is left alone.

CREATE TEMP TABLE review_docs ON COMMIT DROP AS
SELECT p.policy_id,
       jsonb_set(
           pv.document, '{Statement}',
           (pv.document -> 'Statement')
           || jsonb_build_array(
                  jsonb_build_object(
                      'Sid', 'ReviewOwnHeldWrites',
                      'Effect', 'Allow',
                      'Action', jsonb_build_array('sync:Discard', 'sync:Resolve'),
                      'Resource', jsonb_build_array('*')))) AS document
FROM iam.policies p
JOIN iam.policy_versions pv ON pv.policy_id = p.policy_id AND pv.is_default
WHERE p.name IN ('WaypointLoader', 'WaypointDriver', 'WaypointStoreManager')
  AND NOT EXISTS (
      SELECT 1 FROM jsonb_array_elements(pv.document -> 'Statement') s
       WHERE s ->> 'Sid' = 'ReviewOwnHeldWrites');

UPDATE iam.policy_versions pv
SET is_default = false
FROM review_docs d
WHERE pv.policy_id = d.policy_id AND pv.is_default;

INSERT INTO iam.policy_versions (policy_id, version_number, document, is_default)
SELECT d.policy_id,
       (SELECT max(version_number) + 1 FROM iam.policy_versions x WHERE x.policy_id = d.policy_id),
       d.document,
       true
FROM review_docs d;
