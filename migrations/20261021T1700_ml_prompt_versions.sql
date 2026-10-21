-- Every language model call and every Jev shadow answer names the version of the
-- instructions it was given (R-ML-27): a short hash of the instruction text, so a
-- changed prompt is a new version without anyone remembering to bump one, and
-- the AI health screen compares versions. Nullable: rows from before are unknown.
ALTER TABLE ml.ai_calls ADD COLUMN IF NOT EXISTS prompt_version text;
ALTER TABLE ml.jev_shadow ADD COLUMN IF NOT EXISTS prompt_version text;

-- The AI health screen shows every depot's masked inputs, so it is its own action,
-- not ml:Read, which dispatchers hold. The administrator's policy grants ml:*.
INSERT INTO iam.action_catalogue (action, module, description, implemented) VALUES
    ('ml:ReadAiHealth', 'intelligence', 'Read where language models and Jev fail, every depot', true)
ON CONFLICT (action) DO UPDATE SET implemented = true;
