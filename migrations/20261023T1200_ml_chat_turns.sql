-- Decision suggestions part 3, chat understood (R-ML-38 of this plan, docs/issues/decision-suggestions/PLAN.md):
-- Jev reads every typed or spoken chat message on a trip, the dispatcher's too,
-- into what it does (a question, a yes, a no, "we are open now", a problem, off
-- topic...), so the suggested replies follow the conversation. The dispatcher's
-- words never become a problem on the room; only drivers' and stores' do.

ALTER TABLE ml.message_situations DROP CONSTRAINT IF EXISTS message_situations_author_role_check;
ALTER TABLE ml.message_situations ADD CONSTRAINT message_situations_author_role_check
    CHECK (author_role IN ('driver', 'store_manager', 'loader', 'dispatcher'));
ALTER TABLE ml.message_situations ADD COLUMN IF NOT EXISTS thread_id uuid;
ALTER TABLE ml.message_situations ADD COLUMN IF NOT EXISTS reached text[] NOT NULL DEFAULT '{}';
COMMENT ON COLUMN ml.message_situations.reached IS 'The stores the message reached, so their open chats hear what it was read as';
