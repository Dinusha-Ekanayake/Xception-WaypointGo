-- 002 Reference data versioning
--    A published plan must keep the master-data snapshot it was built
--    against, so re-reading history never silently changes a past decision.
-- ---------------------------------------------------------

CREATE TABLE IF NOT EXISTS ref.reference_versions (
    reference_version_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    source_label text NOT NULL,
    content_hash text NOT NULL,
    imported_at timestamptz NOT NULL DEFAULT now(),
    imported_by uuid REFERENCES iam.users(user_id),
    is_current boolean NOT NULL DEFAULT false,
    UNIQUE (content_hash)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_one_current_reference_version
    ON ref.reference_versions(is_current) WHERE is_current;
