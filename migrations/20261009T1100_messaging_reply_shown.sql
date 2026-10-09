-- Roadmap AI-J5: the ready replies a dispatcher was shown, in order, beside the
-- one they picked, so a ranker can learn which replies are taken over which are
-- passed by. Without the shown list only the picks are known, and a pick alone
-- cannot say what was turned down. `explored` marks the posts where the second
-- and third replies were swapped on purpose, to measure the pull of position.
--
-- Expand only: both nullable, so every earlier row stays as it was.
ALTER TABLE messaging.reply_uses ADD COLUMN IF NOT EXISTS shown text[];
ALTER TABLE messaging.reply_uses ADD COLUMN IF NOT EXISTS explored boolean;

ALTER TABLE messaging.reply_uses DROP CONSTRAINT IF EXISTS ck_reply_uses_shown;
ALTER TABLE messaging.reply_uses ADD CONSTRAINT ck_reply_uses_shown CHECK (
    shown IS NULL
    OR (cardinality(shown) BETWEEN 1 AND 10 AND shown[rank] = template_id));

CREATE INDEX IF NOT EXISTS ix_reply_uses_used_at ON messaging.reply_uses (used_at) WHERE shown IS NOT NULL;
