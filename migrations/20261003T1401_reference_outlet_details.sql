-- Reference: what a store says about itself.
--
--     The outlet's delivery window and dock type come from the published reference
--     version. A store manager may now change them for their own outlet, directly,
--     and keep the store's contact details beside them (R-REF-01). The change is
--     laid on top of the current version when a snapshot is loaded, so the next
--     plan uses it, and a reference import cannot quietly discard it; the
--     published version itself is never rewritten. This is the calendar override's
--     shape (009): a decision in its own table, read over the supplied data.
--
--     A mall bay is the building's, not the store's: the store can neither choose
--     one nor leave one, so a mall outlet's dock never changes here (R-PLN-29).
--
--     Scope: the reference role cannot read the identity scope tables (D-B), so
--     the handler checks the outlet against the actor's scope through Identity's
--     contract before it writes, as ReferenceScope does for reads (R-IAM-28).
-- ---------------------------------------------------------------------

CREATE TABLE ref.outlet_details (
    outlet_id       text PRIMARY KEY REFERENCES ref.outlet_registry(outlet_id),
    window_open     time,
    window_close    time,
    dock_type       text,
    contact_name    text,
    contact_phone   text,
    receiving_notes text,
    row_version     bigint      NOT NULL DEFAULT 1,
    updated_by      uuid,
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_outlet_details_window_pair CHECK ((window_open IS NULL) = (window_close IS NULL)),
    CONSTRAINT ck_outlet_details_window_order CHECK (window_open IS NULL OR window_open < window_close),
    CONSTRAINT ck_outlet_details_dock CHECK (dock_type IS NULL OR dock_type IN ('rear_dock', 'street')),
    CONSTRAINT ck_outlet_details_phone CHECK (contact_phone IS NULL OR contact_phone ~ '^\+?[0-9]{7,15}$'),
    CONSTRAINT ck_outlet_details_name CHECK (contact_name IS NULL OR length(contact_name) <= 80),
    CONSTRAINT ck_outlet_details_notes CHECK (receiving_notes IS NULL OR length(receiving_notes) <= 300)
);

COMMENT ON TABLE ref.outlet_details IS
  'A store''s own delivery window, dock type and contacts, set by its manager. Read on top of the '
  'current reference version when a snapshot is loaded, so it survives an import (R-REF-01).';
COMMENT ON COLUMN ref.outlet_details.contact_phone IS
  'Personal data when it is a person''s number: shown to the outlet''s own users, never logged.';

-- ---- a new action needs a catalogue row (R-IAM-03) ---------------------

INSERT INTO iam.action_catalogue (action, module, description, implemented) VALUES
    ('reference:UpdateOutletDetails', 'referencedata',
     'Change one''s own outlet''s delivery window, dock type and contacts', true)
ON CONFLICT (action) DO UPDATE SET implemented = true;

-- The store manager gains it beside its other outlet work; the auditor's deny
-- list grows with it. A policy version is immutable, so each grant is a new
-- version built from the current default. Every pending migration runs in one
-- transaction, so the temporary names here must not repeat another file's.
CREATE OR REPLACE FUNCTION pg_temp.grant_outlet_details(doc jsonb, policy_name text) RETURNS jsonb
LANGUAGE sql IMMUTABLE AS $$
    SELECT CASE
        WHEN policy_name = 'WaypointAuditor' THEN
            jsonb_set(
                doc, '{Statement}',
                (SELECT jsonb_agg(
                            CASE WHEN s ->> 'Sid' = 'NeverWrite'
                                      AND NOT jsonb_exists(s -> 'Action', 'reference:UpdateOutletDetails')
                                 THEN jsonb_set(s, '{Action}', (s -> 'Action') || '["reference:UpdateOutletDetails"]'::jsonb)
                                 ELSE s END
                            ORDER BY ord)
                   FROM jsonb_array_elements(doc -> 'Statement') WITH ORDINALITY AS t(s, ord)))
        WHEN EXISTS (SELECT 1 FROM jsonb_array_elements(doc -> 'Statement') s
                      WHERE s ->> 'Sid' = 'OwnOutletDetails')
        THEN doc
        ELSE jsonb_set(doc, '{Statement}',
                       (doc -> 'Statement') || jsonb_build_array(jsonb_build_object(
                           'Sid', 'OwnOutletDetails',
                           'Effect', 'Allow',
                           'Action', jsonb_build_array('reference:UpdateOutletDetails'),
                           'Resource', jsonb_build_array('wpt:ref:outlet:*'))))
    END
$$;

CREATE TEMP TABLE outlet_details_policy_docs ON COMMIT DROP AS
SELECT p.policy_id,
       pg_temp.grant_outlet_details(pv.document, p.name) AS document,
       pv.document AS previous
FROM iam.policies p
JOIN iam.policy_versions pv ON pv.policy_id = p.policy_id AND pv.is_default
WHERE p.name IN ('WaypointStoreManager', 'WaypointAuditor');

DELETE FROM outlet_details_policy_docs WHERE document = previous;

UPDATE iam.policy_versions pv
SET is_default = false
FROM outlet_details_policy_docs d
WHERE pv.policy_id = d.policy_id AND pv.is_default;

INSERT INTO iam.policy_versions (policy_id, version_number, document, is_default)
SELECT d.policy_id,
       (SELECT max(version_number) + 1 FROM iam.policy_versions x WHERE x.policy_id = d.policy_id),
       d.document,
       true
FROM outlet_details_policy_docs d;
