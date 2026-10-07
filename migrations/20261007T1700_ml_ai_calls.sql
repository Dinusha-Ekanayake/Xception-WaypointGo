-- Roadmap AI-0, R-ML-10: every call to a language model through the gateway is
-- recorded: who asked, for what, which provider and pinned model, the masked
-- facts that were sent (never the unmasked ones), the answer, and how it ended.
-- A used answer is kept and served again for the same key, so a plan version
-- is worded once. Rows are never deleted.
CREATE TABLE ml.ai_calls (
    call_id      uuid        PRIMARY KEY,
    purpose      text        NOT NULL,
    cache_key    text        NOT NULL,
    provider     text        NOT NULL,
    model        text        NOT NULL,
    sent_facts   jsonb       NOT NULL,
    answer       text,
    outcome      text        NOT NULL,
    asked_by     uuid        NOT NULL,
    asked_at     timestamptz NOT NULL,
    millis       integer     NOT NULL,
    CONSTRAINT ck_ai_calls_outcome CHECK (outcome IN ('used', 'unfaithful', 'failed', 'capped')),
    CONSTRAINT ck_ai_calls_answer CHECK ((outcome = 'used') = (answer IS NOT NULL))
);

COMMENT ON TABLE ml.ai_calls IS
  'Every language model call through the gateway (roadmap AI-0): masked facts sent, answer, outcome. Never deleted.';

CREATE INDEX ix_ai_calls_day ON ml.ai_calls (asked_at);
CREATE INDEX ix_ai_calls_used ON ml.ai_calls (cache_key) WHERE outcome = 'used';

GRANT SELECT, INSERT ON ml.ai_calls TO waypoint_ml;
ALTER TABLE ml.ai_calls ENABLE ROW LEVEL SECURITY;
ALTER TABLE ml.ai_calls FORCE ROW LEVEL SECURITY;
-- Written and read by the gateway as the system; nobody reads it through a screen yet.
CREATE POLICY ai_calls_system ON ml.ai_calls
    FOR ALL TO waypoint_ml
    USING (app.actor_is_system()) WITH CHECK (app.actor_is_system());
