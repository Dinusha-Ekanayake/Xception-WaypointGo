-- Roadmap FF-07: a person's profile photo, so the people who work with them
-- that day recognise them (a store sees who is handing over its goods). The
-- administrator uploads it with the person's written consent on record and
-- removes it on request (policy P-PD-1).
--
-- One current photo per person. Removing it deletes the image itself, as the
-- person asked, and keeps who removed it, when and why; a replaced photo is
-- removed the same way. Nothing else is ever deleted.

CREATE TABLE iam.profile_photos (
    photo_id       uuid        PRIMARY KEY,
    user_id        uuid        NOT NULL REFERENCES iam.users (user_id),
    content        bytea,
    content_type   text        NOT NULL CHECK (content_type IN ('image/webp', 'image/jpeg', 'image/png')),
    sha256         text        NOT NULL CHECK (length(sha256) = 64),
    size_bytes     integer     NOT NULL CHECK (size_bytes > 0 AND size_bytes <= 262144),
    consent_note   text        NOT NULL CHECK (length(btrim(consent_note)) BETWEEN 3 AND 300),
    uploaded_by    uuid        NOT NULL,
    uploaded_at    timestamptz NOT NULL,
    removed_at     timestamptz,
    removed_by     uuid,
    removal_reason text,
    row_version    bigint      NOT NULL DEFAULT 1,
    CONSTRAINT ck_profile_photo_removed CHECK (
        (removed_at IS NULL) = (content IS NOT NULL)
        AND (removed_at IS NULL) = (removed_by IS NULL)
        AND (removed_at IS NULL) = (removal_reason IS NULL))
);

COMMENT ON TABLE iam.profile_photos IS
  'Profile photos (FF-07). One current per person; a removed one keeps its record and loses its image.';

CREATE UNIQUE INDEX ux_profile_photos_current ON iam.profile_photos (user_id) WHERE removed_at IS NULL;
CREATE INDEX ix_profile_photos_user ON iam.profile_photos (user_id);

GRANT SELECT, INSERT, UPDATE ON iam.profile_photos TO waypoint_iam;

-- A new action needs a catalogue row and a handler (R-IAM). The administrator
-- holds every action; nobody else uploads or removes a photo.
INSERT INTO iam.action_catalogue (action, module, description, implemented) VALUES
    ('iam:SetProfilePhoto', 'iam', 'Upload a person''s profile photo, with their written consent on record', true),
    ('iam:RemoveProfilePhoto', 'iam', 'Remove a person''s profile photo, deleting the image, with a reason', true)
ON CONFLICT (action) DO UPDATE SET implemented = true;

-- Seeing a photo: every field role may ask; whether this viewer may see this
-- person (themselves, a colleague at the same depot, or the store their driver
-- is delivering to today) is decided in code, and a refusal is audited.
INSERT INTO iam.action_catalogue (action, module, description, implemented) VALUES
    ('iam:ReadProfilePhoto', 'iam', 'See the profile photo of someone you work with today', true)
ON CONFLICT (action) DO UPDATE SET implemented = true;

-- A policy version is immutable: each field role's policy gets a new default
-- version with one more statement, written once even if this runs again.
CREATE OR REPLACE FUNCTION pg_temp.grant_profile_photo(doc jsonb) RETURNS jsonb
LANGUAGE sql IMMUTABLE AS $$
    SELECT CASE
        WHEN EXISTS (SELECT 1 FROM jsonb_array_elements(doc -> 'Statement') s
                      WHERE s ->> 'Sid' = 'ProfilePhotos')
        THEN doc
        ELSE jsonb_set(doc, '{Statement}',
                       (doc -> 'Statement') || jsonb_build_array(jsonb_build_object(
                           'Sid', 'ProfilePhotos',
                           'Effect', 'Allow',
                           'Action', jsonb_build_array('iam:ReadProfilePhoto'),
                           'Resource', jsonb_build_array('wpt:iam:user:*'))))
    END
$$;

CREATE TEMP TABLE profile_photo_policy_docs ON COMMIT DROP AS
SELECT p.policy_id,
       pg_temp.grant_profile_photo(pv.document) AS document,
       pv.document AS previous
FROM iam.policies p
JOIN iam.policy_versions pv ON pv.policy_id = p.policy_id AND pv.is_default
WHERE p.name IN ('WaypointDispatcher', 'WaypointDriver', 'WaypointLoader', 'WaypointStoreManager');

DELETE FROM profile_photo_policy_docs WHERE document = previous;

UPDATE iam.policy_versions pv
SET is_default = false
FROM profile_photo_policy_docs d
WHERE pv.policy_id = d.policy_id AND pv.is_default;

INSERT INTO iam.policy_versions (policy_id, version_number, document, is_default)
SELECT d.policy_id,
       (SELECT max(version_number) + 1 FROM iam.policy_versions x WHERE x.policy_id = d.policy_id),
       d.document,
       true
FROM profile_photo_policy_docs d;

-- A policy change clears every cached decision on the next request.
UPDATE iam.policy_generation
SET generation = generation + 1, changed_at = now()
WHERE EXISTS (SELECT 1 FROM profile_photo_policy_docs);
