-- Decision suggestions PR 5 (docs/issues/decision-suggestions/PLAN.md): Jev in
-- shadow on the words of a trip-chat report. A report whose writer picked its type
-- is the labelled set: Jev reads only the words and its guess of the situation is
-- scored against the type picked, before it is ever asked about a report sent as
-- "other" or by voice. Same table as the issue shadow; issue_id holds the message
-- id for a report.
ALTER TABLE ml.jev_shadow ADD COLUMN IF NOT EXISTS source text NOT NULL DEFAULT 'issue';
ALTER TABLE ml.jev_shadow DROP CONSTRAINT IF EXISTS ck_jev_shadow_source;
ALTER TABLE ml.jev_shadow ADD CONSTRAINT ck_jev_shadow_source CHECK (source IN ('issue', 'report'));
COMMENT ON COLUMN ml.jev_shadow.source IS 'issue: issue_id is an issue; report: issue_id is a trip-chat report message';
