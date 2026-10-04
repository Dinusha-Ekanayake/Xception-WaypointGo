-- Offline PIN switching on a shared loader device (decision 2026-10-01, R-IAM-27).
-- The device downloads a PBKDF2 verifier per crew member, never the PIN, and
-- checks a PIN against it while offline. Each verifier is written when the PIN
-- is set or entered online; members without one can only switch online.
-- Switches made offline are replayed into the operator history on reconnect
-- and marked so the audit trail shows the server did not check the PIN.

ALTER TABLE iam.users ADD COLUMN pin_offline_verifier text;

ALTER TABLE iam.session_operators ADD COLUMN offline boolean NOT NULL DEFAULT false;
