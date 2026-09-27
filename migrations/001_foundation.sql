-- 001 Foundation: extensions and the two schemas the foundation needs.
--     ops, ml and integration arrive with their own modules, not before.
-- ---------------------------------------------------------------------

CREATE EXTENSION IF NOT EXISTS pgcrypto;    -- gen_random_uuid
CREATE EXTENSION IF NOT EXISTS btree_gist;  -- required to mix = with && in an exclusion constraint

CREATE SCHEMA IF NOT EXISTS ref;
CREATE SCHEMA IF NOT EXISTS iam;

COMMENT ON SCHEMA ref IS
  'Shared master data. Read-mostly, versioned as immutable snapshots (decision D1).';
COMMENT ON SCHEMA iam IS
  'Identity and access. Who you are, what you may do, and where.';
