-- Roadmap AI-J1, R-ML-11: Jev in shadow. Each new issue with words of its own is
-- asked about once, outside any transaction, by a job; its guess is kept beside
-- the type the issue really has, so Jev is measured on our own data before it
-- may advise anyone. Nobody sees these rows on a screen. Never deleted.
CREATE TABLE ml.jev_shadow (
    issue_id       uuid        PRIMARY KEY,
    depot_code     text        NOT NULL,
    masked_text    text        NOT NULL,
    actual_type    text        NOT NULL,
    option_order   text[]      NOT NULL,
    status         text        NOT NULL DEFAULT 'pending',
    attempts       integer     NOT NULL DEFAULT 0,
    jev_choice     text,
    confidence     numeric(5,4),
    probabilities  jsonb,
    model          text,
    agrees         boolean,
    created_at     timestamptz NOT NULL,
    answered_at    timestamptz,
    CONSTRAINT ck_jev_shadow_status CHECK (status IN ('pending', 'answered', 'failed')),
    CONSTRAINT ck_jev_shadow_answer CHECK ((status = 'answered') = (jev_choice IS NOT NULL))
);

COMMENT ON TABLE ml.jev_shadow IS
  'Jev in shadow (roadmap AI-J1): its guess of each new issue''s type beside the real type. Nobody sees it.';

CREATE INDEX ix_jev_shadow_pending ON ml.jev_shadow (created_at) WHERE status = 'pending';

GRANT SELECT, INSERT, UPDATE ON ml.jev_shadow TO waypoint_ml;
ALTER TABLE ml.jev_shadow ENABLE ROW LEVEL SECURITY;
ALTER TABLE ml.jev_shadow FORCE ROW LEVEL SECURITY;
CREATE POLICY jev_shadow_system ON ml.jev_shadow
    FOR ALL TO waypoint_ml
    USING (app.actor_is_system()) WITH CHECK (app.actor_is_system());
