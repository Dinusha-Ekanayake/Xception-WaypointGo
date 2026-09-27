-- 009 Identity commands, and a calendar override that is a recorded decision.
--
--     Account administration existed only as host commands. It is now eight
--     command handlers, so an administrator changes an account over HTTP with a
--     receipt, a version guard and an audit row that commit with the change.
-- ---------------------------------------------------------------------

-- ---- a new action needs a catalogue row -------------------------------
-- R-IAM-03. Resetting a password was never catalogued, so a policy naming it
-- would have been rejected at authoring time, which is the intended failure.
-- WaypointAdministrator already allows iam:*, so no policy version changes.

INSERT INTO iam.action_catalogue (action, module, description, implemented) VALUES
    ('iam:ResetPassword', 'identity', 'Set a new password and revoke every session', true)
ON CONFLICT (action) DO UPDATE SET implemented = true;

UPDATE iam.action_catalogue SET implemented = true
 WHERE action IN ('iam:CreateUser', 'iam:UpdateUser', 'iam:DisableUser',
                  'iam:GrantScope', 'iam:RevokeScope', 'iam:AssignDriver',
                  'calendar:Override', 'reference:Read');

-- ---- calendar overrides ----------------------------------------------
--     R-CAL-03 generates days beyond the supplied range so planning does not go
--     silent. This is the other half: a person declaring that a specific day does
--     or does not operate, whatever the data says.
--
--     Its own table rather than columns on ref.calendar_days, for two reasons.
--     An override is a decision and carries an actor, a reason and a timestamp,
--     which a derived calendar row has nowhere to put. And ref.calendar_days is
--     rewritten by every reference import, which would silently discard the
--     override the first time the supplied CSV changed.

CREATE TABLE ref.calendar_overrides (
    calendar_date date PRIMARY KEY,
    is_operating  boolean NOT NULL,
    reason        text    NOT NULL,
    set_by        uuid,
    set_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_override_reason CHECK (length(btrim(reason)) >= 3)
);

COMMENT ON TABLE ref.calendar_overrides IS
  'A person overruling the calendar for one day. Read on top of ref.calendar_days '
  'when a snapshot is loaded, so it survives a reference import.';

COMMENT ON COLUMN ref.calendar_overrides.reason IS
  'Required. An override with no reason is indistinguishable from a mistake six months later.';
