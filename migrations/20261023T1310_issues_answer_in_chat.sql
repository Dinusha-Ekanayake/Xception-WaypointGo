-- R-ML-45: a store's answer may be what it wrote in the trip's chat, recorded by
-- the dispatcher with the store's words as the reason, beside the app and the phone.
ALTER TABLE issues.decision_requests DROP CONSTRAINT IF EXISTS ck_decision_answer;
ALTER TABLE issues.decision_requests ADD CONSTRAINT ck_decision_answer CHECK (
    (answer IS NULL) = (state IN ('sent', 'expired', 'withdrawn'))
    AND (answer IS NULL OR answer IN ('yes', 'no'))
    AND (answer IS NULL) = (answered_by IS NULL)
    AND (answer IS NULL) = (answered_at IS NULL)
    AND (answer IS NULL) = (answered_via IS NULL)
    AND (answered_via IS NULL OR answered_via IN ('app', 'phone', 'chat')));
