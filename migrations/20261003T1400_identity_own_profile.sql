-- Identity: a person keeps their own name and phone number up to date.
--
--     Until now only an administrator could change an account (iam:UpdateUser),
--     and an account had no phone number at all. A store manager now edits their
--     own display name and phone (R-IAM-32). The email is the sign-in name and the
--     password stays with the administrator; neither is part of this.
--
--     Expand only: the column is nullable and nothing is backfilled, because no
--     phone number is known for anyone yet.
-- ---------------------------------------------------------------------

ALTER TABLE iam.users ADD COLUMN phone text;

-- Stored normalised by the handler: an optional leading + and 7 to 15 digits.
-- NOT VALID then VALIDATE, so the check never holds a lock while it scans.
ALTER TABLE iam.users ADD CONSTRAINT ck_users_phone
    CHECK (phone IS NULL OR phone ~ '^\+?[0-9]{7,15}$') NOT VALID;
ALTER TABLE iam.users VALIDATE CONSTRAINT ck_users_phone;

COMMENT ON COLUMN iam.users.phone IS
  'The person''s own contact number, set by themselves (iam:UpdateOwnProfile). Personal data: never logged.';

-- ---- a new action needs a catalogue row (R-IAM-03) ---------------------

INSERT INTO iam.action_catalogue (action, module, description, implemented) VALUES
    ('iam:UpdateOwnProfile', 'identity', 'Change one''s own display name and phone number', true)
ON CONFLICT (action) DO UPDATE SET implemented = true;

-- The store manager may change their own profile; the auditor's deny list
-- already covers iam:*. A policy version is immutable, so the grant is a new
-- version built from the current default. Every pending migration runs in one
-- transaction, so the temporary names here must not repeat another file's.
CREATE OR REPLACE FUNCTION pg_temp.grant_own_profile(doc jsonb) RETURNS jsonb
LANGUAGE sql IMMUTABLE AS $$
    SELECT CASE
        WHEN EXISTS (SELECT 1 FROM jsonb_array_elements(doc -> 'Statement') s
                      WHERE s ->> 'Sid' = 'OwnProfile')
        THEN doc
        ELSE jsonb_set(doc, '{Statement}',
                       (doc -> 'Statement') || jsonb_build_array(jsonb_build_object(
                           'Sid', 'OwnProfile',
                           'Effect', 'Allow',
                           'Action', jsonb_build_array('iam:UpdateOwnProfile'),
                           'Resource', jsonb_build_array('wpt:iam:user:self'))))
    END
$$;

CREATE TEMP TABLE own_profile_policy_docs ON COMMIT DROP AS
SELECT p.policy_id,
       pg_temp.grant_own_profile(pv.document) AS document,
       pv.document AS previous
FROM iam.policies p
JOIN iam.policy_versions pv ON pv.policy_id = p.policy_id AND pv.is_default
WHERE p.name = 'WaypointStoreManager';

DELETE FROM own_profile_policy_docs WHERE document = previous;

UPDATE iam.policy_versions pv
SET is_default = false
FROM own_profile_policy_docs d
WHERE pv.policy_id = d.policy_id AND pv.is_default;

INSERT INTO iam.policy_versions (policy_id, version_number, document, is_default)
SELECT d.policy_id,
       (SELECT max(version_number) + 1 FROM iam.policy_versions x WHERE x.policy_id = d.policy_id),
       d.document,
       true
FROM own_profile_policy_docs d;
