-- Shared dock devices: a supervisor signs the device in, loaders switch to
-- themselves with a PIN (decision 2026-10-01, Figma 08 Loader Phone 00, 00b,
-- 07, 07b, E1, E2).
--
--     The device session belongs to the account that signed in with email and
--     password, which decides the role. On a loader device that is the
--     supervisor. The loader working at that moment is the session's operator,
--     and the operator is the actor on every write, so the custody chain names
--     the person who touched the goods (R-RCP-08), not the supervisor.
--
--     Lock and Switch user end the operator; the device stays signed in. The
--     supervisor signs the device out at the end of the day.
--
--     A PIN is a second factor on an already signed-in device, never a way in
--     on its own: there is no PIN-only sign in. Stored as Argon2id like a
--     password, throttled per user across replicas.
--
--     Every operator interval is kept, so a write queued offline and replayed
--     later can be checked against who was actually signed in when it was
--     recorded (R-IAM-18).

ALTER TABLE iam.users ADD COLUMN employee_code text;
ALTER TABLE iam.users ADD COLUMN pin_hash text;
ALTER TABLE iam.users ADD CONSTRAINT uq_users_employee_code UNIQUE (employee_code);
ALTER TABLE iam.users ADD CONSTRAINT ck_users_employee_code
    CHECK (employee_code IS NULL OR employee_code ~ '^[A-Z]{3}-[0-9]{5}$');

COMMENT ON COLUMN iam.users.employee_code IS
  'The dock badge, for example LDR-00038. Shown on the crew list and next to every check.';
COMMENT ON COLUMN iam.users.pin_hash IS
  'Argon2id of the operator PIN. Null means the person cannot be switched to on a shared device.';

ALTER TABLE iam.sessions ADD COLUMN operator_user_id uuid REFERENCES iam.users (user_id);
ALTER TABLE iam.sessions ADD COLUMN operator_since timestamptz;
ALTER TABLE iam.sessions ADD CONSTRAINT ck_sessions_operator
    CHECK ((operator_user_id IS NULL) = (operator_since IS NULL));
CREATE INDEX ix_sessions_operator ON iam.sessions (operator_user_id) WHERE operator_user_id IS NOT NULL;

CREATE TABLE iam.session_operators (
    interval_id   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    session_token text        NOT NULL REFERENCES iam.sessions (session_token) ON DELETE CASCADE,
    user_id       uuid        NOT NULL REFERENCES iam.users (user_id),
    started_at    timestamptz NOT NULL,
    ended_at      timestamptz,
    end_reason    text,
    CONSTRAINT ck_session_operators_end CHECK ((ended_at IS NULL) = (end_reason IS NULL)),
    CONSTRAINT ck_session_operators_order CHECK (ended_at IS NULL OR ended_at >= started_at),
    CONSTRAINT ck_session_operators_reason CHECK (end_reason IN ('lock','switch','sign_out','expired'))
);

COMMENT ON TABLE iam.session_operators IS
  'Who operated a shared device and when. A queued write is accepted for the operator whose interval covers its recorded time.';

CREATE UNIQUE INDEX ux_session_operators_open ON iam.session_operators (session_token) WHERE ended_at IS NULL;
CREATE INDEX ix_session_operators_user ON iam.session_operators (user_id, started_at);

CREATE TABLE iam.pin_attempts (
    user_id      uuid        NOT NULL REFERENCES iam.users (user_id),
    attempted_at timestamptz NOT NULL,
    succeeded    boolean     NOT NULL,
    session_token_hash text
);

COMMENT ON TABLE iam.pin_attempts IS
  'Shared across replicas, or a second replica is a bypass. Five failures pause PIN entry for that person.';

CREATE INDEX ix_pin_attempts_user_time ON iam.pin_attempts (user_id, attempted_at DESC);

GRANT SELECT, INSERT, UPDATE ON iam.session_operators, iam.pin_attempts TO waypoint_iam;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA iam TO waypoint_iam;
