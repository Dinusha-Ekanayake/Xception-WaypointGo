-- Photos on issues (Figma store manager "06d Damage photo" and "08b3 Report
-- issue: details").
--
--     A store manager photographs what is wrong with a delivery: while counting
--     it, or after unpacking. The photo is evidence for the dispatcher, kept in
--     the database every deployment already backs up, as proof of delivery is
--     (A-33). The bytes are apart from the rows, so a list never reads them.
--
--     An upload names the order it is about, and the receipt when it was taken
--     while counting. It is linked to an issue in a separate table, so the link
--     can be made whichever arrives first: an issue raised naming the photo, or
--     a photo of a receipt whose shortage investigation is already open. On a
--     phone that was offline, the photo and the report reach the server in
--     either order.
--
--     Retention (P-14, as proof): past retain_until the bytes are cleared and
--     purged_at set. The row, its size and its SHA-256 stay. Nothing is deleted.

CREATE TABLE issues.attachments (
    attachment_id uuid        PRIMARY KEY,
    outlet_id     text        NOT NULL REFERENCES ref.outlet_registry (outlet_id),
    depot_code    text        NOT NULL,
    order_id      uuid        NOT NULL,
    receipt_id    uuid,
    content_type  text        NOT NULL,
    size_bytes    integer     NOT NULL,
    sha256        text        NOT NULL,
    storage_key   text        NOT NULL UNIQUE,
    retain_until  date        NOT NULL,
    uploaded_by   uuid        NOT NULL,
    uploaded_at   timestamptz NOT NULL,
    purged_at     timestamptz,
    CONSTRAINT ck_attachments_type CHECK (content_type IN ('image/jpeg','image/png','image/webp')),
    CONSTRAINT ck_attachments_size CHECK (size_bytes > 0)
);

COMMENT ON TABLE issues.attachments IS
  'A photo of a delivery problem, uploaded by the store for an order. Linked to issues through issues.issue_attachments.';

CREATE INDEX ix_attachments_outlet  ON issues.attachments (outlet_id);
CREATE INDEX ix_attachments_order   ON issues.attachments (order_id);
CREATE INDEX ix_attachments_receipt ON issues.attachments (receipt_id) WHERE receipt_id IS NOT NULL;
CREATE INDEX ix_attachments_due     ON issues.attachments (retain_until) WHERE purged_at IS NULL;

CREATE TABLE issues.attachment_content (
    storage_key  text        PRIMARY KEY,
    content_type text        NOT NULL,
    size_bytes   integer     NOT NULL,
    content      bytea,
    stored_at    timestamptz NOT NULL,
    purged_at    timestamptz,
    CONSTRAINT ck_attachment_content_size CHECK (size_bytes > 0),
    CONSTRAINT ck_attachment_content_purge CHECK ((content IS NULL) = (purged_at IS NOT NULL))
);

COMMENT ON TABLE issues.attachment_content IS
  'The bytes of an issue photo, keyed as issues.attachments.storage_key. Cleared, never deleted, past retention.';

-- An issue names a photo by id. The photo may not have arrived yet, so there is
-- no foreign key to issues.attachments: a read joins the two and shows only
-- what has arrived.
CREATE TABLE issues.issue_attachments (
    issue_id      uuid        NOT NULL REFERENCES issues.issues (issue_id),
    attachment_id uuid        NOT NULL,
    linked_at     timestamptz NOT NULL,
    PRIMARY KEY (issue_id, attachment_id)
);

CREATE INDEX ix_issue_attachments_attachment ON issues.issue_attachments (attachment_id);

GRANT SELECT, INSERT, UPDATE ON issues.attachments, issues.attachment_content TO waypoint_issues;
GRANT SELECT, INSERT ON issues.issue_attachments TO waypoint_issues;

-- ---- row-level security -----------------------------------------------------
-- A photo is seen by its outlet's manager, its depot's staff and the process; it
-- is uploaded by the outlet's manager. Only the process clears one. The bytes
-- are the process's alone: an upload is authorized before they are written and
-- a read before they are served. A link follows its issue.

ALTER TABLE issues.attachments ENABLE ROW LEVEL SECURITY;
ALTER TABLE issues.attachments FORCE ROW LEVEL SECURITY;
CREATE POLICY attachments_read ON issues.attachments
    FOR SELECT TO waypoint_issues
    USING (app.actor_is_system() OR app.actor_has_outlet(outlet_id) OR app.actor_has_depot(depot_code));
CREATE POLICY attachments_upload ON issues.attachments
    FOR INSERT TO waypoint_issues
    WITH CHECK (app.actor_is_system() OR app.actor_has_outlet(outlet_id));
CREATE POLICY attachments_purge ON issues.attachments
    FOR UPDATE TO waypoint_issues
    USING (app.actor_is_system()) WITH CHECK (app.actor_is_system());

ALTER TABLE issues.attachment_content ENABLE ROW LEVEL SECURITY;
ALTER TABLE issues.attachment_content FORCE ROW LEVEL SECURITY;
CREATE POLICY attachment_content_process ON issues.attachment_content
    FOR ALL TO waypoint_issues
    USING (app.actor_is_system()) WITH CHECK (app.actor_is_system());

ALTER TABLE issues.issue_attachments ENABLE ROW LEVEL SECURITY;
ALTER TABLE issues.issue_attachments FORCE ROW LEVEL SECURITY;
CREATE POLICY issue_attachments_scope ON issues.issue_attachments
    FOR ALL TO waypoint_issues
    USING (EXISTS (SELECT 1 FROM issues.issues i WHERE i.issue_id = issue_attachments.issue_id))
    WITH CHECK (EXISTS (SELECT 1 FROM issues.issues i WHERE i.issue_id = issue_attachments.issue_id));

-- ---- the action ---------------------------------------------------------------

INSERT INTO iam.action_catalogue (action, module, description, implemented) VALUES
    ('issue:AttachPhoto', 'issues', 'Upload a photo of a delivery problem for an order', true)
ON CONFLICT (action) DO UPDATE SET implemented = true;

-- A policy version is immutable, so the grant is a new version built from the
-- current default. The store manager uploads; the dispatcher already holds
-- issue:*; the auditor's deny list grows with it.
CREATE OR REPLACE FUNCTION pg_temp.add_actions(doc jsonb, target_sid text, wanted text[]) RETURNS jsonb
LANGUAGE sql IMMUTABLE AS $$
    SELECT jsonb_set(
               doc, '{Statement}',
               (SELECT jsonb_agg(
                           CASE WHEN s ->> 'Sid' = target_sid
                                THEN jsonb_set(
                                         s, '{Action}',
                                         (s -> 'Action')
                                         || (SELECT coalesce(jsonb_agg(w), '[]'::jsonb)
                                               FROM unnest(wanted) AS w
                                              WHERE NOT jsonb_exists(s -> 'Action', w)))
                                ELSE s END
                           ORDER BY ord)
                  FROM jsonb_array_elements(doc -> 'Statement') WITH ORDINALITY AS t(s, ord)))
$$;

CREATE TEMP TABLE photo_policy_docs ON COMMIT DROP AS
SELECT p.policy_id,
       pg_temp.add_actions(pv.document, g.sid, g.actions) AS document,
       pv.document AS previous
FROM iam.policies p
JOIN iam.policy_versions pv ON pv.policy_id = p.policy_id AND pv.is_default
JOIN (VALUES
        ('WaypointStoreManager', 'ReportAndSync', ARRAY['issue:AttachPhoto']),
        ('WaypointAuditor',      'NeverWrite',    ARRAY['issue:AttachPhoto'])
     ) AS g(name, sid, actions) ON g.name = p.name;

DELETE FROM photo_policy_docs WHERE document = previous;

UPDATE iam.policy_versions pv
SET is_default = false
FROM photo_policy_docs d
WHERE pv.policy_id = d.policy_id AND pv.is_default;

INSERT INTO iam.policy_versions (policy_id, version_number, document, is_default)
SELECT d.policy_id,
       (SELECT max(version_number) + 1 FROM iam.policy_versions x WHERE x.policy_id = d.policy_id),
       d.document,
       true
FROM photo_policy_docs d;
