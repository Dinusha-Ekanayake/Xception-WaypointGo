-- A hold lapses after 30 minutes with no activity from its holder, so another
-- loader can take the trip over (decision 2026-10-01, R-LOD-11). The holder's
-- last accepted command is kept beside the hold; existing holds start from
-- when they were taken.

ALTER TABLE loading.sessions ADD COLUMN holder_active_at timestamptz;

UPDATE loading.sessions SET holder_active_at = held_since WHERE held_since IS NOT NULL;

ALTER TABLE loading.sessions ADD CONSTRAINT ck_sessions_holder_active
    CHECK ((holder_user_id IS NULL) = (holder_active_at IS NULL));
