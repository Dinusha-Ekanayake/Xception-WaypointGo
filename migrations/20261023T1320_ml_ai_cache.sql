-- R-ML-47: AI answers kept and served again for the same words asked the same
-- way, so a repeated "ok", "no" or question costs no call. Keyed by a SHA-256 of
-- the purpose, prompt version, model, scope and normalised masked words: the
-- words themselves are never kept. A new prompt or model is a new key, so an
-- answer under an old one is never served. Rows are not deleted (no role holds
-- DELETE); an expired one is ignored and replaced on the next answer.
CREATE TABLE IF NOT EXISTS ml.ai_cache (
    cache_key      text        PRIMARY KEY CHECK (cache_key ~ '^[0-9a-f]{64}$'),
    purpose        text        NOT NULL CHECK (purpose IN ('reading', 'draft')),
    prompt_version text        NOT NULL,
    model          text        NOT NULL,
    depot_code     text,
    answer         jsonb       NOT NULL,
    created_at     timestamptz NOT NULL,
    expires_at     timestamptz NOT NULL,
    hits           integer     NOT NULL DEFAULT 0 CHECK (hits >= 0),
    last_hit_at    timestamptz,
    CONSTRAINT ck_ai_cache_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_ai_cache_scope CHECK ((purpose = 'draft') = (depot_code IS NOT NULL))
);
COMMENT ON TABLE ml.ai_cache IS 'AI answers served again for the same normalised masked words (R-ML-47); keys are hashes, no words kept';
CREATE INDEX IF NOT EXISTS ix_ai_cache_purpose ON ml.ai_cache (purpose, created_at);

GRANT SELECT, INSERT, UPDATE ON ml.ai_cache TO waypoint_ml;
ALTER TABLE ml.ai_cache ENABLE ROW LEVEL SECURITY;
ALTER TABLE ml.ai_cache FORCE ROW LEVEL SECURITY;
-- Read and written by the jobs and the gateway as the system only; no screen reads a row.
DROP POLICY IF EXISTS ai_cache_system ON ml.ai_cache;
CREATE POLICY ai_cache_system ON ml.ai_cache
    FOR ALL TO waypoint_ml
    USING (app.actor_is_system()) WITH CHECK (app.actor_is_system());

-- A reading served from the cache cost no call, so the daily cap does not count it.
ALTER TABLE ml.message_situations ADD COLUMN IF NOT EXISTS cached boolean NOT NULL DEFAULT false;
