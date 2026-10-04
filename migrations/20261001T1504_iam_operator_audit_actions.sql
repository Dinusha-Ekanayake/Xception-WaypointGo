-- The account scope change and password reset revoke operator device sessions.
-- Keep that close reason explicit in the historical operator interval.
ALTER TABLE iam.session_operators DROP CONSTRAINT ck_session_operators_reason;
ALTER TABLE iam.session_operators ADD CONSTRAINT ck_session_operators_reason
    CHECK (end_reason IN ('lock','switch','sign_out','expired','revoked'));

-- These direct identity endpoints enforce the signed-in loader role and the
-- PIN policy themselves; SetPin is only available to the trusted host command.
INSERT INTO iam.action_catalogue (action, module, description, implemented) VALUES
    ('iam:SwitchOperator', 'identity', 'Switch a shared loader device to a PIN-verified operator', true),
    ('iam:EndOperator', 'identity', 'Lock or end the active operator interval on a shared device', true),
    ('iam:SetPin', 'identity', 'Provision or change a loader PIN from a trusted host command', true)
ON CONFLICT (action) DO UPDATE SET implemented = true;
