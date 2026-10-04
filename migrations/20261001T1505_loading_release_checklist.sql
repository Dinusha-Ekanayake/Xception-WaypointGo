-- Release now records the three confirmed checks in the command receipt.
-- Keep former readings on historical rows, but they no longer gate a release.
ALTER TABLE loading.sessions DROP CONSTRAINT ck_sessions_released;
ALTER TABLE loading.sessions ADD CONSTRAINT ck_sessions_released CHECK (
    (status = 'released') = (released_at IS NOT NULL)
    AND (status <> 'released' OR (released_by IS NOT NULL AND holder_user_id IS NULL))
);

COMMENT ON COLUMN loading.sessions.seal_number IS
  'Legacy release data; new releases use the doors sealed confirmation.';
COMMENT ON COLUMN loading.sessions.reefer_temp_c IS
  'Legacy release data; a temperature reading is no longer required.';
