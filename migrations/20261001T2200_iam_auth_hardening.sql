-- Identity and auth hardening (issue #5).
--
--     Sessions are stored by hash, failed sign-ins keep their history, every
--     policy change moves a generation number that each decision reads, and the
--     aggregates administration changes carry a version.
--     See docs/issues/005-identity-hardening/PLAN.md.
-- ---------------------------------------------------------------------

-- ---- sessions are stored by hash ---------------------------------------
--     The token is a bearer credential. Stored as it is, anyone who can read
--     the table (a backup, a replica, a mistaken grant) can use every live
--     session. A SHA-256 of 256 random bits cannot be reversed or guessed, so
--     no salt or slow hash is needed.

ALTER TABLE iam.sessions RENAME COLUMN session_token TO token_hash;

UPDATE iam.sessions
   SET token_hash = encode(sha256(convert_to(token_hash, 'UTF8')), 'hex');

ALTER TABLE iam.sessions
    ADD CONSTRAINT ck_session_token_hash CHECK (token_hash ~ '^[0-9a-f]{64}$');

COMMENT ON COLUMN iam.sessions.token_hash IS
  'Lowercase hex SHA-256 of the session token. The token itself is never stored.';

-- ---- failed sign-ins keep their history (SEC-05) ------------------------
--     A success used to delete the failures before it. It now marks them, so
--     "how often was this account attacked" stays answerable.

ALTER TABLE iam.login_attempts ADD COLUMN cleared_at timestamptz;

COMMENT ON COLUMN iam.login_attempts.cleared_at IS
  'Set on a failure when the same identity later signs in from the same address. '
  'A cleared failure no longer counts toward lockout; it is never deleted.';

CREATE INDEX ix_login_attempts_address_time
    ON iam.login_attempts (source_ip, attempted_at DESC)
    WHERE NOT succeeded;

-- ---- policy generation ---------------------------------------------------
--     One number, incremented in the same transaction as any change that can
--     alter a decision. Every replica compares it with the generation its
--     cached statements were loaded at, and every command reads it again
--     inside its own transaction (SEC-03, SEC-12).

CREATE TABLE iam.policy_generation (
    singleton  boolean PRIMARY KEY DEFAULT true,
    generation bigint      NOT NULL DEFAULT 1,
    changed_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_policy_generation_singleton CHECK (singleton)
);

INSERT INTO iam.policy_generation DEFAULT VALUES;

-- Moved, never added to or removed.
REVOKE INSERT, DELETE ON iam.policy_generation FROM waypoint_iam;

-- A command runs as its own module's role, which cannot read iam. The number
-- itself says nothing about who may do what, so it is readable by every role
-- through this function and nothing else in iam is.
CREATE OR REPLACE FUNCTION app.policy_generation() RETURNS bigint
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog AS $$
    SELECT generation FROM iam.policy_generation
$$;

COMMENT ON FUNCTION app.policy_generation() IS
  'The current policy generation. Read inside a command''s transaction to re-check its authorization.';

-- ---- versions on what administration changes -----------------------------

ALTER TABLE iam.policies ADD COLUMN row_version bigint NOT NULL DEFAULT 1;
ALTER TABLE iam.vehicle_driver_assignments ADD COLUMN row_version bigint NOT NULL DEFAULT 1;

ALTER TABLE iam.devices
    ADD COLUMN row_version   bigint NOT NULL DEFAULT 1,
    ADD COLUMN registered_by uuid REFERENCES iam.users(user_id),
    ADD COLUMN retired_at    timestamptz;

CREATE INDEX ix_devices_registered_by ON iam.devices (registered_by);

-- ---- driver scope is a vehicle on a date (R-IAM-13) ----------------------
--     The third scope predicate, beside app.actor_has_depot and
--     app.actor_has_outlet. A module's row policy calls it with the trip's
--     vehicle and service date, so yesterday's driver cannot post today.

CREATE OR REPLACE FUNCTION app.actor_drives(vehicle text, service_date date) RETURNS boolean
LANGUAGE sql STABLE AS $$
    SELECT EXISTS (
        SELECT 1 FROM iam.vehicle_driver_assignments
        WHERE driver_user_id = app.current_actor()
          AND vehicle_id = vehicle
          AND validity @> service_date
    )
$$;

COMMENT ON FUNCTION app.actor_drives(text, date) IS
  'True when the current actor is assigned to the vehicle on that date. Null actor means false.';

-- The function runs as the calling module's role, like the other two scope
-- predicates, so that role needs to read the table, and row-level security
-- narrows it to the actor's own assignments.
ALTER TABLE iam.vehicle_driver_assignments ENABLE ROW LEVEL SECURITY;
ALTER TABLE iam.vehicle_driver_assignments FORCE ROW LEVEL SECURITY;

CREATE POLICY p_driver_assignment_owner ON iam.vehicle_driver_assignments
    FOR ALL TO waypoint_iam USING (true) WITH CHECK (true);

DO $$
DECLARE
    m text;
    r text;
BEGIN
    FOREACH m IN ARRAY ARRAY['ordering','planning','loading','execution','receipt',
                             'issues','notification','sync','warehouse','ml'] LOOP
        r := 'waypoint_' || m;
        EXECUTE format('GRANT SELECT ON iam.vehicle_driver_assignments TO %I', r);
        EXECUTE format(
            'CREATE POLICY %I ON iam.vehicle_driver_assignments FOR SELECT TO %I'
            ' USING (driver_user_id = app.current_actor())',
            'p_driver_assignment_self_' || m, r);
    END LOOP;
END $$;

-- ---- the catalogue catches up ---------------------------------------------
-- These four have handlers now. The command bus refuses a kind whose action is
-- not marked implemented, so the flag is no longer only documentation.

UPDATE iam.action_catalogue SET implemented = true
 WHERE action IN ('iam:ChangeRole', 'iam:RegisterDevice', 'iam:RetireDevice', 'iam:DetachPolicy');
