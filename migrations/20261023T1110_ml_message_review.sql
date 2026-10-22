-- R-ML-37: a message read unsure, or from a voice note heard unclearly, is shown
-- to the dispatcher to confirm in one tap; what they confirm is what counts, and
-- is the label Jev is scored against. Rule 8: who and when are kept.

ALTER TABLE ml.message_situations ADD COLUMN IF NOT EXISTS unclear boolean NOT NULL DEFAULT false;
ALTER TABLE ml.message_situations ADD COLUMN IF NOT EXISTS reviewed_situation text;
ALTER TABLE ml.message_situations ADD COLUMN IF NOT EXISTS reviewed_by uuid;
ALTER TABLE ml.message_situations ADD COLUMN IF NOT EXISTS reviewed_at timestamptz;
ALTER TABLE ml.message_situations DROP CONSTRAINT IF EXISTS ck_message_situations_review;
ALTER TABLE ml.message_situations ADD CONSTRAINT ck_message_situations_review CHECK (
    (reviewed_at IS NULL) = (reviewed_by IS NULL) AND (reviewed_at IS NULL) = (reviewed_situation IS NULL)
    AND (reviewed_situation IS NULL OR (reviewed_situation ~ '^[A-Z_]+$' AND length(reviewed_situation) <= 32)));

INSERT INTO iam.action_catalogue (action, module, description, implemented) VALUES
    ('ml:ConfirmSignal', 'ml', 'Confirm what a message read unsure or unclearly reports, on the trip''s problem room', true)
ON CONFLICT (action) DO UPDATE SET implemented = true;

CREATE OR REPLACE FUNCTION pg_temp.grant_review(doc jsonb) RETURNS jsonb
LANGUAGE sql IMMUTABLE AS $$
    SELECT CASE
        WHEN EXISTS (SELECT 1 FROM jsonb_array_elements(doc -> 'Statement') s WHERE s ->> 'Sid' = 'ReviewSignals')
        THEN doc
        ELSE jsonb_set(doc, '{Statement}',
                       (doc -> 'Statement') || jsonb_build_array(jsonb_build_object(
                           'Sid', 'ReviewSignals',
                           'Effect', 'Allow',
                           'Action', jsonb_build_array('ml:ConfirmSignal'),
                           'Resource', jsonb_build_array('wpt:ml:trip:*'))))
    END
$$;

CREATE TEMP TABLE review_policy_docs ON COMMIT DROP AS
SELECT p.policy_id,
       pg_temp.grant_review(pv.document) AS document,
       pv.document AS previous
FROM iam.policies p
JOIN iam.policy_versions pv ON pv.policy_id = p.policy_id AND pv.is_default
WHERE p.name = 'WaypointDispatcher';

DELETE FROM review_policy_docs WHERE document = previous;

UPDATE iam.policy_versions pv
SET is_default = false
FROM review_policy_docs d
WHERE pv.policy_id = d.policy_id AND pv.is_default;

INSERT INTO iam.policy_versions (policy_id, version_number, document, is_default)
SELECT d.policy_id,
       (SELECT max(version_number) + 1 FROM iam.policy_versions x WHERE x.policy_id = d.policy_id),
       d.document,
       true
FROM review_policy_docs d;

UPDATE iam.policy_generation
SET generation = generation + 1, changed_at = now()
WHERE EXISTS (SELECT 1 FROM review_policy_docs);
